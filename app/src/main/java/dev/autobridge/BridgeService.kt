package dev.autobridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Lives while AutoBridge runs: listens for the phone over Bluetooth and TCP, owns the Android Auto
 * session, and plays its audio even when the projection screen is in the background.
 */
class BridgeService : Service() {
    companion object {
        @Volatile var instance: BridgeService? = null
        /** Latest status line for the UI; also delivered to [onStatus]. */
        @Volatile var status = "Starting…"
        var onStatus: ((String) -> Unit)? = null
        var onVideoActive: ((Boolean) -> Unit)? = null
        /** Projection surface from MainActivity; may exist before or after the service starts. */
        private var surface: Surface? = null
        fun setSurface(s: Surface?) { surface = s; instance?.surfaceChanged() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { Settings(this) }
    private lateinit var audio: AudioOut
    private val mic = Mic { pcm, n -> handle.takeIf { it != 0L }?.let { Native.mic(it, pcm, n) } }
    @Volatile private var handle = 0L
    private var sessionId = 0
    /** Video layout of the current session (null when no phone is connected). */
    var video: VideoConfig? = null
        private set
    private var decoder: VideoDecoder? = null
    private var car: Car? = null
    @Volatile private var moving = false
    @Volatile private var running = true
    private val searching = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audio = AudioOut(getSystemService(AudioManager::class.java))
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, notification(), types)
        watchDrivingState()
        thread(name = "ab-bluetooth") { bluetoothLoop() }
    }

    override fun onDestroy() {
        running = false
        endSession()
        car?.disconnect()
        instance = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pushSensors()  // day/night follows the car's UI mode
    }

    // ---- connection ----

    @SuppressLint("MissingPermission")  // BLUETOOTH_CONNECT is requested by MainActivity before starting us
    private fun bluetoothLoop() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return say("No Bluetooth on this car")
        while (running) {
            try {
                adapter.listenUsingRfcommWithServiceRecord("AutoBridge", UUID.fromString(Wireless.UUID)).use { server ->
                    say("Waiting for phone over Bluetooth…")
                    val socket = server.accept()
                    thread(name = "ab-handshake") {
                        socket.use {
                            val wifi = prefs.wifi() ?: return@thread say("Set the Wi-Fi details in AutoBridge settings")
                            val ip = carIp() ?: return@thread say("Car Wi-Fi hotspot is off (no address found)")
                            say("Phone ${it.remoteDevice.name ?: "?"} connected over Bluetooth")
                            try {
                                Wireless.handshake(it.inputStream, it.outputStream, "127.0.0.1", wifi, Wireless.PHONE_AA_PORT,
                                    onPhoneOnWifi = { thread(name = "ab-find") { connectToPhone(ip) } }, log = ::say)
                            } catch (_: IOException) { /* phone closed RFCOMM, normal after Wi-Fi joins */ }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "bluetooth", e)
                Thread.sleep(2000)
            }
        }
    }

    /**
     * Finds the AutoBridge Phone relay on the hotspot and connects out to it (the hotspot only
     * allows car -> phone connections). Tries every address in our /24 at once, for up to ~30 s.
     */
    private fun connectToPhone(carIp: String) {
        if (!searching.compareAndSet(false, true)) return
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        try {
            say("Looking for the AutoBridge Phone app on the hotspot…")
            val prefix = carIp.substringBeforeLast('.')
            val tasks = (1..254).map { "$prefix.$it" }.filter { it != carIp }.map { host ->
                java.util.concurrent.Callable {
                    Socket().also { s ->
                        try {
                            s.bind(InetSocketAddress(carIp, 0))  // leave through the hotspot interface
                            s.connect(InetSocketAddress(host, Wireless.PHONE_CAR_PORT), 700)
                        } catch (e: IOException) { s.close(); throw e }
                    }
                }
            }
            repeat(15) {
                val socket = try { pool.invokeAny(tasks) } catch (_: java.util.concurrent.ExecutionException) { null }
                if (socket != null) {
                    say("Found phone at ${socket.inetAddress.hostAddress}")
                    socket.tcpNoDelay = true
                    val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()  // native side owns this copy
                    socket.close()
                    main.post { startSession(fd) }
                    return
                }
                Thread.sleep(1000)
            }
            say("Phone app not found. Is AutoBridge Phone installed and running on the phone?")
        } finally {
            pool.shutdownNow()
            searching.set(false)
        }
    }

    /** Our address on the car's Wi-Fi hotspot, which the phone connects back to. */
    private fun carIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
        .sortedBy { if (it.name.contains("ap") || it.name.contains("swlan")) 0 else 1 }
        .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
        .firstOrNull()?.hostAddress

    // ---- session ----

    private fun startSession(fd: Int) {
        endSession()
        val v = Display.config(this)
        video = v
        decoder = VideoDecoder(v.codecW, v.codecH).also { d -> surface?.let { d.attach(it) } }
        handle = Native.start(fd, v.toArray(), Callbacks(++sessionId))
        pushSensors()
        say("Android Auto connected")
    }

    private fun endSession() {
        mic.open(false)  // joins the mic thread, so it can't write into a freed session
        val h = handle
        handle = 0
        if (h != 0L) Native.stop(h)
        audio.release()
        decoder?.release()
        decoder = null
        video = null
        onVideoActive?.invoke(false)
    }

    private fun surfaceChanged() {
        val s = surface
        val d = decoder ?: return
        if (s != null) d.attach(s) else d.release()
        // Asking for focus again makes the phone resend a key frame for the new decoder.
        handle.takeIf { it != 0L }?.let { Native.videoFocus(it, s != null) }
    }

    fun touch(action: Int, index: Int, ids: IntArray, xs: IntArray, ys: IntArray) {
        handle.takeIf { it != 0L }?.let { Native.touch(it, action, index, ids, xs, ys) }
    }

    /** Session events, on the native session thread. [id] tells a stale session's late events apart. */
    private inner class Callbacks(val id: Int) : Native.Listener {
        private var firstFrame = true
        override fun onVideo(data: ByteArray, timestampUs: Long) {
            decoder?.frame(data, timestampUs)
            if (firstFrame) { firstFrame = false; main.post { onVideoActive?.invoke(true) } }
        }
        override fun onAudio(stream: Int, pcm: ByteArray) = audio.write(stream, pcm)
        override fun onAudioState(stream: Int, playing: Boolean) = audio.state(stream, playing)
        override fun onMic(open: Boolean) = mic.open(open)
        override fun onVideoFocus(projected: Boolean) {
            if (!projected) main.post {  // "Exit" in Android Auto: show the car's home screen
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        override fun onEnded(reason: String) {
            main.post { if (id == sessionId) { endSession(); say("Disconnected: $reason") } }
        }
    }

    // ---- car state ----

    private fun watchDrivingState() {
        runCatching {
            car = Car.createCar(this)
            val ux = car!!.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            moving = ux.currentCarUxRestrictions.isRequiresDistractionOptimization
            ux.registerListener { r -> moving = r.isRequiresDistractionOptimization; pushSensors() }
        }.onFailure { Log.w(TAG, "no car service; reporting parked", it) }
    }

    private fun pushSensors() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        handle.takeIf { it != 0L }?.let { Native.sensors(it, moving, night) }
    }

    // ---- status ----

    private val recent = ArrayDeque<String>()

    /** Shows the last few steps on screen, so a photo of the car screen tells where a connection stopped. */
    @Synchronized private fun say(text: String) {
        Log.i(TAG, text)
        recent.addLast(text)
        if (recent.size > 6) recent.removeFirst()
        val all = recent.joinToString("\n")
        status = all
        main.post { onStatus?.invoke(all) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ab", "AutoBridge", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "ab").setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("AutoBridge is ready for Android Auto").build()
    }
}

/** Wi-Fi details the phone is given (the car's own hotspot). */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("autobridge", Context.MODE_PRIVATE)
    var ssid: String get() = p.getString("ssid", "")!!; set(v) = p.edit().putString("ssid", v.trim()).apply()
    var password: String get() = p.getString("password", "")!!; set(v) = p.edit().putString("password", v).apply()
    var bssid: String get() = p.getString("bssid", "")!!; set(v) = p.edit().putString("bssid", v.trim()).apply()
    var viewW: Int get() = p.getInt("viewW", 0); set(v) = p.edit().putInt("viewW", v).apply()
    var viewH: Int get() = p.getInt("viewH", 0); set(v) = p.edit().putInt("viewH", v).apply()

    fun wifi() = if (ssid.isEmpty() || password.isEmpty() || bssid.isEmpty()) null else Wireless.Wifi(ssid, password, bssid)
}

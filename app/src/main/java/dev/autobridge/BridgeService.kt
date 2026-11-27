package dev.autobridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
        /** The truck bar's text (null: the truck shares nothing); also delivered to [onTruck]. */
        var onTruck: ((Truck) -> Unit)? = null
        /** Set while AutoBridge's screen is in front: captures it as a PNG (null if it can't). */
        var capture: ((done: (ByteArray?) -> Unit) -> Unit)? = null
        /** Latest truck values, for the bar under Android Auto. */
        @Volatile var latestTruck = Truck()
        /** Name of the phone that last connected over Bluetooth (settings page). */
        @Volatile var phoneName: String? = null
        const val EXTRA_BOOT = "boot"
        /** No first ping this long after connecting: the handshake died (Wi-Fi dropped mid-way). */
        const val FIRST_PING_MS = 60_000L  // room for a new phone's first-time Android Auto screens
        /** Phone silent this long = gone. Its pings normally come every second or so (logged at session start). */
        const val PING_SILENCE_MS = 5_000L  // pings come every 1.0 s; the longest gap seen on the truck was 1.7 s
        var onStatus: ((String) -> Unit)? = null
        var onVideoActive: ((Boolean) -> Unit)? = null
        /** The truck started or stopped requiring distraction-optimized screens (main thread). */
        var onMoving: ((Boolean) -> Unit)? = null
        /** Projection surface from MainActivity; may exist before or after the service starts. */
        private var surface: Surface? = null
        fun setSurface(s: Surface?) { surface = s; instance?.surfaceChanged() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { Settings(this) }
    private lateinit var audio: AudioOut
    /** Held while the mic thread calls into the session and while the session is freed, so a stuck mic can't use a freed one. */
    private val micLock = Any()
    private val mic = Mic { pcm, n -> synchronized(micLock) { handle.takeIf { it != 0L }?.let { Native.mic(it, pcm, n) } } }
    @Volatile private var handle = 0L
    private var sessionId = 0
    /** Video layout of the current session (null when no phone is connected). */
    var video: VideoConfig? = null
        private set
    /** The running session's codec, for the settings page. */
    @Volatile var hevc = false
        private set
    private var decoder: VideoDecoder? = null
    private var car: Car? = null
    /** True while the truck requires distraction-optimized screens. Starts true: if the car service never answers, stay locked. */
    @Volatile var moving = true
        private set
    @Volatile private var truck = Truck()
    @Volatile private var phoneIp: String? = null
    private var lastAutoLog = 0L
    private var btDownSince = 0L
    /** Started at boot and the screen not opened since: bring the screen up when the phone connects. */
    @Volatile private var openScreenOnConnect = false
    private var watchingGps = false
    @Volatile private var running = true
    // Closed in onDestroy so threads blocked on them end: a stopped copy left listening took the phone's next connection.
    @Volatile private var btServer: java.io.Closeable? = null
    @Volatile private var logLine: Socket? = null
    @Volatile private var btLink: java.io.Closeable? = null
    /** Sends the phone a new "join the hotspot" request over the open Bluetooth link (null: no link). */
    @Volatile private var invite: (() -> Unit)? = null
    @Volatile private var busyAt = 0L
    @Volatile private var invitedAt = 0L
    private val searching = java.util.concurrent.atomic.AtomicBoolean(false)
    /** When the current session started, and whether the phone has pinged in it yet (it then got past the handshake). */
    @Volatile private var startedAt = 0L
    @Volatile private var pinged = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logs.startCapture(this)
        Settings(this).let { s -> Equalizer.music.gains = Equalizer.decode(s.eqGains); Equalizer.music.enabled = s.eqEnabled }
        audio = AudioOut(getSystemService(AudioManager::class.java)) { say("The car took the speakers (another audio source started)") }
        // At boot only connectedDevice is allowed; the screen upgrades to media + mic when it opens.
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        watchDrivingState()
        thread(name = "ab-bluetooth") { bluetoothLoop() }
        thread(name = "ab-logline", isDaemon = true) { logRequestLoop() }
        thread(name = "ab-setup", isDaemon = true) { setupLoop() }
        thread(name = "ab-reinvite", isDaemon = true) { reinviteLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) openScreenOnConnect = true else fullForeground()
        return START_STICKY
    }

    /** Set once the owner allowed full-screen capture: the service must then also run as a mediaProjection one. */
    @Volatile var projecting = false

    /** Media playback and microphone service types; only allowed while AutoBridge's screen is open. */
    fun fullForeground() {
        openScreenOnConnect = false
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (gpsAllowed()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (projecting) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION  // dropping it would end the capture
        runCatching { startForeground(1, notification(), types) }.onFailure { Log.w(TAG, "foreground upgrade refused", it) }
        locationAllowed = true
        if (handle != 0L) watchGps()
    }

    /** The location service type is on (the screen has been opened); GPS before that gets no fixes. */
    private var locationAllowed = false

    private fun gpsAllowed() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The truck's own GPS, passed to Android Auto so Maps doesn't depend on a phone in a pocket. */
    @SuppressLint("MissingPermission")  // checked by gpsAllowed()
    private fun watchGps() {
        if (watchingGps || !locationAllowed || !gpsAllowed()) return
        val lm = getSystemService(LocationManager::class.java)
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) { Log.w(TAG, "no GPS provider on this car"); return }
        watchingGps = true
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, Looper.getMainLooper())
        Log.i(TAG, "truck GPS: watching")
    }

    /** Only Android Auto uses the fixes: off between sessions, so a parked truck isn't polling GPS for hours. */
    private fun stopGps() {
        if (!watchingGps) return
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(gps) }
        watchingGps = false
    }

    /** Kept so endSession/onDestroy can remove it; otherwise an exited service keeps the GPS on and stays in memory. */
    private val gps = LocationListener { l -> sendLocation(l) }

    private fun sendLocation(l: Location) {
        val h = handle.takeIf { it != 0L } ?: return
        Native.location(h, intArrayOf(
            (l.latitude * 1e7).toInt(), (l.longitude * 1e7).toInt(),
            if (l.hasAccuracy()) (l.accuracy * 1000).toInt() else -1,
            if (l.hasAltitude()) (l.altitude * 100).toInt() else Int.MIN_VALUE,
            if (l.hasSpeed()) (l.speed * 1000).toInt() else -1,
            if (l.hasBearing()) (l.bearing * 1e6).toInt() else -1,
        ))
    }

    override fun onDestroy() {
        ScreenCapture.stop()
        running = false
        stopGps()
        runCatching { btServer?.close() }
        runCatching { logLine?.close() }
        runCatching { btLink?.close() }
        main.removeCallbacks(watchdog)
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
                    btServer = server
                    if (!running) return  // stopped while we were opening it
                    if (btDownSince != 0L) { Log.i(TAG, "Bluetooth back after ${(System.currentTimeMillis() - btDownSince) / 1000} s"); btDownSince = 0L }
                    say("Waiting for phone over Bluetooth… (phone: Bluetooth on and connected to the truck, Wireless Android Auto on)")
                    val socket = server.accept()
                    if (!running) { socket.close(); return }
                    thread(name = "ab-handshake") {
                        socket.use {
                            btLink = it
                            // A new truck may get its details from the phone app a few seconds after the phone's
                            // Bluetooth arrives; wait for them rather than hang up, since the phone won't retry soon.
                            var wifi = prefs.wifi()
                            if (wifi == null) say("Phone connected over Bluetooth; waiting for the hotspot details from AutoBridge Phone…")
                            for (i in 1..30) { if (wifi != null) break; Thread.sleep(2000); wifi = prefs.wifi() }
                            if (wifi == null) return@thread say("No hotspot details yet: open AutoBridge Phone on the truck's hotspot and set the password, or tap EDIT in settings")
                            val ip = carIp() ?: return@thread say("Car Wi-Fi hotspot is off (no address found)")
                            phoneName = it.remoteDevice.name
                            say("Phone ${it.remoteDevice.name ?: "?"} connected over Bluetooth")
                            try {
                                val first = wifi
                                Wireless.handshake(it.inputStream, it.outputStream, "127.0.0.1", { prefs.wifi() ?: first }, Wireless.PHONE_AA_PORT,
                                    onPhoneOnWifi = { thread(name = "ab-find") { connectToPhone(ip) } }, log = ::say,
                                    onLink = { send -> busyAt = System.currentTimeMillis(); invite = send }, busy = { handle != 0L })
                            } catch (_: IOException) {
                                Log.i(TAG, "Bluetooth link to the phone closed")
                            } finally {
                                if (btLink === it) { btLink = null; invite = null }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (!running) return  // onDestroy closed the listener
                // One line per outage, not a stack trace every 2 s: a parked truck's Bluetooth can be off for
                // hours, and those traces pushed everything else out of the log.
                if (btDownSince == 0L) { btDownSince = System.currentTimeMillis(); Log.w(TAG, "Bluetooth not ready (${e.message}); retrying quietly") }
                Thread.sleep(2000)
            }
        }
    }

    /** One pass over every address in the car's /24: the first that accepts on [port] and passes [check], or null. */
    private fun sweep(pool: java.util.concurrent.ExecutorService, carIp: String, port: Int, check: (Socket) -> Boolean = { true }): Socket? {
        val prefix = carIp.substringBeforeLast('.')
        val opened = java.util.concurrent.ConcurrentLinkedQueue<Socket>()
        val tasks = (1..254).map { "$prefix.$it" }.filter { it != carIp }.map { host ->
            java.util.concurrent.Callable {
                Socket().also { s ->
                    try {
                        s.bind(InetSocketAddress(carIp, 0))  // leave through the hotspot interface
                        s.connect(InetSocketAddress(host, port), 700)
                        opened += s
                        if (!check(s)) throw IOException("$host is not AutoBridge Phone")
                    } catch (e: IOException) { s.close(); throw e }
                }
            }
        }
        val winner = try { pool.invokeAny(tasks) } catch (_: java.util.concurrent.ExecutionException) { null }
        // A connect ignores cancellation, so others may also have connected: close them, or each sweep leaks one.
        thread(isDaemon = true) { Thread.sleep(750); opened.filter { it !== winner }.forEach { runCatching { it.close() } } }
        return winner
    }

    /**
     * New truck: asks the AutoBridge Phone app on the hotspot for the Wi-Fi details until it has them.
     * After that, checks them once against each phone it finds, so a typo or a changed hotspot fixes itself.
     */
    private fun setupLoop() {
        var checkedIp: String? = null
        var wait = 10_000L
        while (running) {
            val ip = phoneIp
            if (prefs.wifi() == null || (ip != null && ip != checkedIp)) {
                // No phone app answered: ask less often (up to every 2 min), since each ask tries the whole hotspot.
                val answered = runCatching { checkWifi(quiet = true) }.getOrDefault(false)
                if (answered && ip != null) checkedIp = ip
                wait = if (answered) 10_000L else minOf(wait * 2, 120_000L)
            } else wait = 10_000L
            Thread.sleep(wait)
        }
    }

    /** Settings' TEST button: compares the saved hotspot details with the phone's and fixes them. */
    fun testWifi(report: (String) -> Unit) = thread(name = "ab-wifitest") {
        val line = runCatching { checkWifi(quiet = false); lastCheck }.getOrElse { "Wi-Fi check failed: ${it.message}".also(::say) }
        main.post { report(line ?: "Wi-Fi check: no answer") }
    }

    /** True if the phone app answered. [quiet]: say nothing when it isn't found. */
    private var lastCheck: String? = null
    /** Not the service's own lock: say() takes that one on the main thread, and a check can wait seconds on the network. */
    private val wifiCheckLock = Any()

    private fun checkWifi(quiet: Boolean): Boolean = synchronized(wifiCheckLock) {
        val car = carIp() ?: return false.also { if (!quiet) { lastCheck = "Wi-Fi check: the truck's hotspot is off"; say(lastCheck!!) } }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        val reply = try {
            val known = phoneIp?.let { ip -> Socket().let { so ->
                try { so.bind(InetSocketAddress(car, 0)); so.connect(InetSocketAddress(ip, Wireless.PHONE_SETUP_PORT), 700); so }
                catch (_: IOException) { so.close(); null } } }
            (known ?: sweep(pool, car, Wireless.PHONE_SETUP_PORT))?.use { it.soTimeout = 5000; it.getInputStream().readBytes().decodeToString() }
        } finally { pool.shutdownNow() }
        if (reply == null && quiet) return false
        val s = Settings(this)
        val saved = s.wifi()
        val (found, checked) = Wireless.checkSetup(reply, saved)
        // Only TEST or a brand-new truck may change saved details: on its own, a check could be answered by any
        // device on the hotspot pretending to be the phone.
        val fix = found?.takeIf { !quiet || saved == null }
        val message = if (found != null && fix == null && saved != null)
            "Wi-Fi check: the hotspot ${Wireless.differences(found, saved).joinToString(" and ")} on your phone differs from the truck's; tap TEST in settings to use the phone's"
        else checked
        fix?.let { s.ssid = it.ssid; s.password = it.password; s.bssid = it.bssid }
        // TEST always reports. On its own, a working truck only speaks when it fixed something, and a new truck
        // (asking every 10 s) doesn't repeat itself.
        if (!quiet || found != null || (saved == null && message != lastCheck)) say(message)
        lastCheck = message
        return reply != null
    }

    /**
     * Finds the AutoBridge Phone relay on the hotspot and connects out to it (the hotspot only
     * allows car -> phone connections). Tries every address in our /24 at once, for up to ~30 s.
     */
    private fun connectToPhone(carIp: String) {
        if (handle != 0L) return  // already running; a dead session is ended by the watchdog within 5 s, then the phone is invited again
        val password = prefs.wifi()?.password ?: return
        if (!searching.compareAndSet(false, true)) return
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        try {
            say("Looking for the AutoBridge Phone app on the hotspot…")
            repeat(15) {
                // Only the phone app that knows the hotspot password: anyone else on the hotspot could answer first.
                val socket = sweep(pool, carIp, Wireless.PHONE_CAR_PORT) { s ->
                    s.soTimeout = 3000
                    Pairing.truck(s, password).also { s.soTimeout = 0 }
                }
                if (socket != null) {
                    phoneIp = socket.inetAddress.hostAddress
                    say("Found phone at $phoneIp")
                    // The log-request line may be dead (Wi-Fi dropped, or a new address): reconnect it now.
                    runCatching { logLine?.close() }
                    socket.tcpNoDelay = true
                    val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()  // native side owns this copy
                    socket.close()
                    main.post { startSession(fd) }
                    return
                }
                Thread.sleep(1000)
            }
            say("Phone app not found. Is AutoBridge Phone 0.8.1 or newer running on the phone, with the hotspot password saved (Password)?")
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

    /**
     * After a Wi-Fi drop the phone doesn't come back by itself; a car asks it again over the still-open
     * Bluetooth link. Every 10 s without a session (and not mid-connection), send that request.
     */
    private fun reinviteLoop() {
        while (running) {
            Thread.sleep(2000)
            val send = invite ?: continue
            val now = System.currentTimeMillis()
            if (handle != 0L || searching.get()) { busyAt = now; continue }
            if (now - busyAt < 5000 || now - invitedAt < 10_000) continue
            invitedAt = now
            say("No Android Auto: asking the phone to rejoin the hotspot")
            runCatching { send() }.onFailure { Log.i(TAG, "invite failed: $it") }
        }
    }

    /** The phone pings all the time; silence means it's gone (Wi-Fi dropped) even if no error arrives. */
    private val watchdog = object : Runnable {
        override fun run() {
            val h = handle.takeIf { it != 0L } ?: return
            val since = Native.sincePingMs(h)  // -1 until the first ping
            if (since >= 0) pinged = true
            if (since > PING_SILENCE_MS || (since < 0 && System.currentTimeMillis() - startedAt > FIRST_PING_MS)) {
                endSession()
                say("Disconnected: the phone stopped answering (Wi-Fi lost?)")
                busyAt = 0  // ask the phone to rejoin right away, not after the usual 5 s settle
                return
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun startSession(fd: Int) {
        if (!running) { ParcelFileDescriptor.adoptFd(fd).close(); return }  // this copy was stopped meanwhile
        endSession()
        val plan = Display.config(this)
        val v = plan.main
        video = v; hevc = plan.hevc
        decoder = VideoDecoder(plan.mime, v.codecW, v.codecH) {
            // Re-sending video focus makes the phone send a fresh key frame.
            // On main, like every other caller: endSession there frees the session, so the handle can't go stale mid-call.
            main.post { handle.takeIf { it != 0L }?.let { h -> Native.videoFocus(h, true) } }
        }.also { d -> surface?.let { d.attach(it) } }
        audio.holdFocus()
        NowPlaying.connected(this)
        startedAt = System.currentTimeMillis(); pinged = false
        handle = Native.start(fd, plan.toArray(), truck.toArray(), gpsAllowed(), prefs.aaHidden, carInfo, Callbacks(++sessionId, plan))
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 2000)
        watchGps()
        Log.i(TAG, "video offered: ${if (plan.hevc) "H.265" else "H.264"} " + plan.configs.joinToString { "${it.codecW}x${it.codecH}" } +
            " for an area of ${prefs.viewW}x${prefs.viewH} (truck bar ${if (prefs.barEnabled) "on" else "off"}, status bar ${if (prefs.statusBarShown) "on" else "off"}), size ${if (prefs.aaDpi > 0) "${prefs.aaDpi}" else "Auto ${Display.dpi(this)}"}, hidden icons ${prefs.aaHidden}")
        pushSensors()
        say("Android Auto connected")
        // Started at boot, nobody has opened the screen yet: try to bring it up for the video.
        if (openScreenOnConnect) runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "could not open the screen by itself: $it") }
    }

    private fun endSession() {
        mic.open(false)  // joins the mic thread, so it can't write into a freed session
        stopGps()
        main.removeCallbacks(watchdog)
        val h = handle
        synchronized(micLock) { handle = 0 }
        if (h != 0L) Native.stop(h)
        audio.release()
        NowPlaying.disconnected()
        ClusterNav.phoneGone()
        decoder?.release()
        decoder = null
        video = null
        onVideoActive?.invoke(false)
    }

    private fun surfaceChanged() {
        val s = surface
        val d = decoder ?: return
        if (s != null) d.attach(s) else d.detach()
        // Asking for focus again makes the phone resend a key frame for the new decoder.
        handle.takeIf { it != 0L }?.let { Native.videoFocus(it, s != null) }
    }

    fun key(code: Int) { handle.takeIf { it != 0L }?.let { Native.key(it, code) } }

    /**
     * The phone can't connect to the car (the hotspot blocks it), so the car keeps a line open to the
     * phone app; each 'L' the phone sends on it asks for a log.
     */
    private fun logRequestLoop() {
        while (running) {
            val ip = phoneIp
            if (ip == null) { Thread.sleep(5000); continue }
            try {
                Socket().use { s ->
                    logLine = s
                    carIp()?.let { s.bind(InetSocketAddress(it, 0)) }
                    s.connect(InetSocketAddress(ip, Wireless.PHONE_LOG_REQUEST_PORT), 3000)
                    s.keepAlive = true
                    val input = s.getInputStream()
                    while (running) {
                        val b = input.read()
                        if (b < 0) break
                        if (b == 'L'.code) sendLogs("phone")
                        if (b == 'S'.code) sendScreenshot()
                    }
                }
            } catch (_: IOException) { /* phone app gone or older; try again shortly */ }
            Thread.sleep(5000)
        }
    }

    /** The phone app's screenshot button: AutoBridge's screen as the driver sees it, saved next to the logs. */
    private fun sendScreenshot() {
        val ip = phoneIp ?: return
        val grab = capture ?: return say("Screenshot: AutoBridge isn't on the truck's screen")
        main.post {
            grab { png ->
                if (png == null) return@grab say("Screenshot failed")
                thread(name = "ab-shot") {
                    val name = Logs.name("screen").removeSuffix(".txt") + ".png"
                    try { Logs.send(ip, carIp(), name, png); say("Screenshot saved on the phone: $name") }
                    catch (e: IOException) { say("Screenshot not sent: ${e.message}") }
                }
            }
        }
    }

    /** Saves a log file on the phone, over the hotspot. [auto]: after a failure, at most every 2 min. */
    fun sendLogs(reason: String, auto: Boolean = false) {
        val now = System.currentTimeMillis()
        if (auto && now - lastAutoLog < 120_000) return
        if (auto) lastAutoLog = now
        val ip = phoneIp ?: return say("No phone found yet, so logs can't be sent")
        thread(name = "ab-logs") {
            val name = Logs.name(reason)
            val s = Settings(this)
            val header = listOf(
                "Reason: $reason",
                "Hotspot: ${s.ssid} / ${s.bssid}, car ${carIp()}, phone $ip",  // never the password
                "Screen: ${s.viewW}x${s.viewH}, video: ${video?.let { "${it.codecW}x${it.codecH}" } ?: "not connected"}, H.265 ${if (s.hevcFailed) "failed in this version" else "allowed"}",
                "GPS: ${if (gpsAllowed()) "allowed" else "not allowed"}, voice button: ${VoiceAssistant.status(this)}",
                "Truck: $latestTruck",
                "Route: ${ClusterNav.route}, cluster ${if (ClusterNav.sink != null) "bound" else "not bound"}",
                "Status: " + synchronized(this) { recent.joinToString(" | ") },
                "Car permissions: ${TruckProbe.permissions(this)}",
                "Android Auto size: ${if (s.aaDpi > 0) "${s.aaDpi}" else "Auto ${Display.dpi(this)}"}, hidden icons ${s.aaHidden} (1 clock, 2 signal, 4 battery)",
                "Fonts on the truck: ${Fonts.installed()}",
                "GMC font used: ${Fonts.gmFont()}",
                "---- truck data (every value the truck lets AutoBridge read) ----",
            ) + (runCatching { (car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).let { TruckProbe.dump(it) + TruckProbe.hidden(it) } }.getOrElse { listOf("no car service: $it") })
            try {
                Logs.send(ip, carIp(), name, Logs.collect(this, reason, header))
                say("Log saved on the phone: $name")
            } catch (e: IOException) {
                say("Couldn't send the log to the phone: ${e.message}")
            }
        }
    }

    fun touch(action: Int, index: Int, ids: IntArray, xs: IntArray, ys: IntArray) {
        handle.takeIf { it != 0L }?.let { Native.touch(it, action, index, ids, xs, ys) }
    }

    /** Session events, on the native session thread. [id] tells a stale session's late events apart. */
    private inner class Callbacks(val id: Int, val plan: VideoPlan) : Native.Listener {
        private var firstFrame = true
        override fun onVideo(data: ByteArray, timestampUs: Long) {
            decoder?.frame(data, timestampUs)
            if (firstFrame) { firstFrame = false; Log.i(TAG, "first video frame${if (onVideoActive == null) " (screen not in front)" else ""}"); main.post { onVideoActive?.invoke(true) } }
        }
        override fun onAudio(stream: Int, pcm: ByteArray) = audio.write(stream, pcm)
        override fun onAudioState(stream: Int, playing: Boolean) = audio.state(stream, playing)
        // On main and for this session only: a late "open" while the session stops would leave the mic recording.
        override fun onMic(open: Boolean) { main.post { if (id == sessionId && handle != 0L) mic.open(open) } }
        override fun onVideoFocus(projected: Boolean) {
            if (!projected) main.post {  // "Exit" in Android Auto: show the car's home screen
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        override fun onEnded(reason: String) {
            val handshakeDone = pinged || handle.takeIf { it != 0L }?.let { Native.sincePingMs(it) >= 0 } == true
            main.post {
                if (id == sessionId) {
                    val neverShown = decoder?.let { it.shown && !it.decoded } == true  // read before endSession releases it
                    endSession()
                    say("Disconnected: $reason")
                    if (reason != "phone disconnected") {
                        // H.265 never produced a picture: use the proven 1080p H.264 from the next connection on.
                        if (plan.hevc && neverShown && handshakeDone) {  // a drop mid-handshake isn't H.265's fault
                            Settings(this@BridgeService).hevcFailed = true
                            say("Sharper picture (H.265) didn't start; using 1080p next time")
                        }
                        sendLogs("auto", auto = true)
                    }
                }
            }
        }
        override fun onNowPlaying(song: String?, artist: String?, album: String?, art: ByteArray?, durationSec: Int) =
            NowPlaying.metadata(song, artist, album, art, durationSec)
        override fun onPlayback(state: Int, positionSec: Int) = NowPlaying.playback(state, positionSec)
        override fun onNavStatus(status: Int) = ClusterNav.status(status)
        override fun onNavStep(type: Int, exit: Int, road: String?, cue: String?, destination: String?) = ClusterNav.step(type, exit, road, cue, destination)
        override fun onNavLegacyTurn(event: Int, side: Int, exit: Int, road: String?) = ClusterNav.legacyTurn(event, side, exit, road)
        override fun onNavDistance(meters: Int, e3: Int, unit: Int, sec: Int, destMeters: Int, destE3: Int, destUnit: Int, destSec: Int, currentRoad: String?) =
            ClusterNav.distance(meters, e3, unit, sec, destMeters, destE3, destUnit, destSec, currentRoad)
    }

    // ---- car state ----

    private fun watchDrivingState() {
        runCatching {
            car = Car.createCar(this)
            val ux = car!!.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            moving = ux.currentCarUxRestrictions.isRequiresDistractionOptimization
            ux.registerListener { r -> moving = r.isRequiresDistractionOptimization; pushSensors(); main.post { onMoving?.invoke(moving) } }
            (car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).let { watchTruck(it); TruckProbe.record(it); carInfo = readCarInfo(it) }
        }.onFailure { Log.w(TAG, "no car service; reporting moving so driving limits stay on", it) }
    }

    /** The truck's make, model and year for Android Auto ("" for any it won't share: AutoBridge's own then). */
    @Volatile private var carInfo = arrayOf("", "", "")

    private fun readCarInfo(props: CarPropertyManager): Array<String> {
        fun read(id: Int) = runCatching { props.getProperty<Any>(id, 0).value?.toString()?.trim() ?: "" }.getOrDefault("")
        val make = read(VehiclePropertyIds.INFO_MAKE)
        // GM reports its internal code: name the truck the way the owner would.
        val model = read(VehiclePropertyIds.INFO_MODEL).let { if (it == "BT1CG") "Sierra EV Denali" else it }
        return arrayOf(make, model, read(VehiclePropertyIds.INFO_MODEL_YEAR))
            .also { Log.i(TAG, "truck for Android Auto: ${it.joinToString(" ").ifBlank { "not shared" }}") }
    }

    /** Battery, range, speed, gear and parking brake. Whatever the truck refuses simply stays unknown. */
    private fun watchTruck(props: CarPropertyManager) {
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                if (v.status != CarPropertyValue.STATUS_AVAILABLE) return
                val x = v.value
                truck = when (v.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> truck.copy(speedMs = (x as? Float)?.let(Math::abs))  // negative in reverse
                    VehiclePropertyIds.GEAR_SELECTION -> truck.copy(gear = x as? Int)
                    VehiclePropertyIds.PARKING_BRAKE_ON -> truck.copy(parkingBrake = x as? Boolean)
                    VehiclePropertyIds.EV_BATTERY_LEVEL -> truck.copy(batteryWh = x as? Float)
                    VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY -> truck.copy(capacityWh = x as? Float)
                    VehiclePropertyIds.RANGE_REMAINING -> truck.copy(rangeM = x as? Float)
                    VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE -> truck.copy(rateMw = x as? Float)
                    VehiclePropertyIds.EV_CHARGE_STATE -> truck.copy(chargeState = x as? Int)
                    VehiclePropertyIds.EV_CHARGE_TIME_REMAINING -> truck.copy(chargeSec = x as? Int)
                    VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT -> truck.copy(chargeLimit = x as? Float)
                    VehiclePropertyIds.EV_CHARGE_CURRENT_DRAW_LIMIT -> truck.copy(currentA = x as? Float)
                    VehiclePropertyIds.EV_CHARGE_PORT_OPEN -> truck.copy(portOpen = x as? Boolean)
                    VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED -> truck.copy(portConnected = x as? Boolean)
                    VehiclePropertyIds.EV_REGENERATIVE_BRAKING_STATE -> truck.copy(regen = x as? Int)
                    else -> truck
                }
                if (v.propertyId in AA_PROPS) pushSensors()
                latestTruck = truck
                val t = truck
                main.post { onTruck?.invoke(t) }
            }
            override fun onErrorEvent(propertyId: Int, zone: Int) {}
        }
        // The truck's own distance unit; read once, it only changes in the truck's settings.
        runCatching { truck = truck.copy(miles = props.getProperty<Int>(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, 0).value == 0x24) }
        for (id in AA_PROPS + listOf(VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, VehiclePropertyIds.EV_CHARGE_STATE,
            VehiclePropertyIds.EV_CHARGE_TIME_REMAINING, VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT, VehiclePropertyIds.EV_CHARGE_CURRENT_DRAW_LIMIT,
            VehiclePropertyIds.EV_CHARGE_PORT_OPEN, VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED, VehiclePropertyIds.EV_REGENERATIVE_BRAKING_STATE)) {
            val rate = if (id == VehiclePropertyIds.PERF_VEHICLE_SPEED || id == VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE) 1f
                       else CarPropertyManager.SENSOR_RATE_ONCHANGE
            runCatching { props.registerCallback(callback, id, rate) }.onFailure { Log.w(TAG, "truck won't share property $id: $it") }
        }
    }

    /** What Android Auto is told about (Native.sensors); the rest only feeds the bar. */
    private val AA_PROPS = listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.PARKING_BRAKE_ON,
        VehiclePropertyIds.EV_BATTERY_LEVEL, VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, VehiclePropertyIds.RANGE_REMAINING)

    private fun pushSensors() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        handle.takeIf { it != 0L }?.let { Native.sensors(it, moving, night, truck.toArray()) }
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
class Settings(private val context: Context) {
    companion object {
        // Android Auto's session_configuration bits (ServiceDiscoveryResponse field 13)
        const val HIDE_CLOCK = 1
        const val HIDE_SIGNAL = 2
        const val HIDE_BATTERY = 4
    }
    private val p = context.getSharedPreferences("autobridge", Context.MODE_PRIVATE)
    var ssid: String get() = p.getString("ssid", "")!!; set(v) = p.edit().putString("ssid", v.trim()).apply()
    var password: String get() = p.getString("password", "")!!; set(v) = p.edit().putString("password", v).apply()
    var bssid: String get() = p.getString("bssid", "")!!; set(v) = p.edit().putString("bssid", v.trim()).apply()
    // Android Auto's area, remembered for each truck bar / status bar combination: the same settings always give
    // Android Auto the same size, so it picks the same layout. (2012x1680 is a hair under the line between map-on-top
    // and map-beside; one in-between measurement used to flip it until the next reconnect.) The old shared value is
    // only a first-time fallback.
    private val shape get() = "${if (barEnabled) "bar" else ""}${if (statusBarShown) "status" else ""}"
    var viewW: Int get() = p.getInt("viewW.$shape", p.getInt("viewW", 0)); set(v) = p.edit().putInt("viewW.$shape", v).apply()
    var viewH: Int get() = p.getInt("viewH.$shape", p.getInt("viewH", 0)); set(v) = p.edit().putInt("viewH.$shape", v).apply()
    /** Android Auto's density for this bar combination (settings page); 0 = the truck's own, so Android Auto's text
     *  matches the truck's. Lower shows more (more cards), higher shows bigger and fewer. */
    var aaDpi: Int get() = p.getInt("aaDpi.$shape", 0); set(v) { p.edit().putInt("aaDpi.$shape", v).commit() }
    /** Android Auto status icons to hide (settings page): [HIDE_CLOCK], [HIDE_SIGNAL], [HIDE_BATTERY]. All shown by default. */
    var aaHidden: Int get() = p.getInt("aaHidden", 0); set(v) { p.edit().putInt("aaHidden", v).commit() }
    /** H.265 failed to start in this app version; each update tries it again. */
    var hevcFailed: Boolean
        get() = p.getLong("hevcFailedIn", -1) == version
        set(v) = p.edit().putLong("hevcFailedIn", if (v) version else -1).apply()
    private val version by lazy { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }  // a binder call: only when needed

    /** The truck bar under Android Auto (settings page). */
    // commit(), not apply(): changing these restarts the process right after (SettingsPanel), which would lose a pending write.
    var barEnabled: Boolean get() = p.getBoolean("bar", true); set(v) { p.edit().putBoolean("bar", v).commit() }
    /** The truck's own status bar at the top (settings page); off gives Android Auto that space too. */
    var statusBarShown: Boolean get() = p.getBoolean("statusBar", true); set(v) { p.edit().putBoolean("statusBar", v).commit() }
    /** Offer the sharper H.265 1440p picture (settings page); off forces the proven 1080p. */
    var hevcEnabled: Boolean get() = p.getBoolean("hevc", true); set(v) { p.edit().putBoolean("hevc", v).commit() }
    /** The music EQ (settings page): band gains in dB, on/off, and the preset name ("Custom" once edited). */
    var eqGains: String? get() = p.getString("eqGains", null); set(v) = p.edit().putString("eqGains", v).apply()
    var eqEnabled: Boolean get() = p.getBoolean("eqOn", true); set(v) = p.edit().putBoolean("eqOn", v).apply()
    var eqPreset: String get() = p.getString("eqPreset", "Flat") ?: "Flat"; set(v) = p.edit().putString("eqPreset", v).apply()
    /** The owner's saved EQs (EQ 1..3 on the EQ page), as [Equalizer.encode]; null while empty. */
    fun eqSlot(n: Int): String? = p.getString("eqSlot$n", null)
    fun setEqSlot(n: Int, gains: String) = p.edit().putString("eqSlot$n", gains).apply()

    fun forget() = p.edit().remove("ssid").remove("password").remove("bssid").apply()

    fun wifi() = if (ssid.isEmpty() || password.isEmpty() || bssid.isEmpty()) null else Wireless.Wifi(ssid, password, bssid)
}

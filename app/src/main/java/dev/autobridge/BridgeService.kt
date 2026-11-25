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
        @Volatile var truckText: String? = null
        var onTruck: ((Truck) -> Unit)? = null
        /** Latest truck values, for the bar under Android Auto. */
        @Volatile var latestTruck = Truck()
        /** Name of the phone that last connected over Bluetooth (settings page). */
        @Volatile var phoneName: String? = null
        const val EXTRA_BOOT = "boot"
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
    @Volatile private var truck = Truck()
    @Volatile private var phoneIp: String? = null
    private var lastAutoLog = 0L
    private var btDownSince = 0L
    /** Started at boot and the screen not opened since: bring the screen up when the phone connects. */
    @Volatile private var openScreenOnConnect = false
    private var watchingGps = false
    @Volatile private var running = true
    private val searching = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logs.startCapture()
        audio = AudioOut(getSystemService(AudioManager::class.java)) { say("The car took the speakers (another audio source started)") }
        // At boot only connectedDevice is allowed; the screen upgrades to media + mic when it opens.
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        watchDrivingState()
        thread(name = "ab-bluetooth") { bluetoothLoop() }
        thread(name = "ab-logline", isDaemon = true) { logRequestLoop() }
        thread(name = "ab-setup", isDaemon = true) { setupLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) openScreenOnConnect = true else fullForeground()
        return START_STICKY
    }

    /** Media playback and microphone service types; only allowed while AutoBridge's screen is open. */
    fun fullForeground() {
        openScreenOnConnect = false
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (gpsAllowed()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        runCatching { startForeground(1, notification(), types) }.onFailure { Log.w(TAG, "foreground upgrade refused", it) }
        watchGps()
    }

    private fun gpsAllowed() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The truck's own GPS, passed to Android Auto so Maps doesn't depend on a phone in a pocket. */
    @SuppressLint("MissingPermission")  // checked by gpsAllowed()
    private fun watchGps() {
        if (watchingGps || !gpsAllowed()) return
        val lm = getSystemService(LocationManager::class.java)
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) { Log.w(TAG, "no GPS provider on this car"); return }
        watchingGps = true
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, LocationListener { l -> sendLocation(l) }, Looper.getMainLooper())
        Log.i(TAG, "truck GPS: watching")
    }

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
                    if (btDownSince != 0L) { Log.i(TAG, "Bluetooth back after ${(System.currentTimeMillis() - btDownSince) / 1000} s"); btDownSince = 0L }
                    say("Waiting for phone over Bluetooth…")
                    val socket = server.accept()
                    thread(name = "ab-handshake") {
                        socket.use {
                            val wifi = prefs.wifi() ?: return@thread say("Set the Wi-Fi details in AutoBridge settings")
                            val ip = carIp() ?: return@thread say("Car Wi-Fi hotspot is off (no address found)")
                            phoneName = it.remoteDevice.name
                            say("Phone ${it.remoteDevice.name ?: "?"} connected over Bluetooth")
                            try {
                                Wireless.handshake(it.inputStream, it.outputStream, "127.0.0.1", wifi, Wireless.PHONE_AA_PORT,
                                    onPhoneOnWifi = { thread(name = "ab-find") { connectToPhone(ip) } }, log = ::say)
                            } catch (_: IOException) { /* phone closed RFCOMM, normal after Wi-Fi joins */ }
                        }
                    }
                }
            } catch (e: IOException) {
                // One line per outage, not a stack trace every 2 s: a parked truck's Bluetooth can be off for
                // hours, and those traces pushed everything else out of the log.
                if (btDownSince == 0L) { btDownSince = System.currentTimeMillis(); Log.w(TAG, "Bluetooth not ready (${e.message}); retrying quietly") }
                Thread.sleep(2000)
            }
        }
    }

    /**
     * Finds the AutoBridge Phone relay on the hotspot and connects out to it (the hotspot only
     * allows car -> phone connections). Tries every address in our /24 at once, for up to ~30 s.
     */
    /** One pass over every address in the car's /24: the first that accepts on [port], or null. */
    private fun sweep(pool: java.util.concurrent.ExecutorService, carIp: String, port: Int): Socket? {
        val prefix = carIp.substringBeforeLast('.')
        val tasks = (1..254).map { "$prefix.$it" }.filter { it != carIp }.map { host ->
            java.util.concurrent.Callable {
                Socket().also { s ->
                    try {
                        s.bind(InetSocketAddress(carIp, 0))  // leave through the hotspot interface
                        s.connect(InetSocketAddress(host, port), 700)
                    } catch (e: IOException) { s.close(); throw e }
                }
            }
        }
        return try { pool.invokeAny(tasks) } catch (_: java.util.concurrent.ExecutionException) { null }
    }

    /**
     * No Wi-Fi details yet (a new truck): ask the AutoBridge Phone app on the hotspot for them.
     * Typing them in Wi-Fi settings still works, and once saved this stops asking.
     */
    private fun setupLoop() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        while (running) {
            val s = Settings(this)
            val ip = carIp()
            if (s.wifi() == null && ip != null) {
                runCatching {
                    sweep(pool, ip, Wireless.PHONE_SETUP_PORT)?.use { socket ->
                        socket.soTimeout = 5000
                        val w = Wireless.parseSetup(socket.getInputStream().readBytes().decodeToString())
                        if (w == null) say("The phone app sent no usable Wi-Fi details (set the hotspot password in the phone app)")
                        else if (s.wifi() == null) {
                            s.ssid = w.ssid; s.password = w.password; s.bssid = w.bssid
                            say("Got the hotspot details from your phone: ${w.ssid}")
                        }
                    }
                }
            }
            Thread.sleep(10_000)
        }
        pool.shutdownNow()
    }

    private fun connectToPhone(carIp: String) {
        if (!searching.compareAndSet(false, true)) return
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        try {
            say("Looking for the AutoBridge Phone app on the hotspot…")
            repeat(15) {
                val socket = sweep(pool, carIp, Wireless.PHONE_CAR_PORT)
                if (socket != null) {
                    phoneIp = socket.inetAddress.hostAddress
                    say("Found phone at $phoneIp")
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
        val plan = Display.config(this)
        val v = plan.main
        video = v
        decoder = VideoDecoder(plan.mime, v.codecW, v.codecH) {
            // Re-sending video focus makes the phone send a fresh key frame.
            handle.takeIf { it != 0L }?.let { h -> Native.videoFocus(h, true) }
        }.also { d -> surface?.let { d.attach(it) } }
        audio.holdFocus()
        NowPlaying.connected(this)
        handle = Native.start(fd, plan.toArray(), truck.toArray(), gpsAllowed(), Callbacks(++sessionId, plan))
        Log.i(TAG, "video offered: ${if (plan.hevc) "H.265" else "H.264"} " + plan.configs.joinToString { "${it.codecW}x${it.codecH}" })
        pushSensors()
        say("Android Auto connected")
        // Started at boot, nobody has opened the screen yet: try to bring it up for the video.
        if (openScreenOnConnect) runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "could not open the screen by itself: $it") }
    }

    private fun endSession() {
        mic.open(false)  // joins the mic thread, so it can't write into a freed session
        val h = handle
        handle = 0
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

    /** Saves a log file on the phone (Phone Sync folder), over the hotspot. [auto]: after a failure, at most every 2 min. */
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
                    carIp()?.let { s.bind(InetSocketAddress(it, 0)) }
                    s.connect(InetSocketAddress(ip, Wireless.PHONE_LOG_REQUEST_PORT), 3000)
                    s.keepAlive = true
                    val input = s.getInputStream()
                    while (running) {
                        val b = input.read()
                        if (b < 0) break
                        if (b == 'L'.code) sendLogs("phone")
                    }
                }
            } catch (_: IOException) { /* phone app gone or older; try again shortly */ }
            Thread.sleep(5000)
        }
    }

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
                "Truck: ${truckText ?: "nothing shared"}",
                "Route: ${ClusterNav.route}, cluster ${if (ClusterNav.sink != null) "bound" else "not bound"}",
                "Status: " + synchronized(this) { recent.joinToString(" | ") },
                "---- truck data (every value the truck lets AutoBridge read) ----",
            ) + (runCatching { TruckProbe.dump(car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager) }.getOrElse { listOf("no car service: $it") })
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
            main.post {
                if (id == sessionId) {
                    endSession()
                    say("Disconnected: $reason")
                    if (reason != "phone disconnected") {
                        // H.265 never produced a picture: use the proven 1080p H.264 from the next connection on.
                        if (plan.hevc && firstFrame) {
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
            ux.registerListener { r -> moving = r.isRequiresDistractionOptimization; pushSensors() }
            watchTruck(car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager)
        }.onFailure { Log.w(TAG, "no car service; reporting parked", it) }
    }

    /** Battery, range, speed, gear and parking brake. Whatever the truck refuses simply stays unknown. */
    private fun watchTruck(props: CarPropertyManager) {
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                if (v.status != CarPropertyValue.STATUS_AVAILABLE) return
                val x = v.value
                truck = when (v.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> truck.copy(speedMs = x as? Float)
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
                    VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> truck.copy(outsideC = x as? Float)
                    else -> truck
                }
                if (v.propertyId in AA_PROPS) pushSensors()
                truckText = truck.line(miles = truck.miles ?: (java.util.Locale.getDefault().country in setOf("US", "GB")))
                latestTruck = truck
                val t = truck
                main.post { onTruck?.invoke(t) }
            }
            override fun onErrorEvent(propertyId: Int, zone: Int) {}
        }
        // The truck's own units (miles, °F); read once, they only change in the truck's settings.
        runCatching { truck = truck.copy(miles = props.getProperty<Int>(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, 0).value == 0x24) }
        runCatching { truck = truck.copy(fahrenheit = props.getProperty<Int>(VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, 0).value == 0x31) }
        for (id in AA_PROPS + listOf(VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, VehiclePropertyIds.EV_CHARGE_STATE,
            VehiclePropertyIds.EV_CHARGE_TIME_REMAINING, VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT, VehiclePropertyIds.EV_CHARGE_CURRENT_DRAW_LIMIT,
            VehiclePropertyIds.EV_CHARGE_PORT_OPEN, VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED, VehiclePropertyIds.EV_REGENERATIVE_BRAKING_STATE,
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE)) {
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
class Settings(context: Context) {
    private val p = context.getSharedPreferences("autobridge", Context.MODE_PRIVATE)
    var ssid: String get() = p.getString("ssid", "")!!; set(v) = p.edit().putString("ssid", v.trim()).apply()
    var password: String get() = p.getString("password", "")!!; set(v) = p.edit().putString("password", v).apply()
    var bssid: String get() = p.getString("bssid", "")!!; set(v) = p.edit().putString("bssid", v.trim()).apply()
    var viewW: Int get() = p.getInt("viewW", 0); set(v) = p.edit().putInt("viewW", v).apply()
    var viewH: Int get() = p.getInt("viewH", 0); set(v) = p.edit().putInt("viewH", v).apply()
    /** H.265 failed to start in this app version; each update tries it again. */
    var hevcFailed: Boolean
        get() = p.getLong("hevcFailedIn", -1) == version
        set(v) = p.edit().putLong("hevcFailedIn", if (v) version else -1).apply()
    private val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** The truck bar under Android Auto (settings page). */
    // commit(), not apply(): changing these restarts the process right after (SettingsPanel), which would lose a pending write.
    var barEnabled: Boolean get() = p.getBoolean("bar", true); set(v) { p.edit().putBoolean("bar", v).commit() }
    /** The truck's own status bar at the top (settings page); off gives Android Auto that space too. */
    var statusBarShown: Boolean get() = p.getBoolean("statusBar", true); set(v) { p.edit().putBoolean("statusBar", v).commit() }
    /** Offer the sharper H.265 1440p picture (settings page); off forces the proven 1080p. */
    var hevcEnabled: Boolean get() = p.getBoolean("hevc", true); set(v) { p.edit().putBoolean("hevc", v).commit() }

    fun forget() = p.edit().remove("ssid").remove("password").remove("bssid").apply()

    fun wifi() = if (ssid.isEmpty() || password.isEmpty() || bssid.isEmpty()) null else Wireless.Wifi(ssid, password, bssid)
}

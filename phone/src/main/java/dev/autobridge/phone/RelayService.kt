package dev.autobridge.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The car's Wi-Fi hotspot blocks connections from the phone to the car, so Android Auto can't reach
 * the car app directly. Instead the car tells Android Auto to connect to this relay on the phone
 * itself (127.0.0.1:AA_PORT), the car connects out to us on CAR_PORT, and we join the two sockets.
 */
class RelayService : Service() {
    companion object {
        const val AA_PORT = 5280   // must match Wireless.PHONE_AA_PORT in the car app
        const val CAR_PORT = 5300  // must match Wireless.PHONE_CAR_PORT in the car app
        const val LOG_PORT = 5301  // must match Wireless.PHONE_LOG_PORT in the car app
        const val LOG_REQUEST_PORT = 5302  // must match Wireless.PHONE_LOG_REQUEST_PORT in the car app
        const val SETUP_PORT = 5303  // must match Wireless.PHONE_SETUP_PORT in the car app
        /** The status line when a session starts. */
        const val RUNNING = "Android Auto is running through the truck"
        /** True while Android Auto runs through the relay (the app's headline; a status line can scroll out of the history). */
        @Volatile var aaRunning = false
        @Volatile private var carLine: Socket? = null

        /** Asks the truck for its log over the line the truck keeps open; false if the truck isn't connected. */
        fun requestLog() = ask('L')
        /** Asks the truck for a screenshot of AutoBridge's screen; it arrives like a log. */
        fun requestScreenshot() = ask('S')

        private fun ask(what: Char): Boolean {
            val s = carLine ?: return false
            return try { s.getOutputStream().apply { write(what.code); flush() }; true }
                   catch (_: IOException) { runCatching { s.close() }; carLine = null; false }
        }
        @Volatile var status = "Starting…"
        /** The same steps with when they happened (epoch ms), for the app's "Last hour" list. */
        @Volatile var events: List<Pair<Long, String>> = emptyList()
        var onStatus: ((String) -> Unit)? = null

        fun start(context: Context) = context.startForegroundService(Intent(context, RelayService::class.java))
    }

    private val lock = Any()
    private var waitingAa: Socket? = null
    private var waitingCar: Socket? = null
    @Volatile private var running = true
    private val servers = mutableListOf<ServerSocket>()
    /** The address the truck's session came from; only it may send logs or hold the log-request line. */
    @Volatile private var carAddress: InetAddress? = null
    /** Counts sessions, so an old pipe ending late can't mark a newer session stopped. */
    private var sessions = 0

    private fun fromCar(s: Socket): Boolean {
        val car = carAddress
        if (s.inetAddress == car) return true
        // No session yet (the relay just restarted): the truck retries every few seconds, so stay quiet until it connects.
        if (car != null) say("Ignored a connection from ${s.inetAddress?.hostAddress}: not the truck")
        runCatching { s.close() }
        return false
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("relay", "AutoBridge relay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "relay").setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("AutoBridge relay is ready").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        // Must be IPv4 127.0.0.1: the address the car hands Android Auto. getLoopbackAddress() is ::1 on Android.
        listen("Android Auto", "Android Auto on this phone is ready", InetSocketAddress(InetAddress.getByName("127.0.0.1"), AA_PORT)) { offer(aa = it) }
        listen("car", "", InetSocketAddress(CAR_PORT)) { s -> thread { pair(s) } }
        // Logs and the log-request line only from the truck we're relaying for, one upload at a time.
        listen("car log", "The truck is sending a file", InetSocketAddress(LOG_PORT)) { s -> if (fromCar(s)) thread { saveLog(s) } }
        listen("new truck", "", InetSocketAddress(SETUP_PORT)) { s -> thread { sendSetup(s) } }
        listen("car log line", "The truck is ready to send logs", InetSocketAddress(LOG_REQUEST_PORT)) { s -> if (fromCar(s)) { carLine?.let { runCatching { it.close() } }; carLine = s } }
        say("Ready. Waiting for the truck.")
    }

    override fun onDestroy() {
        running = false
        synchronized(servers) { servers.toList() }.forEach { runCatching { it.close() } }
        super.onDestroy()
    }

    /** [arrived] is what the status screen says when this side connects, in plain words. */
    private fun listen(who: String, arrived: String, address: InetSocketAddress, onSocket: (Socket) -> Unit) = thread(name = "relay-$who") {
        while (running) {
            try {
                ServerSocket().use { server ->
                    synchronized(servers) { servers += server }
                    try {
                    server.reuseAddress = true
                    server.bind(address)
                    while (running) {
                        val s = server.accept().apply { tcpNoDelay = true }
                        if (arrived.isNotEmpty()) say(arrived)
                        onSocket(s)
                    }
                    } finally { synchronized(servers) { servers -= server } }  // a failed bind retries every 2 s
                }
            } catch (e: IOException) {
                if (running) { say("Relay error ($who): ${e.message}"); Thread.sleep(2000) }
            }
        }
    }

    /** Holds whichever side arrives first; a newer socket replaces an older waiting one (car sockets are paired first). */
    private fun offer(aa: Socket? = null, car: Socket? = null) {
        val pair = synchronized(lock) {
            aa?.let { waitingAa?.close(); waitingAa = it }
            car?.let { waitingCar?.close(); waitingCar = it }
            val a = waitingAa ?: return
            val c = waitingCar ?: return
            waitingAa = null
            waitingCar = null
            carAddress = c.inetAddress  // set only once Android Auto really runs through it: a stray connect can't claim it
            aaRunning = true
            Triple(a, c, ++sessions)
        }
        say(RUNNING)
        pipe(pair.first, pair.second, pair.third)
    }

    /** Only a truck that proves it knows a saved hotspot password reaches Android Auto. */
    private fun pair(s: Socket) {
        val passwords = WifiDetails.savedPasswords(this)
        val ok = passwords.isNotEmpty() && try {
            s.soTimeout = 5000
            Pairing.phone(s, passwords) != null
        } catch (_: IOException) { false }
        if (!ok) {
            runCatching { s.close() }
            return say(if (passwords.isEmpty()) "The truck connected, but no hotspot password is saved here: tap Password"
                       else "Ignored a connection from ${s.inetAddress?.hostAddress}: it doesn't know a saved hotspot password")
        }
        s.soTimeout = 0
        say("The truck connected")
        offer(car = s)
    }

    /** A truck without Wi-Fi details asks for the hotspot we're on; only answered once its password is saved here. */
    private fun sendSetup(s: Socket) = s.use {
        val located = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val now = runCatching { WifiDetails.current(getSystemService(android.net.wifi.WifiManager::class.java)) }.getOrNull()
        val d = WifiDetails.pick(now, WifiDetails.remembered(this))
        val (message, note) = WifiDetails.setupReply(located || d != null, d, d?.let { WifiDetails.password(this, it.ssid) })
        runCatching { it.getOutputStream().write(message.toByteArray()) }  // the truck shows the error line to the owner
        say(note)
    }

    private fun saveLog(s: Socket) = s.use {
        try {
            it.soTimeout = 15_000
            val name = LogSaver.receive(this, it.getInputStream())
            it.getOutputStream().write('K'.code)  // tells the car it's saved
            say("Saved the car's log: $name")
        } catch (e: IOException) {
            say("Couldn't save the car's log: ${e.message}")
        }
    }

    private fun pipe(a: Socket, b: Socket, session: Int) {
        fun copy(from: Socket, to: Socket) = thread {
            try { from.getInputStream().copyTo(to.getOutputStream(), 64 * 1024) } catch (_: IOException) {}
            runCatching { a.close() }
            runCatching { b.close() }
            synchronized(lock) { if (session == sessions) aaRunning = false }
            say("Android Auto stopped. Waiting for the truck.")
        }
        copy(a, b)
        copy(b, a)
    }

    private val recent = ArrayDeque<String>()
    private val recentAt = ArrayDeque<Long>()

    /** Keeps the last few steps on screen, so a screenshot shows which side never arrived. */
    @Synchronized private fun say(text: String) {
        Log.i("AutoBridgePhone", text)
        if (recent.lastOrNull()?.endsWith("  $text") == true) return  // both pipe directions report the same end
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        recent.addLast("$time  $text"); recentAt.addLast(System.currentTimeMillis())
        if (recent.size > 20) { recent.removeFirst(); recentAt.removeFirst() }  // the app shows 3, and the last hour's 10 on tap
        events = recentAt.zip(recent)
        status = recent.joinToString("\n")
        onStatus?.invoke(status)
    }
}

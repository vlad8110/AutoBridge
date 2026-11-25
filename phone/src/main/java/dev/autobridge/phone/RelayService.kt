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
        var onStatus: ((String) -> Unit)? = null

        fun start(context: Context) = context.startForegroundService(Intent(context, RelayService::class.java))
    }

    private val lock = Any()
    private var waitingAa: Socket? = null
    private var waitingCar: Socket? = null
    @Volatile private var running = true
    private val servers = mutableListOf<ServerSocket>()

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
        listen("Android Auto", InetSocketAddress(InetAddress.getByName("127.0.0.1"), AA_PORT)) { offer(aa = it) }
        listen("car", InetSocketAddress(CAR_PORT)) { offer(car = it) }
        listen("car log", InetSocketAddress(LOG_PORT)) { s -> thread { saveLog(s) } }
        listen("new truck", InetSocketAddress(SETUP_PORT)) { s -> thread { sendSetup(s) } }
        listen("car log line", InetSocketAddress(LOG_REQUEST_PORT)) { s -> carLine?.let { runCatching { it.close() } }; carLine = s }
        say("Ready. Waiting for the car.")
    }

    override fun onDestroy() {
        running = false
        servers.forEach { runCatching { it.close() } }
        super.onDestroy()
    }

    private fun listen(who: String, address: InetSocketAddress, onSocket: (Socket) -> Unit) = thread(name = "relay-$who") {
        while (running) {
            try {
                ServerSocket().use { server ->
                    synchronized(servers) { servers += server }
                    server.reuseAddress = true
                    server.bind(address)
                    while (running) {
                        val s = server.accept().apply { tcpNoDelay = true }
                        say("$who connected")
                        onSocket(s)
                    }
                }
            } catch (e: IOException) {
                if (running) { say("Relay error ($who): ${e.message}"); Thread.sleep(2000) }
            }
        }
    }

    /** Holds whichever side arrives first; a newer socket replaces an older waiting one. */
    private fun offer(aa: Socket? = null, car: Socket? = null) {
        val pair = synchronized(lock) {
            aa?.let { waitingAa?.close(); waitingAa = it }
            car?.let { waitingCar?.close(); waitingCar = it }
            val a = waitingAa ?: return
            val c = waitingCar ?: return
            waitingAa = null
            waitingCar = null
            a to c
        }
        say("Android Auto is running through the car")
        pipe(pair.first, pair.second)
    }

    /** A truck without Wi-Fi details asks for the hotspot we're on; only answered once its password is saved here. */
    private fun sendSetup(s: Socket) = s.use {
        val d = runCatching { WifiDetails.current(getSystemService(android.net.wifi.WifiManager::class.java)) }.getOrNull()
        val pw = d?.let { WifiDetails.password(this, it.ssid) }
        if (d == null || pw == null) return@use say("A new truck asked for its Wi-Fi details; set the hotspot password in this app")
        runCatching { it.getOutputStream().write(d.setupMessage(pw).toByteArray()) }
        say("Sent ${d.ssid} details to the truck")
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

    private fun pipe(a: Socket, b: Socket) {
        fun copy(from: Socket, to: Socket) = thread {
            try { from.getInputStream().copyTo(to.getOutputStream(), 64 * 1024) } catch (_: IOException) {}
            runCatching { a.close() }
            runCatching { b.close() }
            say("Session ended. Waiting for the car.")
        }
        copy(a, b)
        copy(b, a)
    }

    private val recent = ArrayDeque<String>()

    /** Keeps the last few steps on screen, so a screenshot shows which side never arrived. */
    @Synchronized private fun say(text: String) {
        Log.i("AutoBridgePhone", text)
        if (recent.lastOrNull()?.endsWith("  $text") == true) return  // both pipe directions report the same end
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        recent.addLast("$time  $text")
        if (recent.size > 8) recent.removeFirst()
        status = recent.joinToString("\n")
        onStatus?.invoke(status)
    }
}

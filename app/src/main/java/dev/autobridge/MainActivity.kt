package dev.autobridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen Android Auto projection, with a status panel while no phone is connected. */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var truckBar: TextView
    private var statusText = ""
    private var cursorOn = true
    private val blink = object : Runnable {
        override fun run() { cursorOn = !cursorOn; status.text = Console.render(statusText, cursorOn); status.postDelayed(this, 500) }
    }

    private fun show(text: String) { statusText = text; status.text = Console.render(text, cursorOn) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = SurfaceView(this).apply { holder.addCallback(this@MainActivity) }
        // The waiting screen looks like a Linux console: monospace, coloured status tags, blinking cursor.
        status = TextView(this).apply { textSize = 20f; typeface = Typeface.MONOSPACE; setLineSpacing(8f, 1f) }
        val settings = Button(this).apply { text = "Wi-Fi settings"; textSize = 18f; typeface = Typeface.MONOSPACE; setOnClickListener { editWifi() } }
        val logs = Button(this).apply {
            text = "Send logs to phone"; textSize = 18f; typeface = Typeface.MONOSPACE
            setOnClickListener { BridgeService.instance?.sendLogs("button") ?: show("AutoBridge isn't running yet") }
        }
        // The truck's battery, range, gear and brake, big enough to read at a glance.
        // Frosted-glass card under the buttons, in the truck's clean sans-serif style.
        truckBar = TextView(this).apply {
            textSize = 30f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(0xFFF2F4F8.toInt())
            letterSpacing = 0.03f; gravity = Gravity.CENTER_VERTICAL; setPadding(40, 22, 40, 22); visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33FFFFFF, 0x14FFFFFF),  // light at the top, like glass catching light
            ).apply { cornerRadius = 28f; setStroke(2, 0x40FFFFFF) }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Console.BACKGROUND)
            setPadding(64, 48, 64, 48)
            addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(context).apply {
                addView(settings); addView(logs)
                VoiceAssistant.picker(context)?.let { picker ->
                    addView(Button(context).apply {
                        text = "Voice button"; textSize = 18f; typeface = Typeface.MONOSPACE
                        setOnClickListener { show(VoiceAssistant.status(context)); runCatching { startActivity(picker) } }
                    })
                }
            }, LinearLayout.LayoutParams(-2, -2))
            addView(truckBar, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24 })
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(panel, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                Settings(context).apply { viewW = r - l; viewH = b - t }  // used for the next session
                fitSurface()
            }
        }
        setContentView(root)
        root.setOnTouchListener(::onTouch)
        askPermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        BridgeService.onStatus = { show(it) }
        BridgeService.onTruck = ::showTruck
        showTruck(BridgeService.truckText)
        BridgeService.instance?.fullForeground()
        ClusterNav.resumed(this)
        BridgeService.onVideoActive = { active -> panel.visibility = if (active) View.GONE else View.VISIBLE; fitSurface() }
        show(BridgeService.status)
        if (Settings(this).wifi() == null) show("First, enter your car's Wi-Fi hotspot details.")
        panel.visibility = if (BridgeService.instance?.video != null) View.GONE else View.VISIBLE
        status.postDelayed(blink, 500)
    }

    override fun onPause() {
        status.removeCallbacks(blink)
        BridgeService.onStatus = null
        BridgeService.onTruck = null
        ClusterNav.paused(this)
        BridgeService.onVideoActive = null
        super.onPause()
    }

    private fun showTruck(text: String?) {
        truckBar.text = text
        truckBar.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- video surface ----

    override fun surfaceCreated(holder: SurfaceHolder) { BridgeService.setSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { BridgeService.setSurface(null) }

    /** Make the surface bigger than the screen by the margin amount so the margins fall off the edges. */
    private fun fitSurface() {
        val v = BridgeService.instance?.video ?: return
        val w = root.width * v.codecW / v.contentW
        val h = root.height * v.codecH / v.contentH
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) surface.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")  // projection touch is forwarded to the phone, not a click
    private fun onTouch(view: View, e: MotionEvent): Boolean {
        val service = BridgeService.instance ?: return false
        val v = service.video ?: return false
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> e.actionMasked  // same values as Android Auto
            else -> return true
        }
        val n = e.pointerCount
        val ids = IntArray(n) { e.getPointerId(it) }
        val xs = IntArray(n)
        val ys = IntArray(n)
        for (i in 0 until n) {
            val (x, y) = v.toTouch(e.getX(i), e.getY(i), view.width, view.height)
            xs[i] = x; ys[i] = y
        }
        service.touch(action, e.actionIndex, ids, xs, ys)
        return true
    }

    // ---- setup ----

    private fun editWifi() {
        val s = Settings(this)
        fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); textSize = 20f
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val ssid = field("Hotspot name (SSID)", s.ssid)
        val pass = field("Hotspot password", s.password, password = true)
        val bssid = field("Hotspot BSSID, e.g. 02:1a:2b:3c:4d:5e", s.bssid)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(ssid); addView(pass); addView(bssid)
        }
        AlertDialog.Builder(this).setTitle("Car Wi-Fi hotspot").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val mac = bssid.text.toString().trim()
                if (!Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}").matches(mac)) {
                    show("BSSID must look like 02:1a:2b:3c:4d:5e"); return@setPositiveButton
                }
                s.ssid = ssid.text.toString(); s.password = pass.text.toString(); s.bssid = mac
                show("Saved. Waiting for phone…")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askPermissionsThenStart() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(android.car.Car.PERMISSION_SPEED)   // truck speed and battery, shown here and passed to Android Auto
            add(android.car.Car.PERMISSION_ENERGY)
            add(Manifest.permission.ACCESS_FINE_LOCATION)  // the truck's GPS, passed to Android Auto
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) startService() else requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val bt = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (bt) startService() else show("AutoBridge needs the Nearby devices (Bluetooth) permission.")
    }

    private fun startService() {
        if (BridgeService.instance == null) startForegroundService(Intent(this, BridgeService::class.java))
    }
}

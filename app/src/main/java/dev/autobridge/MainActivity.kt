package dev.autobridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = SurfaceView(this).apply { holder.addCallback(this@MainActivity) }
        status = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val settings = Button(this).apply { text = "Wi-Fi settings"; textSize = 20f; setOnClickListener { editWifi() } }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(TextView(context).apply { text = "AutoBridge"; textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 32, 0, 32) })
            addView(settings)
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
        BridgeService.onStatus = { status.text = it }
        BridgeService.onVideoActive = { active -> panel.visibility = if (active) View.GONE else View.VISIBLE; fitSurface() }
        status.text = BridgeService.status
        if (Settings(this).wifi() == null) status.text = "First, enter your car's Wi-Fi hotspot details."
        panel.visibility = if (BridgeService.instance?.video != null) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        BridgeService.onStatus = null
        BridgeService.onVideoActive = null
        super.onPause()
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
                    status.text = "BSSID must look like 02:1a:2b:3c:4d:5e"; return@setPositiveButton
                }
                s.ssid = ssid.text.toString(); s.password = pass.text.toString(); s.bssid = mac
                status.text = "Saved. Waiting for phone…"
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askPermissionsThenStart() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) startService() else requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val bt = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (bt) startService() else status.text = "AutoBridge needs the Nearby devices (Bluetooth) permission."
    }

    private fun startService() {
        if (BridgeService.instance == null) startForegroundService(Intent(this, BridgeService::class.java))
    }
}

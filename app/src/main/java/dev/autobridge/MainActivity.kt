package dev.autobridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Full-screen Android Auto projection, with a status panel while no phone is connected. */
/** Request code for the full-screen capture consent. */
private const val CAPTURE = 7

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    /** Everything on screen: Android Auto, the truck bar and the settings pages on top. */
    private lateinit var screen: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var truckBar: TruckBar
    /** The see-through way into settings over Android Auto. */
    private lateinit var dot: SettingsDot
    private lateinit var settingsPanel: SettingsPanel
    private var statusText = ""
    private var cursorOn = true
    private val blink = object : Runnable {
        override fun run() {
            if (panel.visibility == View.VISIBLE) { cursorOn = !cursorOn; status.text = Console.render(statusText, cursorOn) }  // hidden under the video: skip
            status.postDelayed(this, 500)
        }
    }

    private fun show(text: String) { statusText = text; status.text = Console.render(text, cursorOn) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = SurfaceView(this).apply { holder.addCallback(this@MainActivity) }
        // The waiting screen looks like a Linux console: monospace, coloured status tags, blinking cursor.
        status = TextView(this).apply { textSize = 20f; typeface = Typeface.MONOSPACE; setLineSpacing(8f, 1f) }
        settingsPanel = SettingsPanel(this, editWifi = ::editWifi, toast = ::show, onShapeChanged = ::shapeChanged, onExit = ::exitApp)
        val settings = TextView(this).apply {
            text = "Settings"; textSize = 18f; setTextColor(WHITE); gravity = Gravity.CENTER; setPadding(56, 28, 56, 40)
            Lamp.style(this, resources.displayMetrics.widthPixels / 2012f, 18f)  // the 0.8 button: black, white text, red lamp
            setOnClickListener { settingsPanel.open() }
        }
        // The truck's data in a black strip under Android Auto (always shown).
        truckBar = TruckBar(this).apply { onSettings = { settingsPanel.open() } }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Console.BACKGROUND)
            setPadding(64, 48, 64, 48)
            addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(settings, LinearLayout.LayoutParams(-2, -2))
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(panel, FrameLayout.LayoutParams(-1, -1))
            dot = SettingsDot(context, resources.displayMetrics.widthPixels / 2012f, onOpen = { settingsPanel.open() }, onHold = { settingsPanel.openEq() })
            addView(dot)
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                if (settled()) Settings(context).apply { viewW = r - l; viewH = b - t }  // used for the next session
                fitSurface()
            }
        }
        // Android Auto gets the area above the bar; it re-measures from root's size at the next connection.
        screen = FrameLayout(this)
        setContentView(screen.apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(truckBar)
            })
            addView(settingsPanel, FrameLayout.LayoutParams(-1, -1))
        })
        root.setOnTouchListener(::onTouch)
        askPermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        BridgeService.onStatus = { show(it) }
        BridgeService.onTruck = { truckBar.show(it); settingsPanel.showTruck(it) }
        BridgeService.capture = ::screenshot
        BridgeService.onMoving = { moving -> if (moving) wifiDialog?.dismiss(); settingsPanel.movingChanged() }
        truckBar.show(BridgeService.latestTruck)
        BridgeService.instance?.fullForeground()
        ClusterNav.resumed(this)
        BridgeService.onVideoActive = { active -> panel.visibility = if (active) View.GONE else View.VISIBLE; if (!active) applyBar(); fitSurface(); settingsPanel.refresh(); showDot() }
        show(BridgeService.status)
        if (Settings(this).wifi() == null) show("Waiting for Wi-Fi details: open AutoBridge Phone on the truck hotspot, or set them in Wi-Fi settings.")
        panel.visibility = if (BridgeService.instance?.video != null) View.GONE else View.VISIBLE
        if (BridgeService.instance?.video == null) applyBar()
        showDot()
        applyStatusBar()
        fitSurface()  // a session may have started while this screen was paused (its callbacks were off)
        status.postDelayed(blink, 500)
        settingsPanel.shown(true)
        resumed = true
        root.requestLayout()  // measure again now that the bars are as set
    }

    override fun onPause() {
        resumed = false
        settingsPanel.shown(false)
        status.removeCallbacks(blink)
        BridgeService.onStatus = null
        BridgeService.onTruck = null
        BridgeService.capture = null
        BridgeService.onMoving = null
        ClusterNav.paused(this)
        BridgeService.onVideoActive = null
        super.onPause()
    }

    private var resumed = false

    /**
     * True when the screen is laid out the way the settings say: on screen, full window, status bar and truck bar as
     * set. Only then is root's size Android Auto's real area; in between (opening, the bar being hidden, a swipe
     * showing the status bar) it is a passing size that would give the phone the wrong layout.
     */
    private fun settled(): Boolean {
        if (!resumed || isInMultiWindowMode) return false
        val s = Settings(this)
        val statusShown = root.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.statusBars()) ?: return false
        return statusShown == s.statusBarShown && (truckBar.visibility == View.VISIBLE) == s.barEnabled
    }

    /** Shows or hides the truck's status bar per the setting (a swipe from the top still shows it for a moment). */
    private fun applyStatusBar() {
        val c = window.insetsController ?: return
        if (Settings(this).statusBarShown) c.show(android.view.WindowInsets.Type.statusBars())
        else {
            c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(android.view.WindowInsets.Type.statusBars())
        }
    }

    /** Shows or hides the bar per the setting; only between connections, so Android Auto's shape stays right. */
    private fun applyBar() { truckBar.visibility = if (Settings(this).barEnabled) View.VISIBLE else View.GONE; showDot() }

    /** Whenever Android Auto is on screen, whatever the bars: settings are one tap away without a restart. */
    private fun showDot() { dot.visibility = if (BridgeService.instance?.video != null) View.VISIBLE else View.GONE }

    /** A setting changed Android Auto's shape. It only measures the screen when it connects, so with a phone
     *  connected, close and reopen AutoBridge to reconnect at the new size; otherwise just apply it. */
    private fun shapeChanged() {
        if (BridgeService.instance?.video == null) { applyBar(); applyStatusBar(); return }
        Restart.now(this)
    }

    /** Force close in settings: stop the phone connection and close every AutoBridge screen (the cluster helper too). */
    private fun exitApp() {
        android.util.Log.i(TAG, "Force close: the owner tapped Force close in Settings")
        window.decorView.postDelayed({  // a moment for the log capture to save that line
            stopService(Intent(this, BridgeService::class.java))
            getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }, 300)
    }

    override fun onDestroy() { wifiDialog?.dismiss(); super.onDestroy() }  // no leaked window when the app closes

    @Deprecated("Back gesture; this app has no AndroidX")
    override fun onBackPressed() { if (!settingsPanel.close()) super.onBackPressed() }

    /**
     * The whole screen as a PNG: whatever is showing (Android Auto, the truck bar, settings, EQ). The video is
     * on its own layer that View.draw can't see; drawing the SurfaceView leaves its area transparent, so the
     * video is copied separately and laid in underneath, showing only where nothing covers it.
     */
    private fun screenshot(done: (ByteArray?) -> Unit) {
        // The whole truck screen when the owner has allowed it (asked once per run), else AutoBridge's own picture.
        if (ScreenCapture.ready) return ScreenCapture.grab { b -> if (b != null) png(b, done) else ownScreenshot(done) }
        // The consent box would pop up over the driver's screen: only ask in Park, and use AutoBridge's own picture meanwhile.
        if (ScreenCapture.declined || BridgeService.instance?.moving != false) return ownScreenshot(done)
        pendingShot = done
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent(), CAPTURE) }
            .onFailure { Log.w(TAG, "no screen capture on this truck", it); ScreenCapture.declined = true; pendingShot = null; ownScreenshot(done) }
    }

    private var pendingShot: ((ByteArray?) -> Unit)? = null

    @Deprecated("Activity result API; this app has no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE) return
        val done = pendingShot ?: return; pendingShot = null
        val service = BridgeService.instance
        if (resultCode != RESULT_OK || data == null || service == null) { ScreenCapture.declined = true; Log.i(TAG, "full-screen capture not allowed: AutoBridge-only screenshots"); return ownScreenshot(done) }
        service.projecting = true; service.fullForeground()  // Android 14: the service must be a mediaProjection one first
        if (!ScreenCapture.start(this, resultCode, data)) { service.projecting = false; service.fullForeground(); return ownScreenshot(done) }
        // Let the consent box leave the screen before the shot.
        window.decorView.postDelayed({ ScreenCapture.grab { b -> if (b != null) png(b, done) else ownScreenshot(done) } }, 700)
    }

    private fun png(b: Bitmap, done: (ByteArray?) -> Unit) =
        kotlin.concurrent.thread(name = "ab-shot-png") { done(ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()) }

    private fun ownScreenshot(done: (ByteArray?) -> Unit) {
        if (screen.width == 0) return done(null)
        val shot = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(shot)
        // The black behind Android Auto would hide the video laid in underneath; leave it out of this one drawing.
        val behind = listOf(root, root.parent as View)
        val saved = behind.map { it.background }
        behind.forEach { it.background = null }
        try { screen.draw(c) } finally { behind.zip(saved).forEach { (v, b) -> v.background = b } }
        fun finish(video: Bitmap?) {
            val under = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OVER) }
            video?.let {
                val at = IntArray(2).also(surface::getLocationInWindow); val origin = IntArray(2).also(screen::getLocationInWindow)
                c.drawBitmap(it, (at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat(), under)
            }
            c.drawColor(Color.BLACK, android.graphics.PorterDuff.Mode.DST_OVER)
            // A full-screen PNG takes a few hundred ms: not on the UI thread, where touches would stall.
            kotlin.concurrent.thread(name = "ab-shot-png") { done(ByteArrayOutputStream().also { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()) }
        }
        if (BridgeService.instance?.video == null || !surface.holder.surface.isValid || surface.width == 0) return finish(null)
        val video = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, video, { r -> finish(video.takeIf { r == PixelCopy.SUCCESS }) }, Handler(Looper.getMainLooper()))
    }

    // ---- video surface ----

    override fun surfaceCreated(holder: SurfaceHolder) { BridgeService.setSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { BridgeService.setSurface(null) }

    /** Make the surface bigger than the screen by the margin amount so the margins fall off the edges. */
    private fun fitSurface() {
        val v = BridgeService.instance?.video ?: return
        if (root.width == 0) return  // not laid out yet; the layout listener calls this again
        val w = root.width * v.codecW / v.contentW
        val h = root.height * v.codecH / v.contentH
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            surface.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            android.util.Log.i("AutoBridge", "picture fitted: ${w}x$h for ${root.width}x${root.height}")
        }
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

    private var wifiDialog: android.app.Dialog? = null

    /** The hotspot details, in big type so they can be checked while typing. Parked only. */
    private fun editWifi() {
        // Typing isn't allowed while driving; unknown driving state counts as driving.
        if (BridgeService.instance?.moving != false) return show("Park the truck to edit the hotspot details.")
        val s = Settings(this)
        val k = resources.displayMetrics.widthPixels / 2012f
        fun px(v: Int) = (v * k).roundToInt()
        fun field(value: String) = EditText(this).apply {
            setText(value); setTextSize(TypedValue.COMPLEX_UNIT_PX, 58 * k); setTextColor(WHITE)
            setSingleLine(); letterSpacing = 0.04f; backgroundTintList = android.content.res.ColorStateList.valueOf(OUTLINE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        fun label(t: String) = TruckDialog.text(this, k, 30f, MUTED).apply { text = t }
        val ssid = field(s.ssid)
        val pass = field(s.password).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val bssid = field(s.bssid)
        val hint = TruckDialog.text(this, k, 28f, MUTED)
        val tip = "Just the numbers and letters, no colons. The truck adds them for you."
        hint.text = tip
        // Editing any field clears a warning from the last Save.
        val clear = object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable) { if (hint.text != tip) { hint.text = tip; hint.setTextColor(MUTED) } }
        }
        listOf(ssid, pass, bssid).forEach { it.addTextChangedListener(clear) }
        // A colon goes in after every 2 characters, so only the 12 numbers and letters are typed.
        bssid.addTextChangedListener(object : android.text.TextWatcher {
            private var busy = false
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable) {
                if (busy) return
                val f = Wireless.colonize(e.toString())
                if (f == e.toString()) return
                // Keep the cursor after the same number of characters, so a fix mid-way doesn't jump to the end.
                val typed = e.subSequence(0, bssid.selectionEnd.coerceIn(0, e.length)).count { Wireless.colonize(it.toString()).isNotEmpty() }
                busy = true; e.replace(0, e.length, f); busy = false
                bssid.setSelection(Wireless.cursorAfter(typed).coerceAtMost(f.length))
            }
        })
        // The eye shows the password, to check it matches the truck's hotspot exactly.
        val eye = EyeView(this).apply {
            contentDescription = "Show password"; setPadding(px(30), px(22), px(30), px(34))
            Lamp.style(this, k, px(16).toFloat(), momentary = false)
            setOnClickListener {
                open = !open
                pass.inputType = InputType.TYPE_CLASS_TEXT or if (open) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                pass.setSelection(pass.text.length); contentDescription = if (open) "Hide password" else "Show password"; Lamp.lit(this, open)
            }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Hotspot name (SSID)"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(44) }); addView(ssid)
            addView(label("Hotspot password"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(36) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(pass, LinearLayout.LayoutParams(0, -2, 1f))
                addView(eye, LinearLayout.LayoutParams(px(130), px(100)).apply { marginStart = px(20) })
            })
            addView(label("Hotspot BSSID"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(36) }); addView(bssid)
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }
        fun problem(text: String): Boolean { hint.text = "⚠ $text"; hint.setTextColor(WHITE); return false }
        wifiDialog = TruckDialog.show(this, "Car Wi-Fi hotspot", "Type these exactly as the truck's hotspot settings show them.", form, ok = "Save") {
            val mac = bssid.text.toString().trim()
            when {
                ssid.text.isBlank() -> problem("Enter the hotspot name.")
                pass.text.length !in 8..63 -> problem("A hotspot password is 8 to 63 characters.")
                !Wireless.MAC.matches(mac) ->
                    problem("${12 - mac.count { it != ':' }} more to go: a BSSID has 12 numbers and letters.")
                else -> {
                    s.ssid = ssid.text.toString(); s.password = pass.text.toString(); s.bssid = mac
                    show("Saved. Waiting for phone…"); settingsPanel.refresh(); true
                }
            }
        }
    }

    /** An eye: open shows the password, crossed out hides it. */
    private class EyeView(c: android.content.Context) : View(c) {
        var open = false
            set(v) { field = v; invalidate() }
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = WHITE; strokeCap = android.graphics.Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            val l = paddingLeft.toFloat(); val t = paddingTop.toFloat(); val w = width - paddingLeft - paddingRight; val h = height - paddingTop - paddingBottom
            p.strokeWidth = w / 14f
            c.drawOval(l, t + h * 0.2f, l + w, t + h * 0.8f, p)
            c.drawCircle(l + w / 2f, t + h / 2f, h * 0.17f, p)
            if (!open) c.drawLine(l + w * 0.1f, t, l + w * 0.9f, t + h.toFloat(), p)
        }
    }

    private fun askPermissionsThenStart() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(android.car.Car.PERMISSION_SPEED)   // truck speed and battery, shown here and passed to Android Auto
            add(android.car.Car.PERMISSION_ENERGY)
            // Android 14 lets a driver grant these to ordinary apps: mileage, tire pressure, steering angle.
            addAll(listOf("android.car.permission.CAR_MILEAGE_3P", "android.car.permission.CAR_TIRES_3P", "android.car.permission.READ_CAR_STEERING_3P"))
            add(Manifest.permission.ACCESS_FINE_LOCATION)  // the truck's GPS, passed to Android Auto
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            // One this car's Android doesn't have can never be granted: asking would open the dialog at every start.
            .filter { runCatching { packageManager.getPermissionInfo(it, 0) }.isSuccess }
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

/** Kills AutoBridge's process and opens it again (runs in its own ":restart" process). */
class Restart : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.os.Process.killProcess(intent.getIntExtra(PID, 0))
        // Drop the old screens too (the cluster's CarAppActivity task would otherwise come back on top).
        getSystemService(android.app.ActivityManager::class.java).appTasks
            .filter { it.taskInfo.taskId != taskId }.forEach { runCatching { it.finishAndRemoveTask() } }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val PID = "pid"
        fun now(a: Activity) {
            a.startActivity(Intent(a, Restart::class.java).putExtra(PID, android.os.Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            a.finish()
            Runtime.getRuntime().exit(0)
        }
    }
}

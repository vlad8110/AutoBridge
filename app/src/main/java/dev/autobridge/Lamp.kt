package dev.autobridge

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The truck's colours, shared by every AutoBridge screen. */
internal const val BLACK = 0xFF000000.toInt()
internal const val WHITE = 0xFFF1F3F4.toInt()
internal const val LIGHT = 0xFFBDC1C6.toInt()
internal const val MUTED = 0xFF9AA0A6.toInt()
internal const val OUTLINE = 0xFF8E939B.toInt()  // the truck's button outline grey

/** GM's button shape: square, top-right corner cut on a diagonal, thin outline. */
internal class Chamfer(private val fill: Int, private val stroke: Int, private val cut: Float) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds; val w = 2f; val h = w / 2
        val path = android.graphics.Path().apply {
            moveTo(b.left + h, b.top + h); lineTo(b.right - cut, b.top + h); lineTo(b.right - h, b.top + cut)
            lineTo(b.right - h, b.bottom - h); lineTo(b.left + h, b.bottom - h); close()
        }
        p.style = Paint.Style.FILL; p.color = fill; c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = stroke; c.drawPath(path, p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * The 0.8 button: black, white text, the truck's chamfered outline, and a red lamp strip a few pixels
 * inside the bottom edge. The lamp is lit while a switch or choice is on; for an action it lights while
 * pressed. Every button springs when pressed.
 */
class LampDrawable(private val cut: Float, private val k: Float) : Drawable() {
    var on = false
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
    private val lamp = RectF()

    private val shape = Chamfer(BLACK, OUTLINE, cut)

    override fun draw(c: Canvas) {
        val b = bounds
        shape.bounds = b; shape.draw(c)
        lamp.set(b.left + 12 * k, b.bottom - 18 * k, b.right - 12 * k, b.bottom - 10 * k)
        if (on) { glow.color = 0x99FF2A45.toInt(); c.drawRoundRect(lamp, 3 * k, 3 * k, glow) }
        p.style = Paint.Style.FILL; p.color = if (on) LAMP_ON else LAMP_OFF; c.drawRoundRect(lamp, 3 * k, 3 * k, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        const val LAMP_OFF = 0xFF2B0A0F.toInt()
        const val LAMP_ON = 0xFFFF2A45.toInt()
    }
}

object Lamp {
    /**
     * Gives [v] the lamp background and the spring. A [momentary] button (an action) lights only while held;
     * otherwise set [lit] from the setting it shows.
     */
    @SuppressLint("ClickableViewAccessibility")  // the listener only animates; clicks still go through
    fun style(v: View, k: Float, cut: Float, momentary: Boolean = true): LampDrawable {
        val d = LampDrawable(cut, k)
        v.background = d
        val off = Runnable { d.on = false }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel(); view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).setInterpolator(null).start()
                    if (momentary) { view.removeCallbacks(off); d.on = true }  // a quick second press keeps it lit
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(OvershootInterpolator(5f)).start()
                    if (momentary) view.postDelayed(off, 120)
                }
            }
            false
        }
        return d
    }

    /** Shows a switch's state on its lamp, and tells TalkBack. */
    fun lit(v: View, on: Boolean) {
        (v.background as? LampDrawable)?.on = on
        v.isSelected = on  // TalkBack says "selected" on every version
        if (android.os.Build.VERSION.SDK_INT >= 30) v.stateDescription = if (on) "On" else "Off"  // Android 11+ also says on/off
    }

    /** A text button in the 0.8 style. */
    fun button(c: Context, k: Float, label: String, size: Float = 30f, cut: Float = 20f, bold: Boolean = false, momentary: Boolean = true, onClick: () -> Unit) =
        TextView(c).apply {
            text = label; gravity = Gravity.CENTER; maxLines = 1; letterSpacing = 0.02f
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(WHITE)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            minWidth = (200 * k).roundToInt(); setPadding((34 * k).roundToInt(), 0, (34 * k).roundToInt(), (6 * k).roundToInt())
            style(this, k, cut * k, momentary)
            setOnClickListener { onClick() }
        }
}

/**
 * The truck-style dialog (black panel, cut corner, big type) used for the hotspot details and for
 * Forget. [onOk] returns true to close it, false to keep it open (for example to show an error).
 */
object TruckDialog {
    fun show(c: Context, title: String, message: String, body: View? = null, ok: String, width: Int = 1560, onOk: () -> Boolean): Dialog {
        val k = c.resources.displayMetrics.widthPixels / 2012f
        fun px(v: Int) = (v * k).roundToInt()
        val d = Dialog(c)
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(80), px(70), px(80), px(56))
            background = TruckBar.chamfer(BLACK, OUTLINE, px(40).toFloat())
            addView(text(c, k, 66f).apply { text = title })
            addView(text(c, k, 36f, MUTED).apply { text = message }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18) })
            body?.let { addView(it, LinearLayout.LayoutParams(-1, -2)) }
            addView(LinearLayout(c).apply {
                addView(Lamp.button(c, k, "Cancel", 36f) { d.dismiss() }, LinearLayout.LayoutParams(-2, px(120)))
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(Lamp.button(c, k, ok, 36f, bold = true) { if (onOk()) d.dismiss() }, LinearLayout.LayoutParams(-2, px(120)))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(52) })
        }
        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(px(width).coerceAtMost(c.resources.displayMetrics.widthPixels), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.8f)
        }
        d.show()
        return d
    }

    fun text(c: Context, k: Float, size: Float, color: Int = WHITE) = TextView(c).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
}

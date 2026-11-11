package com.github.vkay94.dtpv3.youtube.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.vkay94.dtpv3.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MEETUBE(seek pill): the double-tap seek count, "⏩ +10", a dark capsule that springs in, then
 * on every tap squishes, dashes its arrows ahead, rolls its number up and throws eight sparks.
 * Timings follow YTXTube's SeekBurstPill.
 */
class SeekBurstPill @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val icon = ImageView(context)
    private val text = TextView(context)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent: Int = TypedValue().let {
        if (context.theme.resolveAttribute(android.R.attr.colorAccent, it, true)) it.data else Color.WHITE
    }
    private var side = 1
    private var large = false
    private var tapAnimator: ValueAnimator? = null
    private var showAnimator: ValueAnimator? = null
    /** 0..1 since the last tap, for the sparks; 1 = none. */
    private var sparks = 1f
    private var squishX = 1f
    private var squishY = 1f
    private var appear = 1f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Glass: a smoky tint (keeps white text readable on bright video), a sheen that is
        // brightest at the top, and a thin light rim.
        val radius = 100 * density
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = radius; setColor(Color.argb(56, 0, 0, 0)) },
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(64, 255, 255, 255), Color.argb(18, 255, 255, 255))).apply {
                cornerRadius = radius
                setStroke(Math.max(1, (0.8f * density).toInt()), Color.argb(90, 255, 255, 255))
            },
        ))
        elevation = 0f
        icon.setColorFilter(Color.WHITE)
        text.setTextColor(Color.WHITE)
        text.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        text.includeFontPadding = false
        addView(icon)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = (8 * density).toInt()
        })
        setWillNotDraw(false)
        visibility = INVISIBLE
        size(false)
    }

    private fun size(large: Boolean) {
        this.large = large
        val iconPx = ((if (large) 20 else 16) * density).toInt()
        icon.layoutParams = LayoutParams(iconPx, iconPx)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (large) 16f else 13f)
        text.setShadowLayer(3 * density, 0f, 0.5f * density, Color.argb(90, 0, 0, 0))
        (text.layoutParams as? LayoutParams)?.marginStart = (6 * density).toInt()
        minimumHeight = ((if (large) 40 else 34) * density).toInt()
        setPadding(((if (large) 12 else 10) * density).toInt(), 0, ((if (large) 15 else 13) * density).toInt(), 0)
    }

    /** One tap: [first] = the burst starts (the pill springs in). [large] in fullscreen. */
    fun tap(forward: Boolean, seconds: Int, first: Boolean, large: Boolean) {
        side = if (forward) 1 else -1
        if (large != this.large) size(large)
        icon.setImageResource(if (forward) R.drawable.dtpv_ic_fast_forward else R.drawable.dtpv_ic_fast_rewind)
        text.text = (if (forward) "+" else "−") + seconds
        if (first || visibility != VISIBLE) {
            visibility = VISIBLE
            showAnimator?.cancel()
            showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                addUpdateListener {
                    val ms = it.animatedFraction * 600
                    alpha = EMPHASIZED.getInterpolation((ms / 140f).coerceAtMost(1f))
                    appear = 0.4f + 0.6f * spring(ms / 1000f)
                    applyScale()
                }
                start()
            }
        } else {
            // The new number rolls up from below.
            text.translationY = text.height * 0.5f
            text.alpha = 0f
            text.animate().translationY(0f).alpha(1f).setDuration(160).setInterpolator(EMPHASIZED).start()
        }
        tapAnimator?.cancel()
        tapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val ms = it.animatedFraction * 520
                squishX = keys(ms, 1.14f, 0.96f)
                squishY = keys(ms, 0.88f, 1.04f)
                // The arrows dash ahead 6dp and come back.
                val dash = if (ms < 115) ms / 115 else (1 - (ms - 115) / 265).coerceAtLeast(0f)
                icon.translationX = side * 6 * density * dash
                sparks = EMPHASIZED.getInterpolation(it.animatedFraction)
                applyScale()
                invalidate()
            }
            start()
        }
    }

    /** The burst is over: fades and shrinks away, then [end]. */
    fun hide(end: Runnable) {
        if (visibility != VISIBLE) {
            end.run()
            return
        }
        showAnimator?.cancel()
        val from = appear
        val fromAlpha = alpha
        showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = EMPHASIZED
            addUpdateListener {
                val f = it.animatedValue as Float
                alpha = fromAlpha * (1 - f)
                appear = from + (0.4f - from) * f
                applyScale()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    visibility = INVISIBLE
                    end.run()
                }
            })
            start()
        }
    }

    /** Back to hidden at once (the overlay was torn down). */
    fun reset() {
        showAnimator?.cancel()
        tapAnimator?.cancel()
        sparks = 1f
        visibility = INVISIBLE
    }

    private fun applyScale() {
        scaleX = appear * squishX
        scaleY = appear * squishY
    }

    override fun dispatchDraw(canvas: Canvas) {
        val t = sparks
        if (t < 1f) {
            // A light fizz: six small dots drift just past the rim and fade; the accent tints
            // two of them faintly. Close in, so the video around stays undisturbed.
            val cx = width / 2f
            val cy = height / 2f
            val reach = width / 2f + 7 * density
            val fade = (1f - t) * (1f - t)
            for (i in 0 until 6) {
                val a = (i / 6f + 1f / 12f) * 2f * PI.toFloat()
                val fx = cos(a) * width * 0.42f
                val fy = sin(a) * height * 0.38f
                val tx = cos(a) * reach
                val ty = sin(a) * (height / 2f + 7 * density)
                sparkPaint.color = if (i % 3 == 0) accent else Color.WHITE
                sparkPaint.alpha = (fade * 140).toInt()
                canvas.drawCircle(cx + fx + (tx - fx) * t, cy + fy + (ty - fy) * t,
                    1.8f * density * (1f - 0.5f * t), sparkPaint)
            }
        }
        super.dispatchDraw(canvas)
    }

    companion object {
        /** Material's emphasized easing. */
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** 1 -> [a] at 120 ms -> [b] at 270 ms -> 1 at 440 ms, straight lines between. */
        private fun keys(ms: Float, a: Float, b: Float): Float = when {
            ms < 120 -> 1 + (a - 1) * ms / 120
            ms < 270 -> a + (b - a) * (ms - 120) / 150
            ms < 440 -> b + (1 - b) * (ms - 270) / 170
            else -> 1f
        }

        /** A spring from 0 to 1, damping ratio 0.5 and stiffness 500 (as Compose's), at [t] seconds. */
        private fun spring(t: Float): Float {
            val w0 = sqrt(500f)
            val zeta = 0.5f
            val wd = w0 * sqrt(1 - zeta * zeta)
            return 1 - exp(-zeta * w0 * t) * (cos(wd * t) + zeta * w0 / wd * sin(wd * t))
        }
    }
}

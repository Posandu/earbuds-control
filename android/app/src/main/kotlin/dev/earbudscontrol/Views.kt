package dev.earbudscontrol

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

private fun View.dp(value: Float) = value * resources.displayMetrics.density

/** Circular battery ring. [level] is 0..100, or null when unknown. */
class RingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        color = context.getColor(R.color.ring_track)
    }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    fun setLevel(level: Int?) {
        val target = (level ?: 0).toFloat()
        progress.color = context.getColor(if (level != null && level <= 20) R.color.battery_low else R.color.battery_good)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Stroke scales with the ring so it reads the same at any size.
        val stroke = minOf(width, height) * 0.17f
        track.strokeWidth = stroke
        progress.strokeWidth = stroke
        val inset = stroke / 2
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, 0f, 360f, false, track)
        if (shown > 0f) canvas.drawArc(bounds, -90f, 360f * shown / 100f, false, progress)
    }
}

/** Pill-shaped listening level meter that changes colour as it gets louder. */
class LevelMeterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ring_track) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val evaluator = ArgbEvaluator()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    /** Meter spans 40..100 dB so everyday levels fill a useful part of the bar. */
    fun setLevel(db: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, db.toFloat()).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        if (shown <= 0f) return
        val fraction = ((shown - 40f) / 60f).coerceIn(0.04f, 1f)
        fillPaint.color = colorFor(shown)
        rect.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    private fun colorFor(db: Float): Int {
        val ok = context.getColor(R.color.level_ok)
        val warn = context.getColor(R.color.level_warn)
        val loud = context.getColor(R.color.level_loud)
        return when {
            db < 65f -> ok
            db < 75f -> evaluator.evaluate((db - 65f) / 10f, ok, warn) as Int
            db < 85f -> evaluator.evaluate((db - 75f) / 10f, warn, loud) as Int
            else -> loud
        }
    }
}

/**
 * One UI style chunky slider (like the quick panel brightness bar): a rounded track that
 * fills up to the chosen step, with a dot marking each step. Steps run [min]..[max].
 */
class PillSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var min = 1
    var max = 5
    var value = 3
        private set

    /** Called on every step change while dragging. */
    var onChanging: ((Int) -> Unit)? = null
    /** Called once the finger lifts, with the final value. */
    var onChanged: ((Int) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.slider_track) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = value.toFloat()
    private var animator: ValueAnimator? = null

    fun setValue(newValue: Int, animate: Boolean) {
        val v = newValue.coerceIn(min, max)
        value = v
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(shown, v.toFloat()).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            shown = v.toFloat()
            invalidate()
        }
    }

    /** x-centre of a step's dot; the fill ends one radius past it so the dot sits inside. */
    private fun stepX(step: Float): Float {
        val r = height / 2f
        return r + (width - 2 * r) * (step - min) / (max - min)
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, trackPaint)
        rect.set(0f, 0f, stepX(shown) + r, height.toFloat())
        canvas.drawRoundRect(rect, r, r, fillPaint)
        val dotR = dp(2.5f)
        for (step in min..max) {
            val filled = step <= shown + 0.01f
            dotPaint.color = if (filled) 0xCCFFFFFF.toInt() else context.getColor(R.color.slider_dot)
            canvas.drawCircle(stepX(step.toFloat()), r, if (step.toFloat() == shown) dotR * 1.6f else dotR, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val r = height / 2f
                val fraction = ((event.x - r) / (width - 2 * r)).coerceIn(0f, 1f)
                val step = Math.round(min + fraction * (max - min))
                if (step != value) {
                    setValue(step, animate = true)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onChanging?.invoke(step)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                onChanged?.invoke(value)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }
}

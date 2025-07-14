package com.example.fingerprint_identifier.ui.camera

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt

class BiometricOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OverlayStyle {
        SOLID, DASHED
    }

    // --- Configuration ---
    companion object {
        private const val RECT_HEIGHT_DP = 80f
        private const val SEMICIRCLE_RADIUS_DP = 85f
    }

    private var currentStyle = OverlayStyle.SOLID
    @ColorInt
    private var currentColor: Int = Color.WHITE // Default color

    private val path = Path()

    // --- Paint objects for each layer ---
    private val backgroundPaint = Paint()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var dashPhase = 0f

    private var animator: ValueAnimator? = null
    private var isAnimationOn = false

    init {

        // 1. Configure the background paint
        backgroundPaint.style = Paint.Style.FILL
        backgroundPaint.color = "#A6000000".toColorInt() // 65% transparent black

        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
        )

        // 3. Configure the paint for the transparent cutout
        cutoutPaint.style = Paint.Style.FILL
        cutoutPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

        // Set up the animator
        setupAnimator()
        setAnimationEnabled(enabled = true)
    }

    private fun setupAnimator() {
        animator = ValueAnimator.ofFloat(0f, 50f).apply {
            duration = 1000 // Animation speed: 1 second for a full cycle
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                dashPhase = it.animatedValue as Float
                invalidate() // Redraw the view with the new dash phase
            }
        }
    }

    fun setAnimationEnabled(enabled: Boolean) {
        if (enabled == isAnimationOn) return // No change needed

        isAnimationOn = enabled
        if (isAnimationOn && currentStyle == OverlayStyle.DASHED) {
            animator?.start()
        } else {
            animator?.cancel()
        }
        invalidate() // Redraw to apply the change immediately
    }

    fun setStyle(style: OverlayStyle) {

        if (currentStyle == style) return
        currentStyle = style

        if (style == OverlayStyle.DASHED && isAnimationOn) {
            animator?.start()
        } else {
            animator?.cancel()
        }
        invalidate()
    }

    fun setColor(@ColorInt color: Int) {
        if (currentColor == color) return
        currentColor = color
        invalidate() // Redraw the view with the new color
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // --- Corrected Path Drawing Logic ---
        path.reset()

        val centerX = width / 2f
        val centerY = height / 2f

        // Convert DP dimensions to pixels for accurate drawing
        val rectHalfHeightPx = dpToPx(RECT_HEIGHT_DP / 2)
        val radiusPx = dpToPx(SEMICIRCLE_RADIUS_DP) // Radius and half-width are the same

        // Define the bounding box for the top semicircle
        val topArcRect = RectF(
            centerX - radiusPx,
            centerY - rectHalfHeightPx - radiusPx,
            centerX + radiusPx,
            centerY - rectHalfHeightPx + radiusPx
        )

        // Define the bounding box for the bottom semicircle
        val bottomArcRect = RectF(
            centerX - radiusPx,
            centerY + rectHalfHeightPx - radiusPx,
            centerX + radiusPx,
            centerY + rectHalfHeightPx + radiusPx
        )

        // 1. Start with the top arc (sweeping 180 degrees from left to right)
        path.addArc(topArcRect, 180f, 180f)

        // 2. Draw the straight line down the right side
        path.lineTo(centerX + radiusPx, centerY + rectHalfHeightPx)

        // 3. Add the bottom arc (sweeping 180 degrees from right to left)
        path.addArc(bottomArcRect, 0f, 180f)

        // 4. Close the path, which draws the final straight line up the left side
        path.lineTo(centerX - radiusPx, centerY - rectHalfHeightPx)

        canvas.drawPath(path, cutoutPaint)

        borderPaint.color = currentColor
        // --- Style setup based on attributes ---
        when (currentStyle) {
            OverlayStyle.DASHED -> {
                // Dashed line effect: 30px line, 20px gap
                borderPaint.pathEffect =
                    if(animator?.isRunning == true) DashPathEffect(floatArrayOf(100f, 40f), dashPhase)
                    else DashPathEffect(floatArrayOf(100f, 40f), 0f)
            }
            OverlayStyle.SOLID -> {
                borderPaint.pathEffect = null
            }
        }

        canvas.drawPath(path, borderPaint)
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }
}
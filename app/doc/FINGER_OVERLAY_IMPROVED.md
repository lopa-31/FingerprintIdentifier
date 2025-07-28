Of course, I can help with that. Adding a progress indicator to your `BiometricOverlayView` that animates smoothly is a great idea for providing better user feedback during the validation state.

Here is the modified code for your `BiometricOverlayView.kt`. I've integrated the progress functionality, including a new `PROGRESS` state, an animation method for the percentage, and the necessary drawing logic.

### Key Changes:

1.  **`OverlayStyle` Enum:** A new `PROGRESS` style has been added to manage the progress-bar state.
2.  **`animateToProgress()` Method:** A new public method that allows you to animate the border to a specific percentage. It will animate from its current progress to the new target.
3.  **`onDraw()` Logic:** The `onDraw` method has been updated to render the progress. It does this by first drawing a dashed "track" and then drawing a solid line on top of it to show the progress, creating a filling effect.
4.  **State Management:** The existing methods (`setStyle`, `animateToStyle`, `cancelAllAnimations`) have been updated to be aware of and correctly handle the new `PROGRESS` state, ensuring smooth transitions and preventing animation conflicts.

Here is the updated code:

```kotlin
package `in`.gov.uidai.capture.ui.camera.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import `in`.gov.uidai.capture.R

class BiometricOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OverlayStyle {
        SOLID, DASHED, PROGRESS
    }

    // --- Configuration ---
    companion object {
        private const val RECT_HEIGHT_F = 80f
        private const val SEMICIRCLE_RADIUS_F = 85f
        private const val COLOR_ANIMATION_DURATION = 250L
        private const val STYLE_ANIMATION_DURATION = 200L
        private const val PROGRESS_ANIMATION_DURATION = 300L


        fun getCutoutRectSize(): Size {
            return Size(
                (2 * SEMICIRCLE_RADIUS_F).toInt(),
                (2 * SEMICIRCLE_RADIUS_F + RECT_HEIGHT_F).toInt()
            )
        }
    }

    private var currentStyle = OverlayStyle.SOLID
    @ColorInt
    private var currentColor: Int = Color.WHITE // Default color

    private val path = Path()

    // --- Paint objects for each layer ---
    private val backgroundPaint = Paint()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Animators and state variables ---
    private var dashPhase = 0f
    private var isAnimationOn = false
    private var animator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var styleAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null


    // For style transition animation
    private var dashLength = 100f
    private var dashGap = 40f

    // For progress state
    private var currentProgress = 0f // Range: 0.0f to 1.0f

    init {
        // 1. Configure the background paint
        backgroundPaint.style = Paint.Style.FILL
        backgroundPaint.color = ContextCompat.getColor(context, R.color.biometric_overlay_background) // 65% transparent black

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
        animator = ValueAnimator.ofFloat(0f, -700f).apply {
            duration = 5000 // Animation speed: 1 second for a full cycle
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

        // Reset progress if we're no longer in PROGRESS mode
        if (style != OverlayStyle.PROGRESS) {
            progressAnimator?.cancel()
            currentProgress = 0f
        }


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

    // --- Animation Methods ---

    /**
     * Smoothly animate color change
     */
    fun animateToColor(@ColorInt targetColor: Int, duration: Long = COLOR_ANIMATION_DURATION) {
        if (currentColor == targetColor) return

        // Cancel any existing color animation
        colorAnimator?.cancel()

        val startColor = currentColor
        colorAnimator = ValueAnimator.ofInt(startColor, targetColor).apply {
            this.duration = duration
            setEvaluator(ArgbEvaluator())
            addUpdateListener { animation ->
                currentColor = animation.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    /**
     * Smoothly animate style change
     */
    fun animateToStyle(targetStyle: OverlayStyle, duration: Long = STYLE_ANIMATION_DURATION) {
        if (currentStyle == targetStyle) return

        // Cancel any existing style animation
        styleAnimator?.cancel()
        // Cancel progress animation if we are changing style
        progressAnimator?.cancel()
        currentProgress = 0f


        when {
            (currentStyle == OverlayStyle.DASHED || currentStyle == OverlayStyle.PROGRESS) && targetStyle == OverlayStyle.SOLID -> {
                // Animate dash gap to 0 to create solid line effect
                styleAnimator = ValueAnimator.ofFloat(dashGap, 0f).apply {
                    this.duration = duration
                    addUpdateListener { animation ->
                        dashGap = animation.animatedValue as Float
                        invalidate()
                    }
                    doOnEnd {
                        currentStyle = targetStyle
                        dashGap = 40f // Reset for future animations
                        animator?.cancel() // Stop dashed animation
                        invalidate()
                    }
                    start()
                }
            }
            currentStyle == OverlayStyle.SOLID && targetStyle == OverlayStyle.DASHED -> {
                // Animate dash gap from 0 to full to create dashed line effect
                val startGap = 0f
                styleAnimator = ValueAnimator.ofFloat(startGap, 40f).apply {
                    this.duration = duration
                    addUpdateListener { animation ->
                        dashGap = animation.animatedValue as Float
                        invalidate()
                    }
                    doOnEnd {
                        currentStyle = targetStyle
                        if (isAnimationOn) {
                            animator?.start() // Start dashed animation
                        }
                        invalidate()
                    }
                    start()
                }
            }
        }
    }

    /**
     * Animate both color and style simultaneously
     */
    fun animateToState(@ColorInt targetColor: Int, targetStyle: OverlayStyle, duration: Long = COLOR_ANIMATION_DURATION) {
        animateToColor(targetColor, duration)
        animateToStyle(targetStyle, (duration * 0.8f).toLong()) // Slightly faster style change
    }

    /**
     * Animate the border to show progress.
     * @param targetProgress The target progress value (0.0 to 1.0).
     * @param duration The duration of the animation.
     */
    fun animateToProgress(targetProgress: Float, duration: Long = PROGRESS_ANIMATION_DURATION) {
        // If not already in a progress-compatible state, switch to it
        if (currentStyle != OverlayStyle.PROGRESS) {
            styleAnimator?.cancel()
            animator?.cancel() // Stop the dashed animation
            currentStyle = OverlayStyle.PROGRESS
        }

        progressAnimator?.cancel()

        val startProgress = currentProgress
        // Coerce progress to be within the 0..1 range
        val newProgress = targetProgress.coerceIn(0f, 1f)

        progressAnimator = ValueAnimator.ofFloat(startProgress, newProgress).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                currentProgress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }


    // Extension function for animation end callback
    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
                removeListener(this)
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                removeListener(this)
            }
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
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
        val rectHalfHeightPx = dpToPx(RECT_HEIGHT_F / 2)
        val radiusPx = dpToPx(SEMICIRCLE_RADIUS_F) // Radius and half-width are the same

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
                // Dashed line effect: use animated dash gap for smooth transitions
                borderPaint.pathEffect =
                    if(animator?.isRunning == true) DashPathEffect(floatArrayOf(dashLength, dashGap), dashPhase)
                    else DashPathEffect(floatArrayOf(dashLength, dashGap), 0f)
                canvas.drawPath(path, borderPaint)
            }
            OverlayStyle.SOLID -> {
                borderPaint.pathEffect = null
                canvas.drawPath(path, borderPaint)
            }
            OverlayStyle.PROGRESS -> {
                // In PROGRESS mode, draw a dashed track first
                borderPaint.pathEffect = DashPathEffect(floatArrayOf(dashLength, dashGap), 0f)
                canvas.drawPath(path, borderPaint)

                // Then, draw the solid progress line on top
                if (currentProgress > 0) {
                    borderPaint.pathEffect = null // Solid line for progress
                    val pathMeasure = PathMeasure(path, false)
                    val length = pathMeasure.length
                    val stop = length * currentProgress
                    val progressPath = Path()
                    // Get the segment of the path to draw
                    pathMeasure.getSegment(0f, stop, progressPath, true)
                    canvas.drawPath(progressPath, borderPaint)
                }
            }
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    /**
     * Get the cutout rectangle in this view's coordinate system.
     * This represents the bounding box of the stadium-shaped cutout.
     */
    fun getCutoutRect(): RectF {
        val centerX = width / 2f
        val centerY = height / 2f

        // Convert DP dimensions to pixels
        val rectHalfHeightPx = dpToPx(RECT_HEIGHT_F / 2)
        val radiusPx = dpToPx(SEMICIRCLE_RADIUS_F)

        // Return the bounding rectangle of the stadium shape
        val rect = RectF(
            centerX - radiusPx,
            centerY - rectHalfHeightPx - radiusPx,
            centerX + radiusPx,
            centerY + rectHalfHeightPx + radiusPx
        )

        Log.d("BiometricOverlayView", "getCutoutRect() called:")
        Log.d("BiometricOverlayView", "  View size: ${width}x${height}")
        Log.d("BiometricOverlayView", "  Center: ($centerX, $centerY)")
        Log.d("BiometricOverlayView", "  rectHalfHeightPx: $rectHalfHeightPx")
        Log.d("BiometricOverlayView", "  radiusPx: $radiusPx")
        Log.d("BiometricOverlayView", "  Cutout rect: $rect")

        return rect
    }

    /**
     * Cancel all running animations to prevent memory leaks
     */
    fun cancelAllAnimations() {
        animator?.cancel()
        colorAnimator?.cancel()
        styleAnimator?.cancel()
        progressAnimator?.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllAnimations()
    }
}
```
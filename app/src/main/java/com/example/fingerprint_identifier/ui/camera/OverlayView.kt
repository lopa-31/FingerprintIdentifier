package com.example.fingerprint_identifier.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.fingerprint_identifier.R

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OverlayMode {
        NONE, PALM_SQUARE, FINGER_OVAL
    }

    private var mode: OverlayMode = OverlayMode.NONE
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 160 // Semi-transparent
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 5f
    }

    fun setMode(newMode: OverlayMode) {
        mode = newMode
        postInvalidate() // Redraw the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw the semi-transparent background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        when (mode) {
            OverlayMode.PALM_SQUARE -> drawPalmSquare(canvas)
            OverlayMode.FINGER_OVAL -> drawFingerOval(canvas)
            OverlayMode.NONE -> { /* Do nothing */ }
        }
    }

    private fun drawPalmSquare(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val sideLength = width * 0.8f // 80% of the view's width
        val halfSide = sideLength / 2f

        val squareRect = RectF(centerX - halfSide, centerY - halfSide, centerX + halfSide, centerY + halfSide)

        canvas.drawRect(squareRect, clearPaint)
        canvas.drawRect(squareRect, strokePaint)
    }

    private fun drawFingerOval(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val ovalRect = RectF(centerX - 150, centerY - 250, centerX + 150, centerY + 250)
        
        canvas.drawOval(ovalRect, clearPaint)
        canvas.drawOval(ovalRect, strokePaint)
    }
}
 
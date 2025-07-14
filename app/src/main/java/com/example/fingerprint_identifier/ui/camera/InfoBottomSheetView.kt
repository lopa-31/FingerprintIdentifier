package com.example.fingerprint_identifier.ui.camera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.example.fingerprint_identifier.R

class InfoBottomSheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val sheetTitle: TextView
    private val sheetDescription: TextView
    private val sheetImage: ImageView

    init {
        // Inflate the layout and attach it to this view
        LayoutInflater.from(context).inflate(R.layout.view_info_bottom_sheet, this, true)

        // Get references to the child views
        sheetTitle = findViewById(R.id.sheet_title)
        sheetDescription = findViewById(R.id.sheet_description)
        sheetImage = findViewById(R.id.sheet_image)

        // Start hidden by default
        visibility = GONE
    }

    /**
     * Updates the content of the bottom sheet.
     *
     * @param title The text for the main title.
     * @param description The text for the description.
     * @param imageRes The drawable resource ID for the image.
     */
    fun updateContent(title: String, description: String, @DrawableRes imageRes: Int) {
        sheetTitle.text = title
        sheetDescription.text = description
        sheetImage.setImageResource(imageRes)
    }

    /**
     * Animates the view into visibility from the bottom of the screen.
     */
    fun show() {
        // Ensure we have the measured height before starting the animation
        post {

            translationY = height.toFloat() // Start off-screen at the bottom

            animate()
                .translationY(0f) // Animate to its original position
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter(){
                    override fun onAnimationStart(animation: Animator) {
                        super.onAnimationStart(animation)
                        visibility = VISIBLE
                    }
                })
                .start()
        }
    }

    /**
     * Animates the view out of visibility towards the bottom of the screen.
     */
    fun hide() {
        animate()
            .translationY(height.toFloat()) // Animate off-screen to the bottom
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    visibility = GONE // Hide the view after animation ends
                }
            })
            .start()
    }
}
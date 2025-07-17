package com.example.fingerprint_identifier.utils

import android.graphics.RectF
import android.media.Image
import android.util.Log
import android.view.TextureView
import android.view.View
import com.example.fingerprint_identifier.ui.camera.BiometricOverlayView

/**
 * Utility class for handling coordinate mapping and cropping calculations
 * between BiometricOverlayView, TextureView, and Camera2 Image coordinates.
 */
object CropUtils {
    
    private const val TAG = "CropUtils"
    
    /**
     * Calculate the crop rectangle in Image coordinates based on the BiometricOverlayView cutout.
     * 
     * @param overlayView The BiometricOverlayView containing the cutout
     * @param textureView The TextureView displaying the camera preview
     * @param image The Camera2 Image object
     * @return RectF representing the crop area in Image coordinates, or null if calculation fails
     */
    fun calculateCropRect(
        overlayView: BiometricOverlayView,
        textureView: TextureView,
        image: Image
    ): RectF? {
        Log.d(TAG, "=== Starting crop calculation ===")
        Log.d(TAG, "Image size: ${image.width}x${image.height}")
        Log.d(TAG, "TextureView size: ${textureView.width}x${textureView.height}")
        Log.d(TAG, "OverlayView size: ${overlayView.width}x${overlayView.height}")
        
        // Step 1: Get cutout rect in overlay's coordinate system
        val cutoutRect = overlayView.getCutoutRect()
        Log.d(TAG, "Cutout rect in overlay coords: $cutoutRect")
        
        // Step 2: Convert cutout rect to screen coordinates
        val screenCutoutRect = convertOverlayToScreenCoords(overlayView, cutoutRect)
        Log.d(TAG, "Cutout rect in screen coords: $screenCutoutRect")
        
        // Step 3: Convert TextureView bounds to screen coordinates
        val textureScreenRect = getTextureViewScreenRect(textureView)
        Log.d(TAG, "TextureView screen rect: $textureScreenRect")
        
        // Step 4: Calculate the actual preview area within TextureView (accounting for scaling/letterboxing)
        val previewRect = calculatePreviewRect(textureView, image, textureScreenRect)
        Log.d(TAG, "Preview rect in screen coords: $previewRect")
        
        // Step 5: Find intersection between cutout and preview area
        val intersectionRect = RectF()
        if (!intersectionRect.setIntersect(screenCutoutRect, previewRect)) {
            Log.w(TAG, "No intersection between cutout and preview area!")
            Log.w(TAG, "Cutout: $screenCutoutRect")
            Log.w(TAG, "Preview: $previewRect")
            return null // No intersection
        }
        Log.d(TAG, "Intersection rect: $intersectionRect")
        
        // Step 6: Convert intersection from screen coordinates to Image coordinates
        val imageRect = convertScreenToImageCoords(intersectionRect, previewRect, image)
        Log.d(TAG, "Final image rect: $imageRect")
        Log.d(TAG, "=== Crop calculation complete ===")
        
        return imageRect
    }
    
    /**
     * Convert a rectangle from overlay's local coordinates to screen coordinates.
     */
    private fun convertOverlayToScreenCoords(overlayView: BiometricOverlayView, rect: RectF): RectF {
        val location = IntArray(2)
        overlayView.getLocationOnScreen(location)
        
        Log.d(TAG, "Overlay location on screen: (${location[0]}, ${location[1]})")
        
        return RectF(
            rect.left + location[0],
            rect.top + location[1],
            rect.right + location[0],
            rect.bottom + location[1]
        )
    }
    
    /**
     * Get TextureView bounds in screen coordinates.
     */
    private fun getTextureViewScreenRect(textureView: TextureView): RectF {
        val location = IntArray(2)
        textureView.getLocationOnScreen(location)
        
        Log.d(TAG, "TextureView location on screen: (${location[0]}, ${location[1]})")
        
        return RectF(
            location[0].toFloat(),
            location[1].toFloat(),
            location[0] + textureView.width.toFloat(),
            location[1] + textureView.height.toFloat()
        )
    }
    
    /**
     * Calculate the actual preview area within TextureView, accounting for scaling and letterboxing.
     */
    private fun calculatePreviewRect(textureView: TextureView, image: Image, textureScreenRect: RectF): RectF {
        val textureWidth = textureView.width.toFloat()
        val textureHeight = textureView.height.toFloat()
        val imageWidth = image.width.toFloat()
        val imageHeight = image.height.toFloat()
        
        Log.d(TAG, "Texture dimensions: ${textureWidth}x${textureHeight}")
        Log.d(TAG, "Image dimensions: ${imageWidth}x${imageHeight}")
        
        // Calculate aspect ratios
        val imageAspect = imageWidth / imageHeight
        val textureAspect = textureWidth / textureHeight
        
        Log.d(TAG, "Image aspect ratio: $imageAspect")
        Log.d(TAG, "Texture aspect ratio: $textureAspect")
        
        val scale: Float
        var dx = 0f
        var dy = 0f
        
        if (imageAspect > textureAspect) {
            // Image is wider than texture, scale to fit height (letterboxing on sides)
            scale = textureHeight / imageHeight
            dx = (textureWidth - imageWidth * scale) / 2f
            Log.d(TAG, "Letterboxing sides - scale: $scale, dx: $dx")
        } else {
            // Image is taller than texture, scale to fit width (letterboxing on top/bottom)
            scale = textureWidth / imageWidth
            dy = (textureHeight - imageHeight * scale) / 2f
            Log.d(TAG, "Letterboxing top/bottom - scale: $scale, dy: $dy")
        }
        
        // Calculate the actual preview area in screen coordinates
        val previewRect = RectF(
            textureScreenRect.left + dx,
            textureScreenRect.top + dy,
            textureScreenRect.left + dx + imageWidth * scale,
            textureScreenRect.top + dy + imageHeight * scale
        )
        
        Log.d(TAG, "Preview area calculated: $previewRect")
        return previewRect
    }
    
    /**
     * Convert a rectangle from screen coordinates to Image coordinates.
     */
    private fun convertScreenToImageCoords(
        screenRect: RectF,
        previewRect: RectF,
        image: Image
    ): RectF {
        // Calculate the scale factor from preview to image
        val scaleX = image.width.toFloat() / previewRect.width()
        val scaleY = image.height.toFloat() / previewRect.height()
        
        Log.d(TAG, "Scale factors - X: $scaleX, Y: $scaleY")
        
        // Convert to preview-relative coordinates first
        val relativeRect = RectF(
            screenRect.left - previewRect.left,
            screenRect.top - previewRect.top,
            screenRect.right - previewRect.left,
            screenRect.bottom - previewRect.top
        )
        
        Log.d(TAG, "Relative rect: $relativeRect")
        
        // Scale to image coordinates
        val imageRect = RectF(
            relativeRect.left * scaleX,
            relativeRect.top * scaleY,
            relativeRect.right * scaleX,
            relativeRect.bottom * scaleY
        )
        
        Log.d(TAG, "Scaled to image coords: $imageRect")
        return imageRect
    }
    
    /**
     * Clamp a rectangle to fit within image boundaries.
     */
    fun clampRectToImage(rect: RectF, image: Image): RectF {
        val clampedRect = RectF(
            rect.left.coerceAtLeast(0f),
            rect.top.coerceAtLeast(0f),
            rect.right.coerceAtMost(image.width.toFloat()),
            rect.bottom.coerceAtMost(image.height.toFloat())
        )
        
        Log.d(TAG, "Clamped rect: $clampedRect")
        return clampedRect
    }
} 
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Converts an Image in YUV_420_888 format to a Bitmap.
 */
private fun Image.toBitmap(): Bitmap? {
    if (format != ImageFormat.YUV_420_888) {
        // This function only supports YUV_420_888, which is the default for Camera2.
        // For other formats like JPEG, you would need different logic.
        return null
    }

    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    //U and V are swapped
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 100, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}


/**
 * Crops an image from a Camera2 Image object based on screen coordinates.
 *
 * @param image The Image object from the camera.
 * @param previewWidth The width of the TextureView or SurfaceView displaying the preview.
 * @param previewHeight The height of the TextureView or SurfaceView displaying the preview.
 * @param sensorRotation The rotation of the camera sensor (usually from CameraCharacteristics.SENSOR_ORIENTATION).
 * @param deviceRotation The current rotation of the device (from Display.getRotation() converted to degrees).
 * @param cropCenterX The x-coordinate of the center of the crop rectangle on the screen.
 * @param cropCenterY The y-coordinate of the center of the crop rectangle on the screen.
 * @param cropWidth The desired width of the crop rectangle on the screen.
 * @param cropHeight The desired height of the crop rectangle on the screen.
 * @return A cropped and correctly rotated Bitmap, or null if cropping fails.
 */
fun cropImageFromCamera2(
    image: Image,
    previewWidth: Int,
    previewHeight: Int,
    sensorRotation: Int,
    deviceRotation: Int,
    cropCenterX: Float,
    cropCenterY: Float,
    cropWidth: Float,
    cropHeight: Float
): Bitmap? {
    // 1. Convert the Image to a Bitmap.
    val originalBitmap = image.toBitmap() ?: return null

    // 2. Calculate the total rotation needed
    val totalRotation = (sensorRotation - deviceRotation + 360) % 360
    
    // 3. Determine effective image dimensions after accounting for rotation
    val effectiveImageWidth: Float
    val effectiveImageHeight: Float
    
    when (totalRotation) {
        90, 270 -> {
            effectiveImageWidth = image.height.toFloat()
            effectiveImageHeight = image.width.toFloat()
        }
        else -> {
            effectiveImageWidth = image.width.toFloat()
            effectiveImageHeight = image.height.toFloat()
        }
    }
    
    // 4. Calculate scaling factors
    val imageAspect = effectiveImageWidth / effectiveImageHeight
    val previewAspect = previewWidth.toFloat() / previewHeight
    
    val scale: Float
    var dx = 0f
    var dy = 0f
    
    if (imageAspect > previewAspect) {
        // Image is wider than the preview, so it's scaled to fit height.
        scale = previewHeight / effectiveImageHeight
        dx = (previewWidth - effectiveImageWidth * scale) / 2f
    } else {
        // Image is taller than or equal to the preview, so it's scaled to fit width.
        scale = previewWidth / effectiveImageWidth
        dy = (previewHeight - effectiveImageHeight * scale) / 2f
    }
    
    // 5. Define the crop rectangle in screen coordinates.
    val cropRectScreen = RectF(
        cropCenterX - cropWidth / 2,
        cropCenterY - cropHeight / 2,
        cropCenterX + cropWidth / 2,
        cropCenterY + cropHeight / 2
    )
    
    // 6. Convert screen coordinates to effective image coordinates
    val effectiveImageRect = RectF(
        (cropRectScreen.left - dx) / scale,
        (cropRectScreen.top - dy) / scale,
        (cropRectScreen.right - dx) / scale,
        (cropRectScreen.bottom - dy) / scale
    )
    
    // 7. Transform coordinates based on rotation to get actual image coordinates
    val actualImageRect = when (totalRotation) {
        90 -> {
            // 90 degrees clockwise: (x,y) -> (y, effectiveWidth - x)
            RectF(
                effectiveImageRect.top,
                effectiveImageWidth - effectiveImageRect.right,
                effectiveImageRect.bottom,
                effectiveImageWidth - effectiveImageRect.left
            )
        }
        180 -> {
            // 180 degrees: (x,y) -> (effectiveWidth - x, effectiveHeight - y)
            RectF(
                effectiveImageWidth - effectiveImageRect.right,
                effectiveImageHeight - effectiveImageRect.bottom,
                effectiveImageWidth - effectiveImageRect.left,
                effectiveImageHeight - effectiveImageRect.top
            )
        }
        270 -> {
            // 270 degrees clockwise: (x,y) -> (effectiveHeight - y, x)
            RectF(
                effectiveImageHeight - effectiveImageRect.bottom,
                effectiveImageRect.left,
                effectiveImageHeight - effectiveImageRect.top,
                effectiveImageRect.right
            )
        }
        else -> {
            // 0 degrees or default - no transformation needed
            effectiveImageRect
        }
    }
    
    // 8. Clamp the crop rectangle to image bounds
    val clampedRect = android.graphics.Rect(
        actualImageRect.left.toInt().coerceAtLeast(0),
        actualImageRect.top.toInt().coerceAtLeast(0),
        actualImageRect.right.toInt().coerceAtMost(originalBitmap.width),
        actualImageRect.bottom.toInt().coerceAtMost(originalBitmap.height)
    )
    
    // 9. Create the final cropped bitmap.
    val croppedBitmap = Bitmap.createBitmap(
        originalBitmap,
        clampedRect.left,
        clampedRect.top,
        clampedRect.width(),
        clampedRect.height()
    )
    
    // 10. Apply final rotation if needed for display orientation
    val finalRotation = (sensorRotation - deviceRotation + 360) % 360
    if (finalRotation != 0) {
        val rotationMatrix = Matrix()
        rotationMatrix.postRotate(finalRotation.toFloat())
        return Bitmap.createBitmap(
            croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, rotationMatrix, true
        )
    }

    return croppedBitmap
}
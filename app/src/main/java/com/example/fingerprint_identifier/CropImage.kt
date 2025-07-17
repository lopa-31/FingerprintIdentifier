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
 * @param sensorRotation The rotation of the camera sensor (usually from CameraCharacteristics).
 * @param deviceRotation The current rotation of the device (from a DisplayOrientationEventListener or similar).
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

    // 2. Create the transformation matrix.
    val matrix = Matrix()

    // --- Transformation Logic ---
    // This is the most critical part and needs to be configured correctly.
    // It maps the preview coordinates to the sensor's coordinates.

    // 2a. Start with the sensor orientation.
    matrix.postRotate(sensorRotation.toFloat())

    // 2b. Account for the preview's scaling and translation.
    // This assumes the preview is center-cropped inside the preview view.
    val imageAspect = image.width.toFloat() / image.height
    val previewAspect = previewWidth.toFloat() / previewHeight

    val scale: Float
    var dx = 0f
    var dy = 0f

    if (imageAspect > previewAspect) {
        // Image is wider than the preview, so it's scaled to fit height.
        scale = previewHeight.toFloat() / image.height
        dx = (previewWidth - image.width * scale) / 2f
    } else {
        // Image is taller than or equal to the preview, so it's scaled to fit width.
        scale = previewWidth.toFloat() / image.width
        dy = (previewHeight - image.height * scale) / 2f
    }

    matrix.postScale(scale, scale)
    matrix.postTranslate(dx, dy)

    // We need to invert the matrix to go from screen coordinates to image coordinates.
    matrix.invert(matrix)

    // 3. Define the crop rectangle in screen coordinates.
    val cropRectScreen = RectF(
        cropCenterX - cropWidth / 2,
        cropCenterY - cropHeight / 2,
        cropCenterX + cropWidth / 2,
        cropCenterY + cropHeight / 2
    )

    // 4. Map the screen crop rectangle to the image's coordinate space.
    matrix.mapRect(cropRectScreen)

    // 5. Create the final cropped bitmap.
    // The createBitmap function will handle the cropping.
    val croppedBitmap = Bitmap.createBitmap(
        originalBitmap,
        cropRectScreen.left.toInt().coerceAtLeast(0),
        cropRectScreen.top.toInt().coerceAtLeast(0),
        cropRectScreen.width().toInt().coerceAtMost(originalBitmap.width - cropRectScreen.left.toInt()),
        cropRectScreen.height().toInt().coerceAtMost(originalBitmap.height - cropRectScreen.top.toInt())
    )

    // 6. Rotate the final bitmap to match the device's current orientation.
    val totalRotation = (sensorRotation - deviceRotation + 360) % 360
    if (totalRotation != 0) {
        val rotationMatrix = Matrix()
        rotationMatrix.postRotate(totalRotation.toFloat())
        return Bitmap.createBitmap(
            croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, rotationMatrix, true
        )
    }

    return croppedBitmap
}
package com.example.fingerprint_identifier.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Helper class used to convert a YUV image to RGB
 *
 * This class is based on the CameraX sample:
 * https://github.com/android/camera-samples/blob/main/CameraXBasic/utils/src/main/java/com/android/example/camerax/utils/YuvToRgbConverter.kt
 */
class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    // Do not add additional allocations here. They are long-running objects that should be tied
    // to the lifecycle of this converter.
    private var yuvBuffer: ByteArray? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null

    @Synchronized
    fun yuvToRgb(image: ImageProxy, output: Bitmap) {
        val yuvBytes = imageProxyToYuvByteArray(image)

        // Create RenderScript allocations
        val yuvType = android.renderscript.Type.Builder(rs, Element.U8(rs))
            .setX(yuvBytes.size)
            .create()
        inputAllocation = Allocation.createTyped(rs, yuvType, Allocation.USAGE_SCRIPT)

        val rgbaType = android.renderscript.Type.Builder(rs, Element.RGBA_8888(rs))
            .setX(image.width)
            .setY(image.height)
            .create()
        outputAllocation = Allocation.createTyped(rs, rgbaType, Allocation.USAGE_SCRIPT)

        // Copy the YUV input bytes to the input allocation
        inputAllocation!!.copyFrom(yuvBytes)

        // Set the input allocation and execute the script
        scriptYuvToRgb.setInput(inputAllocation)
        scriptYuvToRgb.forEach(outputAllocation)

        // Copy the output allocation to the output bitmap
        outputAllocation!!.copyTo(output)
    }

    private fun imageProxyToYuvByteArray(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = image.planes[2].buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = yBuffer.remaining()

        var position = 0
        // TODO(b/122204603): Use ImageReader.OnImageAvailableListener with isr handler thread
        // instead of wait for SYNCHRONIZATION_FENCE.
        val yuvBytes = ByteArray(ySize + (image.width * image.height / 2))

        // U and V are swapped
        yBuffer.get(yuvBytes, 0, ySize)

        position = ySize

        // YUV_420_888 format is typically represented with three planes:
        // Plane 0: Y (Luminance)
        // Plane 1: U (Chrominance)
        // Plane 2: V (Chrominance)
        // In some devices, U and V planes are interleaved. Here we handle both cases.
        if (image.planes[2].pixelStride == 1) { // Planar
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            uBuffer.get(yuvBytes, position, uSize)
            position += uSize
            vBuffer.get(yuvBytes, position, vSize)
        } else { // Semi-planar (interleaved U and V)
            val vSize = vBuffer.remaining()
            vBuffer.get(yuvBytes, position, vSize)
        }

        return yuvBytes
    }
} 
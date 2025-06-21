package com.example.fingerprint_identifier.utils

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

object ImageQualityUtils {
    // This threshold is empirical. You might need to adjust it based on testing with your specific device.
    private const val BLUR_THRESHOLD = 20.0

    fun isBlurred(bitmap: Bitmap): Boolean {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

        // Apply Laplacian operator
        val laplacianMat = Mat()
        Imgproc.Laplacian(grayMat, laplacianMat, 3)

        val meanAndStdDev = MatOfDouble()
        Core.meanStdDev(laplacianMat, MatOfDouble(), meanAndStdDev)
        val variance = meanAndStdDev.get(0, 0)[0].let { it * it }

        mat.release()
        grayMat.release()
        laplacianMat.release()
        meanAndStdDev.release()

        val isBlurred = variance < BLUR_THRESHOLD
        Log.d("ImageQualityUtils", "Blur check: variance=$variance, threshold=$BLUR_THRESHOLD, isBlurred=$isBlurred")

        return isBlurred
    }
} 
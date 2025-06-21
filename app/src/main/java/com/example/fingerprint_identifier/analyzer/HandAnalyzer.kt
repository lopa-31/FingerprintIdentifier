package com.example.fingerprint_identifier.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.fingerprint_identifier.viewmodel.CameraViewModel
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import com.example.fingerprint_identifier.utils.YuvToRgbConverter

// Enum to represent Hand Type
enum class HandType { LEFT, RIGHT }

// Data class to hold the analysis result
data class HandAnalysisResult(
    val handType: HandType,
    val isDorsal: Boolean,
    val landmarks: List<NormalizedLandmark>,
    val worldLandmarks: List<com.google.mediapipe.tasks.components.containers.Landmark>
)

class HandAnalyzer(
    private val context: Context,
    private val viewModel: CameraViewModel
) : ImageAnalysis.Analyzer {

    private val executorService = Executors.newSingleThreadExecutor()
    private var handLandmarker: HandLandmarker? = null
    private val yuvToRgbConverter = YuvToRgbConverter(context)
    private lateinit var bitmapBuffer: Bitmap

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        executorService.execute {
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()

                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1) // We only want to detect one hand
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setResultListener(this::onResults)
                    .setErrorListener(this::onError)
                    .build()

                handLandmarker = HandLandmarker.createFromOptions(context, options)
            } catch (e: Exception) {
                viewModel.onError(e)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val landmarker = handLandmarker ?: return
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        // Luminosity analysis
        val buffer = mediaImage.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val luma = data.map { it.toInt() and 0xFF }.average()
        viewModel.processLuminosity(luma)

        val frameTime = SystemClock.uptimeMillis()

        if (!::bitmapBuffer.isInitialized) {
            bitmapBuffer = Bitmap.createBitmap(
                mediaImage.width,
                mediaImage.height,
                Bitmap.Config.ARGB_8888
            )
        }
        yuvToRgbConverter.yuvToRgb(imageProxy, bitmapBuffer)

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, mediaImage.width, mediaImage.height, matrix, false
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        landmarker.detectAsync(mpImage, frameTime)
        imageProxy.close()
    }

    private fun onResults(result: HandLandmarkerResult, input: MPImage) {
        if (result.landmarks().isEmpty()) {
            viewModel.onNoHandDetected()
            return
        }

        val handLandmarks = result.landmarks().first()
        val worldLandmarks = result.worldLandmarks().first()
        val handedness = result.handedness().first()

        val isDorsal = isHandDorsal(worldLandmarks, handedness.first())
        val handType = if (handedness.first().categoryName() == "Left") HandType.LEFT else HandType.RIGHT

        viewModel.processHandAnalysis(
            HandAnalysisResult(
                handType = handType,
                isDorsal = isDorsal,
                landmarks = handLandmarks,
                worldLandmarks = worldLandmarks
            )
        )
    }

    private fun onError(error: RuntimeException) {
        viewModel.onError(error)
    }

    private fun isHandDorsal(
        worldLandmarks: List<com.google.mediapipe.tasks.components.containers.Landmark>,
        handedness: Category
    ): Boolean {
        val wrist = worldLandmarks[HandLandmark.WRIST]
        val indexMcp = worldLandmarks[HandLandmark.INDEX_FINGER_MCP]
        val pinkyMcp = worldLandmarks[HandLandmark.PINKY_MCP]

        val v1x = pinkyMcp.x() - wrist.x()
        val v1y = pinkyMcp.y() - wrist.y()

        val v2x = indexMcp.x() - wrist.x()
        val v2y = indexMcp.y() - wrist.y()

        // Z component of the cross product
        val normalZ = (v1x * v2y) - (v1y * v2x)

        return if (handedness.categoryName() == "Right") {
            normalZ < 0
        } else { // Left
            normalZ > 0
        }
    }
}

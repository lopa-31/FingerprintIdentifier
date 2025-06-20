package com.example.fingerprint_identifier.analyzer

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.example.fingerprint_identifier.viewmodel.CameraViewModel
import com.example.fingerprint_identifier.viewmodel.CameraState

// Enum to represent Hand Type
enum class HandType { LEFT, RIGHT }

// Data class to hold the analysis result
data class HandAnalysisResult(
    val handType: HandType,
    val isDorsal: Boolean,
    val landmarks: List<PoseLandmark>
)

class HandAnalyzer(private val viewModel: CameraViewModel) : ImageAnalysis.Analyzer {

    private val options = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
        .build()

    private val detector = PoseDetection.getClient(options)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { pose ->
                    processPose(pose)
                }
                .addOnFailureListener { e ->
                    viewModel.onError(e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun processPose(pose: Pose) {
        // Get visibility of left and right hand landmarks
        val leftHandLandmarks = listOfNotNull(
            pose.getPoseLandmark(PoseLandmark.LEFT_WRIST),
            pose.getPoseLandmark(PoseLandmark.LEFT_THUMB),
            pose.getPoseLandmark(PoseLandmark.LEFT_INDEX),
            pose.getPoseLandmark(PoseLandmark.LEFT_PINKY)
        )
        val rightHandLandmarks = listOfNotNull(
            pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST),
            pose.getPoseLandmark(PoseLandmark.RIGHT_THUMB),
            pose.getPoseLandmark(PoseLandmark.RIGHT_INDEX),
            pose.getPoseLandmark(PoseLandmark.RIGHT_PINKY)
        )

        val isLeftHandVisible = leftHandLandmarks.all { it.inFrameLikelihood > 0.6 }
        val isRightHandVisible = rightHandLandmarks.all { it.inFrameLikelihood > 0.6 }

        // We only want one hand in the frame
        if (isLeftHandVisible && isRightHandVisible) {
            viewModel.onNoHandDetected()
            return
        }

        when {
            isLeftHandVisible -> {
                val allLeftHandLandmarks = pose.allPoseLandmarks.filter { it.landmarkType >= PoseLandmark.LEFT_WRIST }
                val visibleLandmarkCount = allLeftHandLandmarks.count { it.inFrameLikelihood > 0.6 }
                // Heuristic: If we see less than 5 key landmarks, assume it's the dorsal side.
                val isDorsal = visibleLandmarkCount < 5
                viewModel.processHandAnalysis(HandAnalysisResult(HandType.LEFT, isDorsal, pose.allPoseLandmarks))
            }
            isRightHandVisible -> {
                val allRightHandLandmarks = pose.allPoseLandmarks.filter { it.landmarkType >= PoseLandmark.RIGHT_WRIST }
                val visibleLandmarkCount = allRightHandLandmarks.count { it.inFrameLikelihood > 0.6 }
                val isDorsal = visibleLandmarkCount < 5
                viewModel.processHandAnalysis(HandAnalysisResult(HandType.RIGHT, isDorsal, pose.allPoseLandmarks))
            }
            else -> {
                viewModel.onNoHandDetected()
            }
        }
    }
}

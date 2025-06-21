package com.example.fingerprint_identifier.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.fingerprint_identifier.analyzer.HandAnalysisResult
import com.example.fingerprint_identifier.analyzer.HandType
import com.example.fingerprint_identifier.utils.LandmarkUtils
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

sealed class CameraState {
    object AwaitingPalm : CameraState()
    data class PalmDetected(val handType: HandType, val sessionId: UUID, val landmarks: HandAnalysisResult) : CameraState()
    data class AwaitingFinger(val handType: HandType, val fingerName: String, val fingerIndex: Int, val sessionId: UUID) : CameraState()
    data class FingerDetected(val handType: HandType, val fingerName: String, val fingerIndex: Int, val sessionId: UUID, val landmarks: HandAnalysisResult) : CameraState()
    data class PalmCaptureSuccess(val handType: HandType, val sessionId: UUID, val imageUri: Uri) : CameraState()
    data class FingerCaptureSuccess(val handType: HandType, val fingerName: String, val fingerIndex: Int, val sessionId: UUID, val imageUri: Uri) : CameraState()
    data class AllCapturesDone(val handType: HandType) : CameraState()
    data class Error(val message: String) : CameraState()
    object AwaitingVerification : CameraState()
    data class VerificationHandDetected(val landmarks: HandAnalysisResult) : CameraState()
    data class Verification(val imageUri: Uri) : CameraState()
}

sealed class OneShotEvent {
    data class TriggerAutoFocus(val landmarks: List<NormalizedLandmark>) : OneShotEvent()
}

class CameraViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    private val _cameraState: MutableStateFlow<CameraState> = MutableStateFlow(CameraState.AwaitingPalm)
    val cameraState = _cameraState.asStateFlow()

    private val _warningEvents = MutableSharedFlow<String>()
    val warningEvents = _warningEvents.asSharedFlow()

    private val _oneShotEvents = MutableSharedFlow<OneShotEvent>()
    val oneShotEvents = _oneShotEvents.asSharedFlow()

    private val _deleteFileEvents = MutableSharedFlow<Uri>()
    val deleteFileEvents = _deleteFileEvents.asSharedFlow()

    private val fingersToCapture = listOf("Thumb", "Index", "Middle", "Ring", "Pinky")
    private var lastLuminosityWarning = 0L
    private val luminosityWarningCooldown = 2000L
    private var isCapturing = false

    companion object {
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_HAND_TYPE = "handType"
        private const val KEY_FINGER_INDEX = "fingerIndex"
    }

    init {
        val sessionIdStr: String? = savedStateHandle[KEY_SESSION_ID]
        val handTypeStr: String? = savedStateHandle[KEY_HAND_TYPE]
        val fingerIndex: Int? = savedStateHandle[KEY_FINGER_INDEX]

        _cameraState.value = if (sessionIdStr != null && handTypeStr != null && fingerIndex != null) {
            val sessionId = UUID.fromString(sessionIdStr)
            val handType = HandType.valueOf(handTypeStr)
            if (fingerIndex >= fingersToCapture.size) {
                CameraState.AllCapturesDone(handType)
            } else {
                CameraState.AwaitingFinger(handType, fingersToCapture[fingerIndex], fingerIndex, sessionId)
            }
        } else {
            CameraState.AwaitingPalm
        }
    }

    fun processLuminosity(luma: Double) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLuminosityWarning < luminosityWarningCooldown) return

        when {
            luma < 50 -> {
                _warningEvents.tryEmit("Too dark")
                lastLuminosityWarning = currentTime
            }
            luma > 200 -> {
                _warningEvents.tryEmit("Too bright")
                lastLuminosityWarning = currentTime
            }
        }
    }

    fun capturePalm(uri: Uri, handType: HandType, sessionId: UUID) {
        Log.d("CameraViewModel", "capturePalm called with uri: $uri")
        savedStateHandle[KEY_SESSION_ID] = sessionId.toString()
        savedStateHandle[KEY_HAND_TYPE] = handType.name
        _cameraState.value = CameraState.PalmCaptureSuccess(handType, sessionId, uri)
        isCapturing = false // Capture completed
        Log.d("CameraViewModel", "capturePalm completed, isCapturing=false")
    }

    fun captureFinger(uri: Uri, handType: HandType, fingerName: String, fingerIndex: Int, sessionId: UUID) {
        Log.d("CameraViewModel", "captureFinger called with uri: $uri")
        _cameraState.value = CameraState.FingerCaptureSuccess(
            handType,
            fingerName,
            fingerIndex,
            sessionId,
            uri
        )
        isCapturing = false // Capture completed
        Log.d("CameraViewModel", "captureFinger completed, isCapturing=false")
    }

    fun startCapture() {
        Log.d("CameraViewModel", "startCapture called, setting isCapturing=true")
        isCapturing = true
    }

    fun cancelCapture() {
        Log.d("CameraViewModel", "cancelCapture called, setting isCapturing=false")
        isCapturing = false
    }

    fun processHandAnalysis(result: HandAnalysisResult?) {
        // Ignore hand analysis updates during capture to prevent state changes
        if (isCapturing) {
            Log.d("CameraViewModel", "Ignoring hand analysis because isCapturing=true")
            return
        }

        if (result == null) {
            onNoHandDetected()
            return
        }

        if (result.isDorsal) {
            _warningEvents.tryEmit("Do not show the dorsal side of the hand.")
            onNoHandDetected() // Revert state if dorsal view is detected
            return
        }

        when (val currentState = _cameraState.value) {
            is CameraState.AwaitingPalm, is CameraState.PalmDetected -> {
                _cameraState.value = CameraState.PalmDetected(
                    result.handType,
                    (currentState as? CameraState.PalmDetected)?.sessionId ?: UUID.randomUUID(),
                    result
                )
                _oneShotEvents.tryEmit(OneShotEvent.TriggerAutoFocus(result.landmarks))
            }
            is CameraState.AwaitingFinger, is CameraState.FingerDetected, is CameraState.AwaitingVerification, is CameraState.VerificationHandDetected -> {
                handleFingerState(result, currentState)
            }
            else -> { /* Do nothing for other states */ }
        }
    }

    private fun handleFingerState(result: HandAnalysisResult, currentState: CameraState) {
        val requiredHandType = when (currentState) {
            is CameraState.AwaitingFinger -> currentState.handType
            is CameraState.FingerDetected -> currentState.handType
            else -> null // Verification can be any hand
        }

        if (requiredHandType != null && result.handType != requiredHandType) {
            _warningEvents.tryEmit("Incorrect hand detected. Please use your $requiredHandType.")
            onNoHandDetected()
            return
        }

        when(currentState) {
            is CameraState.AwaitingFinger -> {
                if (isFingerInPosition(result.landmarks, currentState.fingerIndex)) {
                    _cameraState.value = CameraState.FingerDetected(
                        result.handType,
                        currentState.fingerName,
                        currentState.fingerIndex,
                        currentState.sessionId,
                        result
                    )
                } else {
                    _warningEvents.tryEmit("${result.handType} hand detected. Center your ${currentState.fingerName}.")
                }
            }
            is CameraState.AwaitingVerification -> {
                if (findFingerInPosition(result.landmarks) != -1) {
                    _cameraState.value = CameraState.VerificationHandDetected(result)
                }
            }
            is CameraState.FingerDetected -> {
                if (result.handType != currentState.handType) {
                    _warningEvents.tryEmit("Incorrect hand detected. Please use your ${currentState.handType}.")
                    onNoHandDetected()
                    return
                }
                // If finger moves out of position, revert to awaiting state
                if (!isFingerInPosition(result.landmarks, currentState.fingerIndex)) {
                    _cameraState.value = CameraState.AwaitingFinger(
                        currentState.handType,
                        currentState.fingerName,
                        currentState.fingerIndex,
                        currentState.sessionId
                    )
                } else {
                    // otherwise, just update the landmarks
                    _cameraState.value = currentState.copy(landmarks = result)
                }
            }
            is CameraState.VerificationHandDetected -> _cameraState.value = currentState.copy(landmarks = result)
            else -> {}
        }
    }

    private fun isFingerInPosition(landmarks: List<NormalizedLandmark>, fingerIndex: Int): Boolean {
        val fingerLandmarks = LandmarkUtils.getFingerLandmarks(landmarks, fingerIndex)
        if (fingerLandmarks.isEmpty()) return false

        val avgX = fingerLandmarks.map { it.x() }.average()
        val avgY = fingerLandmarks.map { it.y() }.average()

        // Check if the finger is roughly in the center of the oval.
        // The oval is centered, so we expect coordinates around 0.5.
        // The oval is taller than it is wide, so we allow more deviation in Y.
        val isHorizontallyCentered = avgX > 0.4 && avgX < 0.6
        val isVerticallyCentered = avgY > 0.35 && avgY < 0.65

        return isHorizontallyCentered && isVerticallyCentered
    }

    private fun findFingerInPosition(landmarks: List<NormalizedLandmark>): Int {
        for (i in 0..4) {
            if (isFingerInPosition(landmarks, i)) {
                return i
            }
        }
        return -1
    }

    fun startFingerCapture() {
        if (_cameraState.value is CameraState.PalmCaptureSuccess) {
            val currentState = _cameraState.value as CameraState.PalmCaptureSuccess
            savedStateHandle[KEY_FINGER_INDEX] = 0
            _cameraState.value = CameraState.AwaitingFinger(
                currentState.handType,
                fingersToCapture[0],
                0,
                currentState.sessionId
            )
        }
    }

    fun confirmFingerCapture() {
        if (_cameraState.value is CameraState.FingerCaptureSuccess) {
            val currentState = _cameraState.value as CameraState.FingerCaptureSuccess
            val nextFingerIndex = currentState.fingerIndex + 1
            savedStateHandle[KEY_FINGER_INDEX] = nextFingerIndex

            if (nextFingerIndex < fingersToCapture.size) {
                _cameraState.value = CameraState.AwaitingFinger(
                    currentState.handType,
                    fingersToCapture[nextFingerIndex],
                    nextFingerIndex,
                    currentState.sessionId
                )
            } else {
                _cameraState.value = CameraState.AllCapturesDone(currentState.handType)
            }
        }
    }

    fun retakeCapture() {
        when(val currentState = _cameraState.value) {
            is CameraState.PalmCaptureSuccess -> {
                _deleteFileEvents.tryEmit(currentState.imageUri)
                savedStateHandle.remove<String>(KEY_SESSION_ID)
                savedStateHandle.remove<String>(KEY_HAND_TYPE)
                savedStateHandle.remove<String>(KEY_FINGER_INDEX)
                _cameraState.value = CameraState.AwaitingPalm
            }
            is CameraState.FingerCaptureSuccess -> {
                _deleteFileEvents.tryEmit(currentState.imageUri)
                _cameraState.value = CameraState.AwaitingFinger(
                    currentState.handType,
                    currentState.fingerName,
                    currentState.fingerIndex,
                    currentState.sessionId
                )
            }
            else -> {}
        }
    }

    fun onNoHandDetected() {
        _cameraState.value = when (val currentState = _cameraState.value) {
            is CameraState.PalmDetected -> CameraState.AwaitingPalm
            is CameraState.FingerDetected -> CameraState.AwaitingFinger(
                currentState.handType,
                currentState.fingerName,
                currentState.fingerIndex,
                currentState.sessionId
            )
            is CameraState.VerificationHandDetected -> CameraState.AwaitingVerification
            else -> currentState // For other states, do nothing
        }
    }

    fun onError(exception: Exception) {
        _cameraState.value = CameraState.Error(exception.message ?: "An unknown error occurred")
    }

    fun onImageBlurred() {
        _warningEvents.tryEmit("Image is blurred, please try again.")
    }

    fun startVerificationMode() {
        _cameraState.value = CameraState.AwaitingVerification
    }

    fun onVerificationImageCaptured(uri: Uri) {
        _cameraState.value = CameraState.Verification(uri)
    }

    fun resetToAwaitingPalm() {
        _cameraState.value = CameraState.AwaitingPalm
    }
} 
package com.example.fingerprint_identifier.viewmodel

import androidx.lifecycle.ViewModel
import com.example.fingerprint_identifier.analyzer.HandAnalysisResult
import com.example.fingerprint_identifier.analyzer.HandType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import android.net.Uri

sealed class CameraState {
    object AwaitingPalm : CameraState()
    data class PalmDetected(val handType: HandType, val sessionId: UUID) : CameraState()
    data class AwaitingFinger(val handType: HandType, val fingerName: String, val fingerIndex: Int, val sessionId: UUID) : CameraState()
    data class FingerDetected(val handType: HandType, val fingerName: String, val fingerIndex: Int, val sessionId: UUID) : CameraState()
    data class PalmCaptureSuccess(val handType: HandType, val sessionId: UUID, val imageUri: Uri) : CameraState()
    data class FingerCaptureSuccess(val handType: HandType, val fingerName: String, val fingerIndex: Int, val sessionId: UUID, val imageUri: Uri) : CameraState()
    data class AllCapturesDone(val handType: HandType) : CameraState()
    data class Error(val message: String) : CameraState()
}

class CameraViewModel : ViewModel() {

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.AwaitingPalm)
    val cameraState: StateFlow<CameraState> = _cameraState

    private val fingersToCapture = listOf("Thumb", "Index", "Middle", "Ring", "Pinky")

    fun capturePalm(handType: HandType, uri: Uri) {
        if (_cameraState.value is CameraState.AwaitingPalm || _cameraState.value is CameraState.PalmDetected) {
            val sessionId = UUID.randomUUID()
            _cameraState.value = CameraState.PalmCaptureSuccess(handType, sessionId, uri)
        }
    }

    fun captureFinger(uri: Uri) {
        if (_cameraState.value is CameraState.AwaitingFinger || _cameraState.value is CameraState.FingerDetected) {
            val currentState = _cameraState.value as CameraState.AwaitingFinger
            _cameraState.value = CameraState.FingerCaptureSuccess(
                currentState.handType,
                fingersToCapture[currentState.fingerIndex],
                currentState.fingerIndex,
                currentState.sessionId,
                uri
            )
        }
    }

    fun processHandAnalysis(result: HandAnalysisResult?) {
        when (val currentState = _cameraState.value) {
            is CameraState.AwaitingPalm -> {
                if (result != null) {
                    _cameraState.value = CameraState.PalmDetected(
                        result.handType,
                        UUID.randomUUID()
                    )
                }
            }
            is CameraState.AwaitingFinger -> {
                if(result != null){
                    _cameraState.value = CameraState.FingerDetected(
                        currentState.handType,
                        currentState.fingerName,
                        currentState.fingerIndex,
                        currentState.sessionId
                    )
                }
            }
            else -> { /* Do nothing for now */ }
        }
    }

    fun startFingerCapture() {
        if (_cameraState.value is CameraState.PalmCaptureSuccess) {
            val currentState = _cameraState.value as CameraState.PalmCaptureSuccess
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
                _cameraState.value = CameraState.AwaitingPalm
            }
            is CameraState.FingerCaptureSuccess -> {
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
        _cameraState.value = CameraState.AwaitingPalm
    }

    fun onError(exception: Exception) {
        _cameraState.value = CameraState.Error(exception.message ?: "An unknown error occurred")
    }
} 
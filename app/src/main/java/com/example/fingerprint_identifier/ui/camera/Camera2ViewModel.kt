package com.example.fingerprint_identifier.ui.camera

import androidx.lifecycle.ViewModel
import com.example.fingerprint_identifier.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Predefined warnings for image processing, including priority stage.
 */
sealed class ProcessingWarning(
    val title: String,
    val description: String,
    val imageRes: Int,
    val stage: Int // Priority: 1 (highest) > 3 (lowest)
) {
    object PoorLighting : ProcessingWarning("Poor Lighting", "Low light detected. Please improve lighting conditions.", R.drawable.ic_launcher_foreground, 1)
    object LivenessCheckFailed : ProcessingWarning("Liveness Check", "Liveness check failed. Please use a real finger.", R.drawable.ic_launcher_background, 1)
    object FingerNotDetected : ProcessingWarning("Finger Position", "Please ensure your finger covers the designated area.", R.drawable.ic_launcher_foreground, 2)
    object ImageBlurry : ProcessingWarning("Image Blur", "Image is too blurry. Please hold your hand steady.", R.drawable.ic_launcher_background, 3)
    object BrightSpotsDetected : ProcessingWarning("Bright Spots", "Bright spots detected. Please adjust lighting to avoid reflections.", R.drawable.ic_launcher_foreground, 3)
}

/**
 * Simplified camera states. Warnings are handled separately.
 */
sealed class Camera2State {
    object Initial : Camera2State()
    object Validating : Camera2State()
    object Success : Camera2State()
}

class Camera2ViewModel : ViewModel() {
    
    private val _camera2State = MutableStateFlow<Camera2State>(Camera2State.Initial)
    val camera2State = _camera2State.asStateFlow()
    
    private val _warnings = MutableStateFlow<List<ProcessingWarning>>(emptyList())
    val warnings = _warnings.asStateFlow()
    
    fun setInitialState() {
        _camera2State.value = Camera2State.Initial
        clearAllWarnings()
    }
    
    fun setValidatingState() {
        _camera2State.value = Camera2State.Validating
    }
    
    fun setSuccessState() {
        _camera2State.value = Camera2State.Success
        clearAllWarnings()
    }
    
    /**
     * Adds a warning to the list if it's not already present.
     * The list is kept sorted by priority stage.
     */
    fun addWarning(warning: ProcessingWarning) {
        _warnings.update { currentWarnings ->
            if (currentWarnings.contains(warning)) {
                currentWarnings
            } else {
                (currentWarnings + warning).sortedBy { it.stage }
            }
        }
    }
    
    /**
     * Removes a specific warning from the list.
     */
    fun removeWarning(warning: ProcessingWarning) {
        _warnings.update { currentWarnings ->
            currentWarnings.filterNot { it::class == warning::class }
        }
    }
    
    /**
     * Clears all active warnings.
     */
    fun clearAllWarnings() {
        _warnings.value = emptyList()
    }
    
    /**
     * Gets the highest-priority warning.
     */
    fun getPrioritizedWarning(): ProcessingWarning? {
        return warnings.value.firstOrNull()
    }
    
    /**
     * Gets the current highest priority stage among active warnings.
     */
    fun getCurrentPriorityStage(): Int {
        return getPrioritizedWarning()?.stage ?: 0
    }
    
    /**
     * Gets a map of warning counts per stage.
     */
    fun getWarningsCountByStage(): Map<Int, Int> {
        return warnings.value.groupBy { it.stage }
            .mapValues { it.value.size }
    }
    
    /**
     * Returns the current camera state.
     */
    fun getCurrentState(): Camera2State {
        return _camera2State.value
    }
} 
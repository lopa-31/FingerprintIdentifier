package com.example.fingerprint_identifier.ui.camera

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ValidationWarning(
    val id: String,
    val title: String,
    val description: String,
    val imageRes: Int,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class Camera2State {
    object Initial : Camera2State()
    object Validating : Camera2State()
    data class ValidationWarnings(val currentWarning: ValidationWarning, val totalWarnings: Int) : Camera2State()
    object Success : Camera2State()
    object Failure : Camera2State()
}

class Camera2ViewModel : ViewModel() {
    
    private val _camera2State = MutableStateFlow<Camera2State>(Camera2State.Initial)
    val camera2State = _camera2State.asStateFlow()
    
    private val warningsQueue = mutableListOf<ValidationWarning>()
    private var baseState: Camera2State = Camera2State.Initial
    
    fun setInitialState() {
        baseState = Camera2State.Initial
        clearAllWarnings()
        updateStateBasedOnWarnings()
    }
    
    fun setValidatingState() {
        baseState = Camera2State.Validating
        updateStateBasedOnWarnings()
    }
    
    fun setSuccessState() {
        baseState = Camera2State.Success
        clearAllWarnings()
        _camera2State.value = Camera2State.Success
    }
    
    fun setFailureState() {
        baseState = Camera2State.Failure
        clearAllWarnings()
        _camera2State.value = Camera2State.Failure
    }
    
    /**
     * Add a warning to the queue (called by image analyzer)
     * Only adds if a warning with the same title doesn't already exist
     */
    fun addWarning(title: String, description: String, imageRes: Int) {
        // Check if a warning with the same title already exists
        val existingWarning = warningsQueue.find { it.title == title }
        
        if (existingWarning == null) {
            val warning = ValidationWarning(
                id = generateWarningId(),
                title = title,
                description = description,
                imageRes = imageRes
            )
            warningsQueue.add(warning)
            updateStateBasedOnWarnings()
        }
        // If warning already exists, do nothing (don't add duplicate)
    }
    
    /**
     * Add a warning object to the queue
     */
    fun addWarning(warning: ValidationWarning) {
        warningsQueue.add(warning)
        updateStateBasedOnWarnings()
    }
    
    /**
     * Clear the current warning (first in queue)
     */
    fun clearCurrentWarning() {
        if (warningsQueue.isNotEmpty()) {
            warningsQueue.removeAt(0)
            updateStateBasedOnWarnings()
        }
    }
    
    /**
     * Clear a specific warning by ID
     */
    fun clearWarning(warningId: String) {
        warningsQueue.removeAll { it.id == warningId }
        updateStateBasedOnWarnings()
    }
    
    /**
     * Clear all warnings
     */
    fun clearAllWarnings() {
        warningsQueue.clear()
        updateStateBasedOnWarnings()
    }
    
    /**
     * Get current warnings count
     */
    fun getWarningsCount(): Int {
        return warningsQueue.size
    }
    
    /**
     * Get all warnings
     */
    fun getAllWarnings(): List<ValidationWarning> {
        return warningsQueue.toList()
    }
    
    /**
     * Check if a specific warning exists by ID
     */
    fun hasWarning(warningId: String): Boolean {
        return warningsQueue.any { it.id == warningId }
    }
    
    /**
     * Check if a warning with specific title exists
     */
    fun hasWarningByTitle(title: String): Boolean {
        return warningsQueue.any { it.title == title }
    }
    
    /**
     * Clear a specific warning by title/type
     */
    fun clearWarningByTitle(title: String) {
        warningsQueue.removeAll { it.title == title }
        updateStateBasedOnWarnings()
    }
    
    /**
     * Update an existing warning or add it if it doesn't exist
     */
    fun updateOrAddWarning(title: String, description: String, imageRes: Int) {
        val existingWarning = warningsQueue.find { it.title == title }
        
        if (existingWarning != null) {
            // Update existing warning
            val index = warningsQueue.indexOf(existingWarning)
            val updatedWarning = existingWarning.copy(
                description = description,
                imageRes = imageRes,
                timestamp = System.currentTimeMillis()
            )
            warningsQueue[index] = updatedWarning
        } else {
            // Add new warning
            val warning = ValidationWarning(
                id = generateWarningId(),
                title = title,
                description = description,
                imageRes = imageRes
            )
            warningsQueue.add(warning)
        }
        updateStateBasedOnWarnings()
    }
    
    /**
     * Update state based on current warnings queue with priority system
     */
    private fun updateStateBasedOnWarnings() {
        _camera2State.value = when {
            warningsQueue.isNotEmpty() -> {
                val prioritizedWarning = getPriorityWarning()
                Camera2State.ValidationWarnings(
                    currentWarning = prioritizedWarning,
                    totalWarnings = warningsQueue.size
                )
            }
            else -> baseState
        }
    }
    
    /**
     * Get warning with highest priority based on processing stage
     * Stage 1 (Initial checks) > Stage 2 (Segmentation) > Stage 3 (Quality checks)
     */
    private fun getPriorityWarning(): ValidationWarning {
        // Stage 1: Initial checks (highest priority)
        val stage1Warnings = warningsQueue.filter { getWarningStage(it.title) == 1 }
        if (stage1Warnings.isNotEmpty()) {
            return stage1Warnings.first()
        }
        
        // Stage 2: Segmentation (medium priority)
        val stage2Warnings = warningsQueue.filter { getWarningStage(it.title) == 2 }
        if (stage2Warnings.isNotEmpty()) {
            return stage2Warnings.first()
        }
        
        // Stage 3: Quality checks (lowest priority)
        val stage3Warnings = warningsQueue.filter { getWarningStage(it.title) == 3 }
        if (stage3Warnings.isNotEmpty()) {
            return stage3Warnings.first()
        }
        
        // Fallback to first warning if none match stages
        return warningsQueue.first()
    }
    
    /**
     * Get processing stage for a warning title
     */
    private fun getWarningStage(title: String): Int {
        return when (title) {
            "Poor Lighting", "Liveness Check" -> 1 // Stage 1: Initial checks
            "Finger Position" -> 2 // Stage 2: Segmentation
            "Image Blur", "Bright Spots" -> 3 // Stage 3: Quality checks
            else -> 4 // Unknown warnings get lowest priority
        }
    }
    
    /**
     * Get warnings count by stage
     */
    fun getWarningsCountByStage(): Map<Int, Int> {
        return warningsQueue.groupBy { getWarningStage(it.title) }
            .mapValues { it.value.size }
    }
    
    /**
     * Get current priority stage being displayed
     */
    fun getCurrentPriorityStage(): Int {
        return if (warningsQueue.isNotEmpty()) {
            getWarningStage(getPriorityWarning().title)
        } else {
            0 // No warnings
        }
    }
    
    /**
     * Generate unique warning ID
     */
    private fun generateWarningId(): String {
        return "warning_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
    
    fun getCurrentState(): Camera2State {
        return _camera2State.value
    }
} 
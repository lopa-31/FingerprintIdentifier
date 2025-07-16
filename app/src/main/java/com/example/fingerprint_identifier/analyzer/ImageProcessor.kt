package com.example.fingerprint_identifier.analyzer

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.util.Log
import com.example.fingerprint_identifier.R
import com.example.fingerprint_identifier.ui.camera.Camera2State
import com.example.fingerprint_identifier.ui.camera.Camera2ViewModel
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Processing stages for image analysis
 */
enum class ProcessingStage {
    INITIAL_CHECKS,     // Low light + Liveness checks
    SEGMENTATION,       // Finger segmentation
    QUALITY_CHECKS,     // Blur + Bright spot checks
    COMPLETED           // All checks passed
}

/**
 * Processed image data
 */
data class ProcessedImage(
    val bitmap: Bitmap,
    val timestamp: Long,
    val qualityScore: Float,
    val processingStage: ProcessingStage
)

/**
 * Image processor that handles multi-stage ML model execution
 * and integrates with Camera2ViewModel for state management
 */
class ImageProcessor(
    private val viewModel: Camera2ViewModel,
    private val coroutineScope: CoroutineScope
) : ImageReader.OnImageAvailableListener {
    
    companion object {
        private const val TAG = "ImageProcessor"
        private const val REQUIRED_SUCCESSFUL_IMAGES = 3
        private const val MAX_BUFFER_SIZE = 5
        private const val PROCESSING_TIMEOUT_MS = 5000L
    }
    
    private val modelProcessor = ModelProcessor()
    private val isProcessing = AtomicBoolean(false)
    private val processedImageBuffer = mutableListOf<ProcessedImage>()
    private val processingCounter = AtomicInteger(0)
    
    // State tracking
    private var currentStage = ProcessingStage.INITIAL_CHECKS
    private var hasActiveWarnings = false
    
    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "Failed to acquire image")
            return
        }
        
        // Skip processing if we're already processing or have enough images
        if (isProcessing.get() || processedImageBuffer.size >= REQUIRED_SUCCESSFUL_IMAGES) {
            image.close()
            return
        }
        
        // Start processing in background thread
        coroutineScope.launch(Dispatchers.Default) {
            try {
                processImage(image)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                image.close()
            }
        }
    }
    
    /**
     * Main image processing pipeline
     */
    private suspend fun processImage(image: Image) {
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }
        
        val processingId = processingCounter.incrementAndGet()
        Log.d(TAG, "Starting image processing #$processingId")
        
        try {
            // Stage 1: Initial checks (Low light + Liveness) - Run in parallel
            val stage1Results = performInitialChecks(image)
            if (!stage1Results.first || !stage1Results.second) {
                Log.d(TAG, "Image #$processingId failed initial checks")
                handleProcessingFailure(stage1Results.third)
                return
            }
            
            // Clear resolved warnings from initial checks
            clearWarningsForStage(ProcessingStage.INITIAL_CHECKS)
            
            // Update state to indicate we're progressing
            updateProcessingState(ProcessingStage.SEGMENTATION)
            
            // Stage 2: Segmentation check on background thread
            val segmentationResult = withContext(Dispatchers.Default) {
                modelProcessor.performSegmentation(image)
            }
            if (!segmentationResult.passed) {
                Log.d(TAG, "Image #$processingId failed segmentation")
                handleProcessingFailure(segmentationResult.warnings)
                return
            }
            
            // Clear resolved segmentation warnings
            clearWarningsForStage(ProcessingStage.SEGMENTATION)
            
            // Update state for quality checks
            updateProcessingState(ProcessingStage.QUALITY_CHECKS)
            
            // Stage 3: Quality checks (Blur + Bright spot) - Run in parallel
            val qualityResults = performQualityChecks(image)
            if (!qualityResults.first || !qualityResults.second) {
                Log.d(TAG, "Image #$processingId failed quality checks")
                handleProcessingFailure(qualityResults.third)
                return
            }
            
            // Clear resolved quality warnings
            clearWarningsForStage(ProcessingStage.QUALITY_CHECKS)
            
            // All checks passed - add to buffer
            val processedImage = createProcessedImage(image, qualityResults.fourth)
            addToBuffer(processedImage)
            
            Log.d(TAG, "Image #$processingId successfully processed. Buffer size: ${processedImageBuffer.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in processing pipeline", e)
            handleProcessingFailure(listOf("Processing error: ${e.message}"))
        } finally {
            isProcessing.set(false)
        }
    }
    
    /**
     * Stage 1: Run low light and liveness checks in parallel on background threads
     */
    private suspend fun performInitialChecks(image: Image): Triple<Boolean, Boolean, List<String>> {
        val warnings = mutableListOf<String>()
        
        // Run both checks in parallel on background threads (CPU-intensive work)
        val lowLightDeferred = coroutineScope.async(Dispatchers.Default) { 
            modelProcessor.checkLowLight(image) 
        }
        val livenessDeferred = coroutineScope.async(Dispatchers.Default) { 
            modelProcessor.checkLiveness(image) 
        }
        
        // Wait for both results
        val lowLightResult = lowLightDeferred.await()
        val livenessResult = livenessDeferred.await()
        
        // Collect warnings
        warnings.addAll(lowLightResult.warnings)
        warnings.addAll(livenessResult.warnings)
        
        return Triple(lowLightResult.passed, livenessResult.passed, warnings)
    }
    
    /**
     * Stage 3: Run blur and bright spot checks in parallel on background threads
     */
    private suspend fun performQualityChecks(image: Image): Quadruple<Boolean, Boolean, List<String>, Float> {
        val warnings = mutableListOf<String>()
        
        // Run both checks in parallel on background threads (CPU-intensive work)
        val blurDeferred = coroutineScope.async(Dispatchers.Default) { 
            modelProcessor.checkBlur(image) 
        }
        val brightSpotDeferred = coroutineScope.async(Dispatchers.Default) { 
            modelProcessor.checkBrightSpots(image) 
        }
        
        // Wait for both results
        val blurResult = blurDeferred.await()
        val brightSpotResult = brightSpotDeferred.await()
        
        // Collect warnings
        warnings.addAll(blurResult.warnings)
        warnings.addAll(brightSpotResult.warnings)
        
        // Calculate overall quality score
        val qualityScore = (blurResult.confidence + brightSpotResult.confidence) / 2
        
        return Quadruple(blurResult.passed, brightSpotResult.passed, warnings, qualityScore)
    }
    
    /**
     * Handle processing failure and update state with warnings
     */
    private suspend fun handleProcessingFailure(warnings: List<String>) {
        if (warnings.isNotEmpty()) {
            hasActiveWarnings = true
            // Add warnings to ViewModel on Main thread (only if they don't already exist)
            withContext(Dispatchers.Main) {
                warnings.forEach { warning ->
                    val title = getWarningTitle(warning)
                    // Only add warning if it doesn't already exist
                    if (!viewModel.hasWarningByTitle(title)) {
                        viewModel.addWarning(
                            title,
                            warning,
                            getWarningIcon(warning)
                        )
                    }
                }
            }
        }
        
        // Update state based on current stage and warnings
        updateViewModelState()
    }
    
    /**
     * Update processing state and ViewModel state
     */
    private suspend fun updateProcessingState(stage: ProcessingStage) {
        currentStage = stage
        updateViewModelState()
    }
    
    /**
     * Update ViewModel state based on current processing stage and warnings
     */
    private suspend fun updateViewModelState() {
        withContext(Dispatchers.Main) {
            when (currentStage) {
                ProcessingStage.INITIAL_CHECKS -> {
                    // Stay in initial state during initial checks
                    if (viewModel.getCurrentState() !is Camera2State.Initial) {
                        viewModel.setInitialState()
                    }
                }
                
                ProcessingStage.SEGMENTATION,
                ProcessingStage.QUALITY_CHECKS -> {
                    if (hasActiveWarnings) {
                        // ValidationWarnings state is automatically handled by ViewModel
                        // when warnings are added
                    } else {
                        viewModel.setValidatingState()
                    }
                }
                
                ProcessingStage.COMPLETED -> {
                    viewModel.setSuccessState()
                }
            }
        }
    }
    
    /**
     * Add successfully processed image to buffer
     */
    private suspend fun addToBuffer(image: ProcessedImage) {
        synchronized(processedImageBuffer) {
            processedImageBuffer.add(image)
            
            // Remove oldest if buffer is full
            if (processedImageBuffer.size > MAX_BUFFER_SIZE) {
                processedImageBuffer.removeAt(0)
            }
            
            // Check if we have enough images for success
            if (processedImageBuffer.size >= REQUIRED_SUCCESSFUL_IMAGES) {
                currentStage = ProcessingStage.COMPLETED
                Log.d(TAG, "Successfully collected $REQUIRED_SUCCESSFUL_IMAGES images!")
            }
        }
        
        // Update ViewModel state if we reached the target
        if (processedImageBuffer.size >= REQUIRED_SUCCESSFUL_IMAGES) {
            clearWarningsForStage(ProcessingStage.COMPLETED)
            updateViewModelState()
        }
    }
    
    /**
     * Create processed image from raw image
     */
    private fun createProcessedImage(image: Image, qualityScore: Float): ProcessedImage {
        // Convert Image to Bitmap (simplified - you'd implement proper conversion)
        val bitmap = convertImageToBitmap(image)
        
        return ProcessedImage(
            bitmap = bitmap,
            timestamp = System.currentTimeMillis(),
            qualityScore = qualityScore,
            processingStage = ProcessingStage.COMPLETED
        )
    }
    
    /**
     * Convert Image to Bitmap (dummy implementation)
     */
    private fun convertImageToBitmap(image: Image): Bitmap {
        // In a real implementation, you'd properly convert YUV_420_888 to RGB
        // For now, return a dummy bitmap
        return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    }
    
    /**
     * Get warning title based on warning message
     */
    private fun getWarningTitle(warning: String): String {
        return when {
            warning.contains("lighting") -> "Poor Lighting"
            warning.contains("liveness") -> "Liveness Check"
            warning.contains("segmentation") -> "Finger Position"
            warning.contains("blur") -> "Image Blur"
            warning.contains("bright") -> "Bright Spots"
            else -> "Quality Warning"
        }
    }
    
    /**
     * Get warning icon based on warning message
     */
    private fun getWarningIcon(warning: String): Int {
        return when {
            warning.contains("lighting") -> R.drawable.ic_launcher_foreground
            warning.contains("liveness") -> R.drawable.ic_launcher_background
            warning.contains("segmentation") -> R.drawable.ic_launcher_foreground
            warning.contains("blur") -> R.drawable.ic_launcher_background
            warning.contains("bright") -> R.drawable.ic_launcher_foreground
            else -> R.drawable.ic_launcher_background
        }
    }
    
    /**
     * Clear warnings related to a specific processing stage when that stage passes
     */
    private suspend fun clearWarningsForStage(stage: ProcessingStage) {
        withContext(Dispatchers.Main) {
            when (stage) {
                ProcessingStage.INITIAL_CHECKS -> {
                    // Clear low light and liveness warnings
                    viewModel.clearWarningByTitle("Poor Lighting")
                    viewModel.clearWarningByTitle("Liveness Check")
                }
                ProcessingStage.SEGMENTATION -> {
                    // Clear segmentation warnings
                    viewModel.clearWarningByTitle("Finger Position")
                }
                ProcessingStage.QUALITY_CHECKS -> {
                    // Clear blur and bright spot warnings
                    viewModel.clearWarningByTitle("Image Blur")
                    viewModel.clearWarningByTitle("Bright Spots")
                }
                ProcessingStage.COMPLETED -> {
                    // Clear all warnings when processing is complete
                    viewModel.clearAllWarnings()
                }
            }
        }
    }
    
    /**
     * Get current processed images
     */
    fun getProcessedImages(): List<ProcessedImage> {
        return synchronized(processedImageBuffer) {
            processedImageBuffer.toList()
        }
    }
    
    /**
     * Clear processed images buffer
     */
    fun clearBuffer() {
        synchronized(processedImageBuffer) {
            processedImageBuffer.clear()
        }
        hasActiveWarnings = false
        currentStage = ProcessingStage.INITIAL_CHECKS
        
        // Update ViewModel state on Main thread
        coroutineScope.launch(Dispatchers.Main) {
            viewModel.clearAllWarnings()
            updateViewModelState()
        }
    }
    
    /**
     * Get current buffer size
     */
    fun getBufferSize(): Int {
        return synchronized(processedImageBuffer) {
            processedImageBuffer.size
        }
    }
    
    /**
     * Get current processing stage
     */
    fun getCurrentStage(): ProcessingStage {
        return currentStage
    }
    
    /**
     * Get current warnings status
     */
    fun getWarningsStatus(): String {
        return "Active warnings: $hasActiveWarnings | Current stage: $currentStage | Buffer: ${getBufferSize()}/3"
    }
}

/**
 * Data class for four values (since Triple only supports three)
 */
data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
) 
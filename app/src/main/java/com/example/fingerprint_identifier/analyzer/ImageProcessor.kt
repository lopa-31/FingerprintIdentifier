package com.example.fingerprint_identifier.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.view.TextureView
import com.example.fingerprint_identifier.data.FileRepository
import com.example.fingerprint_identifier.ui.camera.BiometricOverlayView
import com.example.fingerprint_identifier.ui.camera.Camera2State
import com.example.fingerprint_identifier.ui.camera.Camera2ViewModel
import com.example.fingerprint_identifier.ui.camera.ProcessingWarning
import com.example.fingerprint_identifier.utils.CropUtils
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
 * Callback interface for getting sensor orientation
 */
fun interface SensorOrientationCallback {
    fun getSensorOrientation(): Int
}

/**
 * Callback interface for getting device rotation
 */
fun interface DeviceRotationCallback {
    fun getDeviceRotation(): Int
}

/**
 * Image processor that handles multi-stage ML model execution
 * and integrates with Camera2ViewModel for state management
 */
class ImageProcessor(
    private val context: Context,
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
    
    // UI components for cropping
    private var overlayView: BiometricOverlayView? = null
    private var textureView: TextureView? = null
    private var sensorOrientationCallback: SensorOrientationCallback? = null
    private var deviceRotationCallback: DeviceRotationCallback? = null

    private val fileRepository = FileRepository(context)
    
    /**
     * Set the UI components needed for cropping calculations
     */
    fun setUIComponents(
        overlayView: BiometricOverlayView,
        textureView: TextureView,
        sensorOrientationCallback: SensorOrientationCallback,
        deviceRotationCallback: DeviceRotationCallback
    ) {
        this.overlayView = overlayView
        this.textureView = textureView
        this.sensorOrientationCallback = sensorOrientationCallback
        this.deviceRotationCallback = deviceRotationCallback
    }
    
    /**
     * Crop the image to the biometric overlay cutout area
     */
    private fun cropImageToOverlay(image: Image): Bitmap? {
        val overlay = overlayView ?: return null
        val texture = textureView ?: return null
        val sensorCallback = sensorOrientationCallback ?: return null
        val deviceCallback = deviceRotationCallback ?: return null
        
        // Get current orientation values
        val sensorOrientation = sensorCallback.getSensorOrientation()
        val deviceRotation = deviceCallback.getDeviceRotation()
        
        Log.d(TAG, "=== Starting crop process ===")
        Log.d(TAG, "Image dimensions: ${image.width}x${image.height}")
        Log.d(TAG, "TextureView dimensions: ${texture.width}x${texture.height}")
        Log.d(TAG, "Overlay dimensions: ${overlay.width}x${overlay.height}")
        Log.d(TAG, "Sensor orientation: $sensorOrientation degrees")
        Log.d(TAG, "Device rotation: $deviceRotation degrees")
        
        // Get the cutout rectangle in overlay's coordinate system
        val cutoutRect = overlay.getCutoutRect()
        Log.d(TAG, "Original cutout rect: $cutoutRect")
        
        // Convert cutout rect to screen coordinates
        val overlayLocation = IntArray(2)
        overlay.getLocationOnScreen(overlayLocation)
        Log.d(TAG, "Overlay location on screen: (${overlayLocation[0]}, ${overlayLocation[1]})")
        
        val screenCutoutRect = RectF(
            cutoutRect.left + overlayLocation[0],
            cutoutRect.top + overlayLocation[1],
            cutoutRect.right + overlayLocation[0],
            cutoutRect.bottom + overlayLocation[1]
        )
        
        Log.d(TAG, "Screen cutout rect: $screenCutoutRect")
        
        // Get TextureView location on screen
        val textureLocation = IntArray(2)
        texture.getLocationOnScreen(textureLocation)
        Log.d(TAG, "TextureView location on screen: (${textureLocation[0]}, ${textureLocation[1]})")
        
        // Convert screen coordinates to TextureView-relative coordinates
        val textureRelativeRect = RectF(
            screenCutoutRect.left - textureLocation[0],
            screenCutoutRect.top - textureLocation[1],
            screenCutoutRect.right - textureLocation[0],
            screenCutoutRect.bottom - textureLocation[1]
        )
        
        Log.d(TAG, "Texture-relative cutout rect: $textureRelativeRect")
        
        // Now convert TextureView coordinates to Image coordinates with proper orientation handling
        val imageCropRect = convertTextureToImageCoords(
            textureRelativeRect, 
            texture, 
            image, 
            sensorOrientation, 
            deviceRotation
        )
        if (imageCropRect == null) {
            Log.e(TAG, "Failed to convert texture coordinates to image coordinates")
            return null
        }
        
        Log.d(TAG, "Image crop rect: $imageCropRect")
        
        // Convert Image to Bitmap and crop directly
        val originalBitmap = image.toBitmap()
        if (originalBitmap == null) {
            Log.e(TAG, "Failed to convert Image to Bitmap")
            return null
        }
        
        // Clamp the crop rect to image bounds
        val clampedRect = android.graphics.Rect(
            imageCropRect.left.toInt().coerceAtLeast(0),
            imageCropRect.top.toInt().coerceAtLeast(0),
            imageCropRect.right.toInt().coerceAtMost(originalBitmap.width),
            imageCropRect.bottom.toInt().coerceAtMost(originalBitmap.height)
        )
        
        Log.d(TAG, "Clamped crop rect: $clampedRect")
        
        // Crop the bitmap
        val croppedBitmap = android.graphics.Bitmap.createBitmap(
            originalBitmap,
            clampedRect.left,
            clampedRect.top,
            clampedRect.width(),
            clampedRect.height()
        )
        
        Log.d(TAG, "Cropping successful! Result size: ${croppedBitmap.width}x${croppedBitmap.height}")
        
        // Apply rotation correction to make the image upright
        val rotationDegrees = calculateRotationDegrees(sensorOrientation, deviceRotation)
        Log.d(TAG, "Applying rotation correction: $rotationDegrees degrees")
        
        val rotatedBitmap = if (rotationDegrees != 0f) {
            rotateBitmap(croppedBitmap, rotationDegrees)
        } else {
            croppedBitmap
        }
        
        Log.d(TAG, "Final bitmap size after rotation: ${rotatedBitmap.width}x${rotatedBitmap.height}")
        Log.d(TAG, "=== Crop process complete ===")
        
        return rotatedBitmap
    }
    
    /**
     * Calculate the rotation degrees needed to make the image upright
     */
    private fun calculateRotationDegrees(sensorOrientation: Int, deviceRotation: Int): Float {
        // Calculate the total rotation needed
        // For most camera sensors, we need to rotate by (sensorOrientation - deviceRotation)
        // But this might need adjustment based on your specific camera setup
        val totalRotation = (sensorOrientation - deviceRotation + 360) % 360
        
        Log.d(TAG, "Sensor orientation: $sensorOrientation, Device rotation: $deviceRotation")
        Log.d(TAG, "Calculated rotation needed: $totalRotation degrees")
        
        return totalRotation.toFloat()
    }
    
    /**
     * Rotate a bitmap by the specified degrees
     */
    private fun rotateBitmap(bitmap: android.graphics.Bitmap, degrees: Float): android.graphics.Bitmap {
        if (degrees == 0f) return bitmap
        
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        
        return android.graphics.Bitmap.createBitmap(
            bitmap, 
            0, 
            0, 
            bitmap.width, 
            bitmap.height, 
            matrix, 
            true
        )
    }
    
    /**
     * Convert TextureView coordinates to Image coordinates with proper orientation handling
     */
    private fun convertTextureToImageCoords(
        textureRect: RectF, 
        textureView: TextureView, 
        image: Image,
        sensorOrientation: Int,
        deviceRotation: Int
    ): RectF? {
        val textureWidth = textureView.width.toFloat()
        val textureHeight = textureView.height.toFloat()
        val imageWidth = image.width.toFloat()
        val imageHeight = image.height.toFloat()
        
        Log.d(TAG, "=== Coordinate Conversion Debug ===")
        Log.d(TAG, "Image dimensions: ${imageWidth}x${imageHeight}")
        Log.d(TAG, "TextureView display size: ${textureWidth}x${textureHeight}")
        Log.d(TAG, "Input texture rect: $textureRect")
        Log.d(TAG, "Input rect size: ${textureRect.width()}x${textureRect.height()}")
        Log.d(TAG, "Sensor orientation: $sensorOrientation, Device rotation: $deviceRotation")
        
        // Calculate the total rotation needed
        val totalRotation = (sensorOrientation - deviceRotation + 360) % 360
        Log.d(TAG, "Total rotation needed: $totalRotation degrees")
        
        // Determine if we need to swap dimensions due to rotation
        val effectiveImageWidth: Float
        val effectiveImageHeight: Float
        
        when (totalRotation) {
            90, 270 -> {
                effectiveImageWidth = imageHeight
                effectiveImageHeight = imageWidth
                Log.d(TAG, "90/270 rotation - swapping dimensions: ${effectiveImageWidth}x${effectiveImageHeight}")
            }
            else -> {
                effectiveImageWidth = imageWidth
                effectiveImageHeight = imageHeight
                Log.d(TAG, "0/180 rotation - keeping dimensions: ${effectiveImageWidth}x${effectiveImageHeight}")
            }
        }
        
        // Calculate aspect ratios using effective dimensions
        val imageAspect = effectiveImageWidth / effectiveImageHeight
        val textureAspect = textureWidth / textureHeight
        
        Log.d(TAG, "Effective image aspect ratio: $imageAspect")
        Log.d(TAG, "TextureView aspect ratio: $textureAspect")
        
        // Calculate the actual scaling and positioning
        val scaleX: Float
        val scaleY: Float
        var offsetX = 0f
        var offsetY = 0f
        
        if (imageAspect > textureAspect) {
            // Image is wider than TextureView - image is scaled to fit TextureView height
            // There will be horizontal cropping (parts of image not visible)
            scaleY = textureHeight / effectiveImageHeight
            scaleX = scaleY  // Maintain aspect ratio
            offsetX = (textureWidth - effectiveImageWidth * scaleX) / 2f
            Log.d(TAG, "Image wider than texture - scale: $scaleX, offsetX: $offsetX")
        } else {
            // Image is taller than TextureView - image is scaled to fit TextureView width
            // There will be vertical cropping (parts of image not visible)
            scaleX = textureWidth / effectiveImageWidth
            scaleY = scaleX  // Maintain aspect ratio
            offsetY = (textureHeight - effectiveImageHeight * scaleY) / 2f
            Log.d(TAG, "Image taller than texture - scale: $scaleX, offsetY: $offsetY")
        }
        
        Log.d(TAG, "Final scale factors: scaleX=$scaleX, scaleY=$scaleY")
        Log.d(TAG, "Offsets: offsetX=$offsetX, offsetY=$offsetY")
        Log.d(TAG, "Effective image size in TextureView: ${effectiveImageWidth * scaleX}x${effectiveImageHeight * scaleY}")
        
        // Convert TextureView coordinates to effective image coordinates
        // Step 1: Remove the offset to get coordinates relative to the scaled image
        val relativeRect = RectF(
            textureRect.left - offsetX,
            textureRect.top - offsetY,
            textureRect.right - offsetX,
            textureRect.bottom - offsetY
        )
        
        Log.d(TAG, "After removing offset: $relativeRect")
        
        // Step 2: Scale back to effective image coordinates
        val effectiveImageRect = RectF(
            relativeRect.left / scaleX,
            relativeRect.top / scaleY,
            relativeRect.right / scaleX,
            relativeRect.bottom / scaleY
        )
        
        Log.d(TAG, "Effective image rect: $effectiveImageRect")
        
        // Step 3: Transform coordinates based on rotation to get actual image coordinates
        val imageRect = transformRectForRotation(
            effectiveImageRect, 
            effectiveImageWidth, 
            effectiveImageHeight, 
            imageWidth, 
            imageHeight, 
            totalRotation
        )
        
        Log.d(TAG, "Final image rect after rotation transform: $imageRect")
        Log.d(TAG, "Final rect size: ${imageRect.width()}x${imageRect.height()}")
        
        // Validate the result
        if (imageRect.left < 0 || imageRect.top < 0 || 
            imageRect.right > imageWidth || imageRect.bottom > imageHeight) {
            Log.w(TAG, "Crop rect extends beyond image bounds - will be clamped")
        }
        
        Log.d(TAG, "=== Coordinate Conversion Complete ===")
        return imageRect
    }
    
    /**
     * Transform rectangle coordinates based on rotation
     */
    private fun transformRectForRotation(
        rect: RectF,
        effectiveWidth: Float,
        effectiveHeight: Float,
        actualWidth: Float,
        actualHeight: Float,
        rotation: Int
    ): RectF {
        return when (rotation) {
            90 -> {
                // 90 degrees clockwise: (x,y) -> (y, effectiveWidth - x)
                RectF(
                    rect.top,
                    effectiveWidth - rect.right,
                    rect.bottom,
                    effectiveWidth - rect.left
                )
            }
            180 -> {
                // 180 degrees: (x,y) -> (effectiveWidth - x, effectiveHeight - y)
                RectF(
                    effectiveWidth - rect.right,
                    effectiveHeight - rect.bottom,
                    effectiveWidth - rect.left,
                    effectiveHeight - rect.top
                )
            }
            270 -> {
                // 270 degrees clockwise: (x,y) -> (effectiveHeight - y, x)
                RectF(
                    effectiveHeight - rect.bottom,
                    rect.left,
                    effectiveHeight - rect.top,
                    rect.right
                )
            }
            else -> {
                // 0 degrees or default - no transformation needed
                rect
            }
        }
    }
    
    /**
     * Convert Image to Bitmap
     */
    private fun Image.toBitmap(): android.graphics.Bitmap? {
        if (format != android.graphics.ImageFormat.YUV_420_888) {
            return null
        }

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, this.width, this.height), 100, out)
        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    
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
            // STEP 0: Crop the image to the biometric overlay cutout area
            val croppedBitmap = cropImageToOverlay(image)
            if (croppedBitmap == null) {
                Log.w(TAG, "Image #$processingId failed to crop - skipping processing")
                return
            }
            
            // Save only the cropped image for processing
            fileRepository.saveBitmapAndGetUri(croppedBitmap, captureName = "Cropped_Image_${processingId}")
            
            Log.d(TAG, "Image #$processingId successfully cropped to ${croppedBitmap.width}x${croppedBitmap.height}")
            
            // For now, we'll stop here and not send for further processing
            // The cropped bitmap is ready for future ML model processing
            
            // TODO: Continue with ML model processing on croppedBitmap
            // Stage 1: Initial checks (Low light + Liveness) - Run in parallel
            // Stage 2: Segmentation check
            // Stage 3: Quality checks (Blur + Bright spot) - Run in parallel
            
            Log.d(TAG, "Image #$processingId cropping completed - stopping here as requested")


            
        } catch (e: Exception) {
            Log.e(TAG, "Error in processing pipeline", e)
        } finally {
            isProcessing.set(false)
        }
    }
    
    /**
     * Stage 1: Run low light and liveness checks in parallel on background threads
     */
    private suspend fun performInitialChecks(image: Image): Pair<Boolean, List<ProcessingWarning>> {
        val warnings = mutableListOf<ProcessingWarning>()
        
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
        if (!lowLightResult.passed) warnings.add(ProcessingWarning.PoorLighting)
        if (!livenessResult.passed) warnings.add(ProcessingWarning.LivenessCheckFailed)
        
        return Pair(warnings.isEmpty(), warnings)
    }
    
    /**
     * Stage 3: Run blur and bright spot checks in parallel on background threads
     */
    private suspend fun performQualityChecks(image: Image): Triple<Boolean, List<ProcessingWarning>, Float> {
        val warnings = mutableListOf<ProcessingWarning>()
        
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
        if (!blurResult.passed) warnings.add(ProcessingWarning.ImageBlurry)
        if (!brightSpotResult.passed) warnings.add(ProcessingWarning.BrightSpotsDetected)
        
        // Calculate overall quality score
        val qualityScore = (blurResult.confidence + brightSpotResult.confidence) / 2
        
        return Triple(warnings.isEmpty(), warnings, qualityScore)
    }
    
    /**
     * Handle processing failure and update state with warnings
     */
    private suspend fun handleProcessingFailure(warnings: List<ProcessingWarning>) {
        if (warnings.isNotEmpty()) {
            // Add warnings to ViewModel on Main thread
            withContext(Dispatchers.Main) {
                warnings.forEach { warning ->
                    viewModel.addWarning(warning)
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
                        viewModel.setValidatingState()
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
                
                viewModel.clearAllWarnings()

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
     * Get warning object based on warning message
     */
    private fun getWarningObject(warning: String): ProcessingWarning {
        return when {
            warning.contains("lighting") -> ProcessingWarning.PoorLighting
            warning.contains("liveness") -> ProcessingWarning.LivenessCheckFailed
            warning.contains("segmentation") -> ProcessingWarning.FingerNotDetected
            warning.contains("blur") -> ProcessingWarning.ImageBlurry
            warning.contains("bright") -> ProcessingWarning.BrightSpotsDetected
            else -> ProcessingWarning.ImageBlurry // Fallback
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
                    viewModel.removeWarning(ProcessingWarning.PoorLighting)
                    viewModel.removeWarning(ProcessingWarning.LivenessCheckFailed)
                }
                ProcessingStage.SEGMENTATION -> {
                    // Clear segmentation warnings
                    viewModel.removeWarning(ProcessingWarning.FingerNotDetected)
                }
                ProcessingStage.QUALITY_CHECKS -> {
                    // Clear blur and bright spot warnings
                    viewModel.removeWarning(ProcessingWarning.ImageBlurry)
                    viewModel.removeWarning(ProcessingWarning.BrightSpotsDetected)
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
        return "Current stage: $currentStage | Buffer: ${getBufferSize()}/3"
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
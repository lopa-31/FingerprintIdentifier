import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AutoMacroFocusManager(
    private val cameraDevice: CameraDevice,
    private val captureSession: CameraCaptureSession,
    private val previewRequestBuilder: CaptureRequest.Builder,
    private val backgroundHandler: Handler
) {
    companion object {
        private const val TAG = "AutoMacroFocus"
        
        // Focus distance thresholds (in diopters)
        private const val CLOSE_OBJECT_THRESHOLD = 2.0f  // ~50cm
        private const val MACRO_RANGE_THRESHOLD = 5.0f   // ~20cm
        
        // Contrast analysis
        private const val CONTRAST_ANALYSIS_INTERVAL = 100L // ms
        private const val FOCUS_SWEEP_STEP = 0.1f
        private const val MIN_CONTRAST_IMPROVEMENT = 0.15f
        
        // Focus lock timing
        private const val FOCUS_LOCK_DELAY = 300L // ms
        private const val FOCUS_STABILITY_CHECKS = 3
    }
    
    // Focus state tracking
    private var currentFocusDistance = 0f
    private var previousFocusDistance = 0f
    private var isAutoFocusing = false
    private var focusSweepDirection = 1 // 1 for closer, -1 for farther
    
    // Contrast tracking for focus quality
    private var contrastHistory = mutableListOf<Float>()
    private var bestContrastDistance = 0f
    private var bestContrastValue = 0f
    private var focusStabilityCount = 0
    
    // Focus range limits
    private var minFocusDistance = 0f
    private var maxFocusDistance = 0f
    
    // Callbacks
    private var focusListener: AutoFocusListener? = null
    
    // Handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    private val contrastAnalysisRunnable = object : Runnable {
        override fun run() {
            analyzeContrastAndAdjustFocus()
            backgroundHandler.postDelayed(this, CONTRAST_ANALYSIS_INTERVAL)
        }
    }
    
    fun initialize(characteristics: CameraCharacteristics) {
        // Get focus distance range
        val focusRange = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        maxFocusDistance = focusRange ?: 10f
        minFocusDistance = 0f
        
        Log.d(TAG, "Focus range: $minFocusDistance - $maxFocusDistance diopters")
        
        // Setup continuous autofocus
        setupContinuousAutofocus()
        
        // Start contrast monitoring
        startContrastMonitoring()
    }
    
    private fun setupContinuousAutofocus() {
        try {
            // Enable continuous autofocus
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            
            // Enable focus distance reporting
            previewRequestBuilder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                currentFocusDistance
            )
            
            // Set metering areas for center focus (good for close objects)
            val centerMeteringRect = MeteringRectangle(
                -100, -100, 200, 200, MeteringRectangle.METERING_WEIGHT_MAX
            )
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_REGIONS,
                arrayOf(centerMeteringRect)
            )
            
            // Update capture session
            val previewRequest = previewRequestBuilder.build()
            captureSession.setRepeatingRequest(
                previewRequest,
                autofocusCaptureCallback,
                backgroundHandler
            )
            
            Log.d(TAG, "Continuous autofocus initialized")
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to setup continuous autofocus", e)
        }
    }
    
    private val autofocusCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            
            // Get current focus state and distance
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            
            updateFocusState(afState, focusDistance)
            detectCloseObjectAndFocus(focusDistance)
        }
    }
    
    private fun updateFocusState(afState: Int?, focusDistance: Float) {
        previousFocusDistance = currentFocusDistance
        currentFocusDistance = focusDistance
        
        when (afState) {
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> {
                Log.d(TAG, "AF scanning passively at distance: $focusDistance")
            }
            
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> {
                Log.d(TAG, "AF passive focused at distance: $focusDistance")
                if (isCloseObject(focusDistance)) {
                    onCloseObjectFocused(focusDistance)
                }
            }
            
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> {
                Log.d(TAG, "AF passive unfocused at distance: $focusDistance")
                if (isCloseObject(focusDistance)) {
                    triggerMacroFocusSweep()
                }
            }
        }
    }
    
    private fun detectCloseObjectAndFocus(focusDistance: Float) {
        // Detect if a close object has entered the scene
        val distanceChange = abs(focusDistance - previousFocusDistance)
        
        if (distanceChange > 1.0f && isCloseObject(focusDistance) && !isAutoFocusing) {
            Log.d(TAG, "Close object detected at distance: $focusDistance")
            triggerMacroFocusSweep()
        }
    }
    
    private fun isCloseObject(focusDistance: Float): Boolean {
        return focusDistance >= CLOSE_OBJECT_THRESHOLD
    }
    
    private fun isMacroRange(focusDistance: Float): Boolean {
        return focusDistance >= MACRO_RANGE_THRESHOLD
    }
    
    private fun triggerMacroFocusSweep() {
        if (isAutoFocusing) return
        
        Log.d(TAG, "Starting macro focus sweep")
        isAutoFocusing = true
        contrastHistory.clear()
        bestContrastValue = 0f
        focusStabilityCount = 0
        
        // Start from current position and sweep toward optimal macro distance
        focusSweepDirection = if (currentFocusDistance < MACRO_RANGE_THRESHOLD) 1 else -1
        
        focusListener?.onMacroFocusStarted()
        
        // Begin focus sweep
        performFocusSweepStep()
    }
    
    private fun performFocusSweepStep() {
        if (!isAutoFocusing) return
        
        // Calculate next focus distance
        val nextDistance = currentFocusDistance + (FOCUS_SWEEP_STEP * focusSweepDirection)
        
        // Check bounds
        if (nextDistance > maxFocusDistance || nextDistance < minFocusDistance) {
            // Reverse direction or complete sweep
            if (focusSweepDirection == 1) {
                focusSweepDirection = -1
                performFocusSweepStep()
            } else {
                completeFocusSweep()
            }
            return
        }
        
        // Set new focus distance
        setManualFocus(nextDistance)
        
        // Schedule next step
        backgroundHandler.postDelayed({
            performFocusSweepStep()
        }, 50) // 50ms between steps for smooth sweep
    }
    
    private fun setManualFocus(focusDistance: Float) {
        try {
            // Switch to manual focus for precise control
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            
            previewRequestBuilder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistance
            )
            
            val request = previewRequestBuilder.build()
            captureSession.capture(request, null, backgroundHandler)
            
            Log.v(TAG, "Manual focus set to: $focusDistance")
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to set manual focus", e)
        }
    }
    
    private fun startContrastMonitoring() {
        backgroundHandler.post(contrastAnalysisRunnable)
    }
    
    private fun stopContrastMonitoring() {
        backgroundHandler.removeCallbacks(contrastAnalysisRunnable)
    }
    
    private fun analyzeContrastAndAdjustFocus() {
        if (!isAutoFocusing) return
        
        // This is a simplified contrast analysis
        // In practice, you'd analyze the actual image data for edge detection
        val simulatedContrast = calculateSimulatedContrast()
        
        contrastHistory.add(simulatedContrast)
        
        // Keep only recent history
        if (contrastHistory.size > 10) {
            contrastHistory.removeAt(0)
        }
        
        // Check if this is the best contrast so far
        if (simulatedContrast > bestContrastValue) {
            bestContrastValue = simulatedContrast
            bestContrastDistance = currentFocusDistance
            focusStabilityCount = 0
            Log.d(TAG, "New best contrast: $simulatedContrast at distance: $currentFocusDistance")
        } else {
            focusStabilityCount++
        }
        
        // If contrast hasn't improved for several frames, lock focus
        if (focusStabilityCount >= FOCUS_STABILITY_CHECKS) {
            lockOptimalFocus()
        }
    }
    
    private fun calculateSimulatedContrast(): Float {
        // Simplified contrast simulation based on distance from optimal macro range
        val optimalDistance = (MACRO_RANGE_THRESHOLD + CLOSE_OBJECT_THRESHOLD) / 2
        val distanceFromOptimal = abs(currentFocusDistance - optimalDistance)
        
        // Higher contrast when closer to optimal distance
        return max(0f, 1f - (distanceFromOptimal / optimalDistance))
    }
    
    private fun lockOptimalFocus() {
        if (!isAutoFocusing) return
        
        Log.d(TAG, "Locking focus at optimal distance: $bestContrastDistance")
        
        // Set focus to best contrast position
        setManualFocus(bestContrastDistance)
        
        // Wait for focus to settle, then complete
        backgroundHandler.postDelayed({
            completeFocusSweep()
        }, FOCUS_LOCK_DELAY)
    }
    
    private fun completeFocusSweep() {
        isAutoFocusing = false
        
        Log.d(TAG, "Macro focus sweep completed at distance: $bestContrastDistance")
        
        focusListener?.onMacroFocusCompleted(bestContrastDistance, bestContrastValue)
        
        // Hold manual focus for a moment, then return to continuous AF
        backgroundHandler.postDelayed({
            returnToContinuousAutofocus()
        }, 2000) // Hold for 2 seconds
    }
    
    private fun returnToContinuousAutofocus() {
        try {
            Log.d(TAG, "Returning to continuous autofocus")
            
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            
            // Remove manual focus distance
            previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null as Float?)
            
            val request = previewRequestBuilder.build()
            captureSession.setRepeatingRequest(request, autofocusCaptureCallback, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to return to continuous AF", e)
        }
    }
    
    private fun onCloseObjectFocused(focusDistance: Float) {
        Log.d(TAG, "Close object focused at distance: $focusDistance")
        focusListener?.onCloseObjectDetected(focusDistance)
    }
    
    // Public methods
    
    fun forceCloseObjectFocus() {
        Log.d(TAG, "Force triggering close object focus")
        triggerMacroFocusSweep()
    }
    
    fun setAutoFocusListener(listener: AutoFocusListener) {
        this.focusListener = listener
    }
    
    fun pause() {
        stopContrastMonitoring()
        isAutoFocusing = false
    }
    
    fun resume() {
        startContrastMonitoring()
        setupContinuousAutofocus()
    }
    
    fun cleanup() {
        stopContrastMonitoring()
        focusListener = null
    }
    
    // Callback interface
    interface AutoFocusListener {
        fun onCloseObjectDetected(focusDistance: Float)
        fun onMacroFocusStarted()
        fun onMacroFocusCompleted(finalDistance: Float, contrastScore: Float)
        fun onFocusLocked()
    }
}
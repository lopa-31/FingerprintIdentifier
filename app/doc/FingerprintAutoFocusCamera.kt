import android.hardware.camera2.*
import android.view.Surface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class FingerprintAutoFocusCamera(
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "FingerprintAutoFocus"
        private const val CAMERA_THREAD_NAME = "FingerprintCamera"
    }
    
    // Camera components
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    
    // Background thread
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Auto focus manager
    private var autoFocusManager: AutoMacroFocusManager? = null
    
    // Surfaces
    private var previewSurface: Surface? = null
    private var captureSurface: Surface? = null
    
    // Callbacks
    private var fingerprintFocusListener: FingerprintFocusListener? = null
    
    fun initializeCamera(
        cameraManager: CameraManager,
        previewSurface: Surface,
        captureSurface: Surface
    ) {
        this.cameraManager = cameraManager
        this.previewSurface = previewSurface
        this.captureSurface = captureSurface
        
        startBackgroundThread()
        openCamera()
    }
    
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(CAMERA_THREAD_NAME).apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }
    
    private fun openCamera() {
        try {
            val cameraId = selectBestCameraForFingerprint()
            cameraManager?.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }
    
    private fun selectBestCameraForFingerprint(): String {
        val cameraIds = cameraManager?.cameraIdList ?: emptyArray()
        
        for (cameraId in cameraIds) {
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
            val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
            
            // Prefer back camera for fingerprint scanning
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                
                // Ensure camera supports macro focus
                if (minFocusDistance != null && minFocusDistance > 0) {
                    Log.d(TAG, "Selected camera $cameraId with macro capability: $minFocusDistance")
                    return cameraId
                }
            }
        }
        
        // Fallback to first available camera
        return cameraIds.firstOrNull() ?: "0"
    }
    
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened successfully")
            cameraDevice = camera
            createCaptureSession()
        }
        
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }
        
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }
    
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val preview = previewSurface ?: return
        val capture = captureSurface ?: return
        
        try {
            // Create preview request builder
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                
                // Optimize for fingerprint scanning
                setupFingerprintOptimization()
            }
            
            // Create capture session
            camera.createCaptureSession(
                listOf(preview, capture),
                captureSessionCallback,
                backgroundHandler
            )
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }
    
    private fun CaptureRequest.Builder.setupFingerprintOptimization() {
        // Enable flash for better fingerprint visibility
        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        
        // Optimize exposure for close-up scanning
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        
        // Disable face detection to focus on fingerprints
        set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
        
        // Enable edge enhancement for sharper fingerprint details
        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        
        // Reduce noise for cleaner fingerprint capture
        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    }
    
    private val captureSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            Log.d(TAG, "Capture session configured")
            captureSession = session
            
            val camera = cameraDevice ?: return
            val builder = previewRequestBuilder ?: return
            
            // Initialize auto macro focus manager
            initializeAutoFocusManager(camera, session, builder)
            
            // Start preview
            startPreview()
        }
        
        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Capture session configuration failed")
        }
    }
    
    private fun initializeAutoFocusManager(
        camera: CameraDevice,
        session: CameraCaptureSession,
        builder: CaptureRequest.Builder
    ) {
        val handler = backgroundHandler ?: return
        
        autoFocusManager = AutoMacroFocusManager(camera, session, builder, handler).apply {
            // Get camera characteristics for focus setup
            val cameraId = camera.id
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
            
            if (characteristics != null) {
                initialize(characteristics)
            }
            
            // Set up focus listener for fingerprint scanning
            setAutoFocusListener(object : AutoMacroFocusManager.AutoFocusListener {
                override fun onCloseObjectDetected(focusDistance: Float) {
                    Log.d(TAG, "Close object (finger) detected at $focusDistance diopters")
                    
                    lifecycleOwner.lifecycleScope.launch {
                        fingerprintFocusListener?.onFingerDetected(focusDistance)
                    }
                }
                
                override fun onMacroFocusStarted() {
                    Log.d(TAG, "Macro focus started for fingerprint")
                    
                    lifecycleOwner.lifecycleScope.launch {
                        fingerprintFocusListener?.onFingerprintFocusStarted()
                    }
                }
                
                override fun onMacroFocusCompleted(finalDistance: Float, contrastScore: Float) {
                    Log.d(TAG, "Macro focus completed - Distance: $finalDistance, Contrast: $contrastScore")
                    
                    lifecycleOwner.lifecycleScope.launch {
                        fingerprintFocusListener?.onFingerprintFocusCompleted(finalDistance, contrastScore)
                        
                        // If focus quality is good, trigger capture
                        if (contrastScore > 0.7f) {
                            captureFingerprint()
                        }
                    }
                }
                
                override fun onFocusLocked() {
                    Log.d(TAG, "Focus locked on fingerprint")
                    
                    lifecycleOwner.lifecycleScope.launch {
                        fingerprintFocusListener?.onFingerprintReadyForCapture()
                    }
                }
            })
        }
    }
    
    private fun startPreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = backgroundHandler ?: return
        
        try {
            val previewRequest = builder.build()
            session.setRepeatingRequest(previewRequest, null, handler)
            Log.d(TAG, "Preview started with auto macro focus")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }
    
    private fun captureFingerprint() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val captureSurface = this.captureSurface ?: return
        val handler = backgroundHandler ?: return
        
        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(captureSurface)
                
                // Use same settings as preview for consistent quality
                copySettingsFromPreview(previewRequestBuilder)
                
                // Ensure flash is on for capture
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
            }
            
            session.capture(
                captureBuilder.build(),
                fingerprintCaptureCallback,
                handler
            )
            
            Log.d(TAG, "Fingerprint capture triggered")
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to capture fingerprint", e)
        }
    }
    
    private fun CaptureRequest.Builder.copySettingsFromPreview(previewBuilder: CaptureRequest.Builder?) {
        previewBuilder?.let { preview ->
            // Copy relevant settings from preview
            val keys = listOf(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.LENS_FOCUS_DISTANCE,
                CaptureRequest.EDGE_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE
            )
            
            keys.forEach { key ->
                val value = preview.get(key)
                if (value != null) {
                    set(key, value)
                }
            }
        }
    }
    
    private val fingerprintCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            Log.d(TAG, "Fingerprint capture completed")
            
            lifecycleOwner.lifecycleScope.launch {
                fingerprintFocusListener?.onFingerprintCaptured()
            }
        }
        
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "Fingerprint capture failed: ${failure.reason}")
            
            lifecycleOwner.lifecycleScope.launch {
                fingerprintFocusListener?.onFingerprintCaptureFailed()
            }
        }
    }
    
    // Public methods
    
    fun forceFingerprintFocus() {
        autoFocusManager?.forceCloseObjectFocus()
    }
    
    fun setFingerprintFocusListener(listener: FingerprintFocusListener) {
        this.fingerprintFocusListener = listener
    }
    
    fun pauseAutoFocus() {
        autoFocusManager?.pause()
    }
    
    fun resumeAutoFocus() {
        autoFocusManager?.resume()
    }
    
    fun closeCamera() {
        autoFocusManager?.cleanup()
        
        captureSession?.close()
        captureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        stopBackgroundThread()
        
        Log.d(TAG, "Camera closed")
    }
    
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread interrupted", e)
        }
    }
    
    // Callback interface for fingerprint focus events
    interface FingerprintFocusListener {
        fun onFingerDetected(focusDistance: Float)
        fun onFingerprintFocusStarted()
        fun onFingerprintFocusCompleted(distance: Float, quality: Float)
        fun onFingerprintReadyForCapture()
        fun onFingerprintCaptured()
        fun onFingerprintCaptureFailed()
    }
}

// Usage example in Activity/Fragment
/*
class FingerprintScanActivity : AppCompatActivity() {
    private lateinit var fingerprintCamera: FingerprintAutoFocusCamera
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        fingerprintCamera = FingerprintAutoFocusCamera(this)
        
        fingerprintCamera.setFingerprintFocusListener(object : FingerprintAutoFocusCamera.FingerprintFocusListener {
            override fun onFingerDetected(focusDistance: Float) {
                statusText.text = "Finger detected - focusing..."
            }
            
            override fun onFingerprintFocusStarted() {
                statusText.text = "Auto-focusing on fingerprint..."
                showFocusIndicator(true)
            }
            
            override fun onFingerprintFocusCompleted(distance: Float, quality: Float) {
                statusText.text = "Focus complete - Quality: ${String.format("%.2f", quality)}"
                if (quality > 0.7f) {
                    statusText.append(" - Capturing...")
                }
            }
            
            override fun onFingerprintReadyForCapture() {
                statusText.text = "Ready for capture!"
                showFocusIndicator(false)
            }
            
            override fun onFingerprintCaptured() {
                statusText.text = "Fingerprint captured successfully!"
            }
            
            override fun onFingerprintCaptureFailed() {
                statusText.text = "Capture failed - please try again"
            }
        })
    }
}
*/
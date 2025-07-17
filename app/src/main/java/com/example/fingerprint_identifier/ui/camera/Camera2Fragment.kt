package com.example.fingerprint_identifier.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.fingerprint_identifier.R
import com.example.fingerprint_identifier.databinding.FragmentCamera2Binding
import com.example.fingerprint_identifier.ui.base.BaseFragment
import com.example.fingerprint_identifier.analyzer.ImageProcessor
import com.example.fingerprint_identifier.analyzer.SensorOrientationCallback
import com.example.fingerprint_identifier.analyzer.DeviceRotationCallback
import com.example.fingerprint_identifier.utils.CropUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

class Camera2Fragment : BaseFragment() {

    private var _binding: FragmentCamera2Binding? = null
    private val binding get() = _binding!!

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    
    private lateinit var cameraId: String
    private lateinit var previewSize: Size
    private lateinit var cameraCharacteristics: CameraCharacteristics
    
    // Focus-related properties
    private var currentFocusDistance: Float = 0f
    private var focalLength: Float = 0f
    private var minimumFocusDistance: Float = 0f
    private var hyperfocalDistance: Float = 0f
    
    // UI components
    private lateinit var biometricOverlay: BiometricOverlayView
    private lateinit var infoBottomSheet: InfoBottomSheetView
    
    // ViewModel
    private val camera2ViewModel: Camera2ViewModel by viewModels()
    
    // Image processor
    private lateinit var imageProcessor: ImageProcessor
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
            activity?.supportFragmentManager?.popBackStack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCamera2Binding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize UI components
        biometricOverlay = binding.biometricOverlay
        infoBottomSheet = binding.infoBottomSheet
        
        cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupCameraInfo()
        
        // Initialize image processor
        imageProcessor = ImageProcessor(requireContext(), camera2ViewModel, viewLifecycleOwner.lifecycleScope)
        
        // Set UI components for cropping with callback functions
        imageProcessor.setUIComponents(
            overlayView = biometricOverlay,
            textureView = binding.textureView,
            sensorOrientationCallback = SensorOrientationCallback { getSensorOrientation() },
            deviceRotationCallback = DeviceRotationCallback { getDeviceRotation() }
        )
        
        setupClickListeners()
        observeViewModel()
        
        // Set initial state
        camera2ViewModel.setInitialState()
    }
    
    /**
     * Get the current sensor orientation from camera characteristics
     */
    private fun getSensorOrientation(): Int {
        return try {
            if (::cameraCharacteristics.isInitialized) {
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } else {
                Log.w("Camera2Fragment", "Camera characteristics not initialized, returning 0")
                0
            }
        } catch (e: Exception) {
            Log.e("Camera2Fragment", "Failed to get sensor orientation", e)
            0
        }
    }
    
    /**
     * Get the current device rotation
     */
    private fun getDeviceRotation(): Int {
        return try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireContext().display
            } else {
                @Suppress("DEPRECATION")
                requireActivity().windowManager.defaultDisplay
            }
            
            val rotation = display?.rotation ?: Surface.ROTATION_0
            
            // Convert Surface rotation constants to degrees
            when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.e("Camera2Fragment", "Failed to get device rotation", e)
            0
        }
    }

    private fun setupClickListeners() {
        binding.captureButton.setOnClickListener {
            capturePhoto()
        }
        
        // Trigger autofocus when TextureView is tapped
        binding.textureView.setOnClickListener {
            triggerAutoFocus()
        }
        
        // State test buttons
        binding.btnInitial.setOnClickListener {
            camera2ViewModel.setInitialState()
        }
        
        binding.btnValidating.setOnClickListener {
            camera2ViewModel.setValidatingState()
        }
        
        binding.btnSuccess.setOnClickListener {
            camera2ViewModel.setSuccessState()
        }
        
        // Warning management buttons
        binding.btnAddWarning.setOnClickListener {
            addRandomWarning()
        }
        
        binding.btnClearWarning.setOnClickListener {
            camera2ViewModel.warnings.value.firstOrNull()?.let {
                camera2ViewModel.removeWarning(it)
            }
        }
        
        binding.btnClearAll.setOnClickListener {
            camera2ViewModel.clearAllWarnings()
            imageProcessor.clearBuffer()
        }
        
        // Add clear buffer button functionality
        binding.btnClearWarning.setOnClickListener {
            camera2ViewModel.warnings.value.firstOrNull()?.let {
                camera2ViewModel.removeWarning(it)
            }
            // Also clear buffer to restart process
            imageProcessor.clearBuffer()
        }
    }
    
    private fun addRandomWarning() {
        val warnings = listOf(
            ProcessingWarning.PoorLighting,
            ProcessingWarning.LivenessCheckFailed,
            ProcessingWarning.FingerNotDetected,
            ProcessingWarning.ImageBlurry,
            ProcessingWarning.BrightSpotsDetected
        )
        
        val randomWarning = warnings.random()
        camera2ViewModel.addWarning(randomWarning)
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            camera2ViewModel.camera2State.collectLatest { state ->
                updateUIForState(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            camera2ViewModel.warnings.collectLatest { warnings ->
                updateUIForWarnings(warnings)
            }
        }
    }
    
    private fun updateUIForState(state: Camera2State) {
        // Clear any persistent animations or states first
        biometricOverlay.setAnimationEnabled(false)

        when (state) {
            is Camera2State.Initial -> {
                // White, Dashed, No animation
                biometricOverlay.setColor(Color.WHITE)
                biometricOverlay.setStyle(BiometricOverlayView.OverlayStyle.DASHED)
                binding.statusText.text = "Camera2 Preview - Initial (${getBufferSize()}/3 images)"
                Log.d("Camera2Fragment", "State: Initial")
            }
            
            is Camera2State.Validating -> {
                // Amber, Dashed, With animation
                biometricOverlay.setColor(Color.parseColor("#FFC107")) // Amber
                biometricOverlay.setStyle(BiometricOverlayView.OverlayStyle.DASHED)
                biometricOverlay.setAnimationEnabled(true)
                binding.statusText.text = "Validating... (${getBufferSize()}/3 images)"
                Log.d("Camera2Fragment", "State: Validating")
            }
            
            is Camera2State.Success -> {
                // Green, Solid, No animation
                biometricOverlay.setColor(Color.parseColor("#4CAF50")) // Green
                biometricOverlay.setStyle(BiometricOverlayView.OverlayStyle.SOLID)
                binding.statusText.text = "Success! (${getBufferSize()}/3 images captured)"
                Log.d("Camera2Fragment", "State: Success")
            }
        }
    }

    private fun updateUIForWarnings(warnings: List<ProcessingWarning>) {
        if (warnings.isNotEmpty()) {
            // Get the highest-priority warning
            val currentWarning = warnings.first()
            val totalWarnings = warnings.size

            // Orange, Dashed, with animation when warnings are present
            biometricOverlay.setColor(Color.parseColor("#FF9800")) // Orange
            biometricOverlay.setStyle(BiometricOverlayView.OverlayStyle.DASHED)
            biometricOverlay.setAnimationEnabled(true)

            val warningTitle = if (totalWarnings > 1) {
                "${currentWarning.title} ($totalWarnings warnings)"
            } else {
                currentWarning.title
            }

            infoBottomSheet.updateContent(
                warningTitle,
                currentWarning.description,
                currentWarning.imageRes
            )
            infoBottomSheet.show()

            val priorityStage = camera2ViewModel.getCurrentPriorityStage()
            binding.statusText.text = "Warning: ${currentWarning.title} [Stage $priorityStage] (${getBufferSize()}/3 images)"

            Log.d("Camera2Fragment", "Warnings present: ${currentWarning.title} [Stage $priorityStage] ($totalWarnings total)")
            Log.d("Camera2Fragment", "Warning breakdown: ${getWarningBreakdown()}")
        } else {
            // No warnings, hide the bottom sheet
            infoBottomSheet.hide()

            // If the state is not 'Success', revert overlay to its base state color
            val currentState = camera2ViewModel.camera2State.value
            if (currentState !is Camera2State.Success) {
                biometricOverlay.setColor(if (currentState is Camera2State.Initial) Color.WHITE else Color.parseColor("#FFC107"))
            }
        }
    }

    /**
     * Calculate and update crop rectangle for image processing
     */
    private fun updateCropRect() {
        // Only calculate if views are laid out and we have camera info
        if (binding.textureView.width > 0 && binding.textureView.height > 0 && 
            biometricOverlay.width > 0 && biometricOverlay.height > 0) {
            
            // We'll set the crop rect on the ImageProcessor when we have an image
            // This is just a placeholder to ensure views are ready
            Log.d("Camera2Fragment", "Views are ready for crop calculation")
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        
        if (binding.textureView.isAvailable) {
            openCamera()
        } else {
            binding.textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
        stopBackgroundThread()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupCameraInfo() {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    cameraCharacteristics = characteristics
                    
                    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    previewSize = map?.getOutputSizes(SurfaceTexture::class.java)?.get(0) ?: Size(1920, 1080)
                    
                    // Extract focal length and focus distance information
                    extractFocalLengthInfo(characteristics)
                    
                    break
                }
            }
        } catch (e: CameraAccessException) {
            Log.e("Camera2Fragment", "Failed to get camera info", e)
        }
    }

    private fun extractFocalLengthInfo(characteristics: CameraCharacteristics) {
        // Get focal length (in mm)
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        focalLength = focalLengths?.get(0) ?: 0f
        
        // Get minimum focus distance (in diopters - 1/meters)
        minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        
        // Get hyperfocal distance (in diopters)
        hyperfocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
        
        Log.d("Camera2Fragment", "Focal Length: ${focalLength}mm")
        Log.d("Camera2Fragment", "Minimum Focus Distance: ${diopterToDistance(minimumFocusDistance)}m")
        Log.d("Camera2Fragment", "Hyperfocal Distance: ${diopterToDistance(hyperfocalDistance)}m")
    }

    /**
     * Convert diopter value to distance in meters
     * Diopter = 1/distance_in_meters
     */
    private fun diopterToDistance(diopter: Float): Float {
        return if (diopter > 0) 1.0f / diopter else Float.POSITIVE_INFINITY
    }

    /**
     * Convert distance in meters to diopter
     */
    private fun distanceToDiopter(distance: Float): Float {
        return if (distance > 0) 1.0f / distance else 0f
    }

    /**
     * Calculate optimal focus distance for a subject at given distance
     */
    private fun calculateOptimalFocusDistance(subjectDistance: Float): Float {
        // For optimal focus, the focus distance should equal the subject distance
        // But we need to respect the camera's minimum focus distance
        val minDistance = diopterToDistance(minimumFocusDistance)
        
        return when {
            subjectDistance < minDistance -> minDistance
            subjectDistance > diopterToDistance(hyperfocalDistance) -> diopterToDistance(hyperfocalDistance)
            else -> subjectDistance
        }
    }

    /**
     * Calculate depth of field for current focus settings
     */
    private fun calculateDepthOfField(focusDistance: Float, aperture: Float = 2.8f): Pair<Float, Float> {
        val focalLengthM = focalLength / 1000f // Convert mm to meters
        val circleOfConfusion = 0.00003f // Typical value for smartphone cameras
        
        val hyperfocalDist = (focalLengthM * focalLengthM) / (aperture * circleOfConfusion)
        
        val nearDistance = (hyperfocalDist * focusDistance) / (hyperfocalDist + focusDistance)
        val farDistance = (hyperfocalDist * focusDistance) / (hyperfocalDist - focusDistance)
        
        return Pair(nearDistance, if (farDistance < 0) Float.POSITIVE_INFINITY else farDistance)
    }

    /**
     * Get current camera focus information
     */
    @SuppressLint("DefaultLocale")
    private fun getFocusInfo(): String {
        val focusDistanceM = diopterToDistance(currentFocusDistance)
        val (nearFocus, farFocus) = calculateDepthOfField(focusDistanceM)
        
        return """
            Focal Length: ${focalLength}mm
            Current Focus Distance: ${String.format("%.2f", focusDistanceM)}m
            Depth of Field: ${String.format("%.2f", nearFocus)}m - ${
                if (farFocus.isInfinite()) "∞" else String.format("%.2f", farFocus)
            }m
            Min Focus Distance: ${String.format("%.2f", diopterToDistance(minimumFocusDistance))}m
            Hyperfocal Distance: ${String.format("%.2f", diopterToDistance(hyperfocalDistance))}m
        """.trimIndent()
    }

    

    private fun triggerAutoFocus() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        
        try {
            val texture = binding.textureView.surfaceTexture
            val surface = Surface(texture)
            
            // Simple autofocus request
            val focusBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            focusBuilder.addTarget(surface)
            imageReader?.surface?.let { focusBuilder.addTarget(it) }
            
            // Set autofocus mode and trigger
            focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            
            // Trigger autofocus
            session.capture(focusBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d("Camera2Fragment", "Autofocus triggered")
                    
                    // Update focus distance if available
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { distance ->
                        currentFocusDistance = distance
                        Log.d("Camera2Fragment", "Focus distance: ${diopterToDistance(distance)}m")
                    }
                }
            }, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            Log.e("Camera2Fragment", "Failed to trigger autofocus", e)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e("Camera2Fragment", "Background thread interrupted", e)
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        try {
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("Camera2Fragment", "Failed to open camera", e)
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun onOpened(camera: CameraDevice) {
            Log.d("Camera2Fragment", "Camera opened successfully")
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d("Camera2Fragment", "Camera disconnected")
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e("Camera2Fragment", "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
            updateCropRect()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            updateCropRect()
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        
        try {
            val texture = binding.textureView.surfaceTexture
            texture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)

            // Setup ImageReader for processing frames
            imageReader = ImageReader.newInstance(
                previewSize.width, 
                previewSize.height, 
                ImageFormat.YUV_420_888, 
                2
            )
            
            imageReader?.setOnImageAvailableListener(imageProcessor, backgroundHandler)

            val outputs = listOf(
                OutputConfiguration(surface),
                OutputConfiguration(imageReader!!.surface)
            )

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                Executors.newSingleThreadExecutor(),
                captureSessionCallback
            )

            device.createCaptureSession(sessionConfig)
            
        } catch (e: CameraAccessException) {
            Log.e("Camera2Fragment", "Failed to create preview session", e)
        }
    }

    private val captureSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            Log.d("Camera2Fragment", "Capture session configured")
            captureSession = session
            startPreview()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e("Camera2Fragment", "Capture session configuration failed")
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return

        try {
            val texture = binding.textureView.surfaceTexture
            val surface = Surface(texture)

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            requestBuilder.addTarget(imageReader!!.surface)
            
            // Enable continuous autofocus by default
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            session.setRepeatingRequest(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    
                    // Update focus distance continuously
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { distance ->
                        currentFocusDistance = distance
                    }
                }
            }, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            Log.e("Camera2Fragment", "Failed to start preview", e)
        }
    }

    /**
     * Get current processed images from ImageProcessor
     */
    fun getProcessedImages() = imageProcessor.getProcessedImages()
    
    /**
     * Clear processed images buffer
     */
    fun clearProcessedImages() {
        imageProcessor.clearBuffer()
    }
    
    /**
     * Get current buffer size
     */
    fun getBufferSize() = imageProcessor.getBufferSize()
    
    /**
     * Get processing statistics
     */
    fun getProcessingStats(): String {
        val processedImages = getProcessedImages()
        val bufferSize = getBufferSize()
        
        return if (processedImages.isNotEmpty()) {
            val avgQuality = processedImages.map { it.qualityScore }.average()
            "Buffer: $bufferSize/3 images | Avg Quality: ${String.format("%.2f", avgQuality)}"
        } else {
            "Buffer: $bufferSize/3 images | No images processed yet"
        }
    }
    
    /**
     * Get current processing stage
     */
    fun getCurrentProcessingStage() = imageProcessor.getCurrentStage()
    
    /**
     * Get warnings status
     */
    fun getWarningsStatus() = imageProcessor.getWarningsStatus()
    
    /**
     * Get detailed warning breakdown by stage
     */
    fun getWarningBreakdown(): String {
        val warningsByStage = camera2ViewModel.getWarningsCountByStage()
        val currentStage = camera2ViewModel.getCurrentPriorityStage()
        
        return "Priority Stage: $currentStage | Stage 1: ${warningsByStage[1] ?: 0} | Stage 2: ${warningsByStage[2] ?: 0} | Stage 3: ${warningsByStage[3] ?: 0}"
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun capturePhoto() {
        val device = cameraDevice ?: return

        try {
            val reader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 1)
            
            reader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    Log.d("Camera2Fragment", "Photo captured: ${it.width}x${it.height}")
                    it.close()
                }
                reader.close()
            }, backgroundHandler)

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)

            val outputConfig = OutputConfiguration(reader.surface)
            val captureSessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.capture(captureBuilder.build(), null, backgroundHandler)
                        } catch (e: CameraAccessException) {
                            Log.e("Camera2Fragment", "Failed to capture photo", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("Camera2Fragment", "Capture session configuration failed")
                    }
                }
            )

            device.createCaptureSession(captureSessionConfig)
            
        } catch (e: CameraAccessException) {
            Log.e("Camera2Fragment", "Failed to capture photo", e)
        }
    }

    companion object {
        private const val TAG = "Camera2Fragment"
    }
} 
package com.example.fingerprint_identifier.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.fingerprint_identifier.analyzer.HandAnalyzer
import com.example.fingerprint_identifier.analyzer.HandType
import com.example.fingerprint_identifier.data.FileRepository
import com.example.fingerprint_identifier.databinding.FragmentCameraBinding
import com.example.fingerprint_identifier.ui.base.BaseFragment
import com.example.fingerprint_identifier.utils.FingerMatcher
import com.example.fingerprint_identifier.utils.ImageQualityUtils
import com.example.fingerprint_identifier.utils.PermissionManager
import com.example.fingerprint_identifier.viewmodel.CameraState
import com.example.fingerprint_identifier.viewmodel.CameraViewModel
import com.example.fingerprint_identifier.viewmodel.OneShotEvent
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import androidx.exifinterface.media.ExifInterface

class CameraFragment : BaseFragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val cameraViewModel: CameraViewModel by viewModels()

    private lateinit var permissionManager: PermissionManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handAnalyzer: HandAnalyzer

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissionManager.handlePermissionsResult(permissions)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initDebug()) {
            Log.e("CameraFragment", "OpenCV initialization failed.")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        handAnalyzer = HandAnalyzer(requireContext(), cameraViewModel)

        val isVerificationMode = arguments?.getBoolean("IS_VERIFICATION_MODE", false) ?: false
        if (isVerificationMode) {
            cameraViewModel.startVerificationMode()
        }

        permissionManager = PermissionManager(
            requireContext(),
            requestPermissionLauncher,
            onPermissionsGranted = { startCamera() },
            onPermissionsDenied = {
                Toast.makeText(
                    requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                activity?.finish()
            }
        )

        permissionManager.checkAndRequestPermissions()

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.retakeButton.setOnClickListener { cameraViewModel.retakeCapture() }
        binding.confirmButton.setOnClickListener {
            when(cameraViewModel.cameraState.value) {
                is CameraState.PalmCaptureSuccess -> cameraViewModel.startFingerCapture()
                is CameraState.FingerCaptureSuccess -> cameraViewModel.confirmFingerCapture()
                else -> {}
            }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            cameraViewModel.cameraState.collectLatest { state ->
                Log.d("CameraFragment", "State changed: $state")
                updateUIForState(state)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            cameraViewModel.warningEvents.collectLatest { warning ->
                Toast.makeText(requireContext(), warning, Toast.LENGTH_SHORT).show()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            cameraViewModel.oneShotEvents.collectLatest { event ->
                if (event is OneShotEvent.TriggerAutoFocus) {
                    triggerAutoFocus(event.landmarks)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            cameraViewModel.deleteFileEvents.collectLatest { uri ->
                Log.d("CameraFragment", "Deleting file: $uri")
                FileRepository(requireContext()).deleteFile(uri)
            }
        }
    }

    private fun updateUIForState(state: CameraState) {
        // Default UI state
        binding.confirmationGroup.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.overlayView.visibility = View.VISIBLE
        binding.captureButton.visibility = View.VISIBLE
        binding.captureButton.isEnabled = false // Disabled by default

        when (state) {
            is CameraState.AwaitingPalm -> {
                binding.overlayView.setMode(OverlayView.OverlayMode.PALM_SQUARE)
                binding.statusText.text = "Please position your palm inside the outline"
            }
            is CameraState.PalmDetected -> {
                val hand = if (state.handType == HandType.LEFT) "Left" else "Right"
                binding.statusText.text = "$hand Hand Detected. Ready to capture."
                binding.captureButton.isEnabled = true
            }
            is CameraState.AwaitingFinger -> {
                binding.overlayView.setMode(OverlayView.OverlayMode.FINGER_OVAL)
                val hand = if (state.handType == HandType.LEFT) "Left" else "Right"
                binding.statusText.text = "$hand Hand Detected. Scan ${state.fingerName} (${state.fingerIndex + 1}/5)."
            }
            is CameraState.FingerDetected -> {
                binding.statusText.text = "${state.fingerName} Detected. Ready to capture."
                binding.captureButton.isEnabled = true
            }
            is CameraState.PalmCaptureSuccess, is CameraState.FingerCaptureSuccess -> {
                binding.previewView.visibility = View.GONE
                binding.captureButton.visibility = View.GONE
                binding.overlayView.visibility = View.GONE
                binding.confirmationGroup.visibility = View.VISIBLE
                val uri = if (state is CameraState.PalmCaptureSuccess) state.imageUri else (state as CameraState.FingerCaptureSuccess).imageUri
                val text = if (state is CameraState.PalmCaptureSuccess) "Palm" else (state as CameraState.FingerCaptureSuccess).fingerName
                binding.confirmationImage.setImageURI(uri)
                binding.statusText.text = "$text Captured. OK to continue?"
            }
            is CameraState.AllCapturesDone -> {
                binding.captureButton.visibility = View.GONE
                binding.overlayView.setMode(OverlayView.OverlayMode.NONE)
                Toast.makeText(context, "All captures complete!", Toast.LENGTH_LONG).show()
                activity?.supportFragmentManager?.popBackStack()
            }
            is CameraState.AwaitingVerification -> {
                binding.overlayView.setMode(OverlayView.OverlayMode.FINGER_OVAL)
                binding.statusText.text = "Place finger in outline to verify."
                binding.captureButton.isEnabled = false
            }
            is CameraState.VerificationHandDetected -> {
                binding.overlayView.setMode(OverlayView.OverlayMode.FINGER_OVAL)
                binding.statusText.text = "Finger Detected. Ready to Verify."
                binding.captureButton.isEnabled = true
            }
            is CameraState.Verification -> {
                binding.overlayView.setMode(OverlayView.OverlayMode.NONE)
                binding.statusText.text = "Verifying finger..."
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val result = FingerMatcher.findBestMatch(requireContext(), state.imageUri)
                    withContext(Dispatchers.Main) {
                        val message = if (result != null) {
                            "Match found: ${result.bestMatchPath} with score ${result.bestMatchScore}"
                        } else {
                            "No match found."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        cameraViewModel.startVerificationMode()
                    }
                }
            }
            is CameraState.Error -> {
                binding.captureButton.visibility = View.GONE
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerAutoFocus(landmarks: List<NormalizedLandmark>) {
        val cameraControl = camera?.cameraControl ?: return
        val wrist = landmarks[HandLandmark.WRIST]
        val meteringPointFactory = binding.previewView.meteringPointFactory
        val meteringPoint = meteringPointFactory.createPoint(wrist.x(), wrist.y())
        val action = FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cameraControl.startFocusAndMetering(action)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val fileRepository = FileRepository(requireContext())
        val stateAtCapture = cameraViewModel.cameraState.value

        Log.d("CameraFragment", "takePhoto() called with state: $stateAtCapture")

        val outputFileOptions = when (stateAtCapture) {
            is CameraState.PalmDetected -> fileRepository.createImageFileOptions(stateAtCapture.handType, "Palm_Full")
            is CameraState.FingerDetected -> fileRepository.createImageFileOptions(stateAtCapture.handType, "${stateAtCapture.fingerName}_Full")
            is CameraState.VerificationHandDetected -> fileRepository.createImageFileOptions(stateAtCapture.landmarks.handType, "Verification_Attempt")
            else -> {
                Log.w("CameraFragment", "TakePhoto called in an invalid state: $stateAtCapture")
                return
            }
        }

        // Start capturing to prevent state changes
        cameraViewModel.startCapture()
        Log.d("CameraFragment", "Started capture, set isCapturing=true")

        imageCapture.takePicture(
            outputFileOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraFragment", "onImageSaved callback triggered")
                    val savedUri = output.savedUri ?: return

                    val landmarks = when (val state = cameraViewModel.cameraState.value) {
                        is CameraState.PalmDetected -> state.landmarks.landmarks
                        is CameraState.FingerDetected -> state.landmarks.landmarks
                        is CameraState.VerificationHandDetected -> state.landmarks.landmarks
                        else -> null
                    }

                    if (landmarks == null) {
                        Log.e("CameraFragment", "Landmarks not available after photo capture in state ${cameraViewModel.cameraState.value}")
                        cameraViewModel.cancelCapture() // Cancel capture when landmarks unavailable
                        fileRepository.deleteFile(savedUri)
                        activity?.runOnUiThread {
                            Toast.makeText(context, "Hand detection lost during capture. Please try again.", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    Log.d("CameraFragment", "Starting image processing on IO thread")
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        Log.d("CameraFragment", "Cropping image based on overlay area")
                        val croppedBitmap = cropImageFromOverlay(savedUri, stateAtCapture)

                        if (ImageQualityUtils.isBlurred(croppedBitmap)) {
                            Log.d("CameraFragment", "Image is blurred, cancelling capture")
                            withContext(Dispatchers.Main) {
                                cameraViewModel.cancelCapture() // Cancel capture on blur
                                Toast.makeText(context, "Image is blurry. Please hold steady and try again.", Toast.LENGTH_SHORT).show()
                                cameraViewModel.onImageBlurred()
                            }
                            // Clean up the full-size image and stop processing
                            fileRepository.deleteFile(savedUri)
                            return@launch
                        }

                        Log.d("CameraFragment", "Saving cropped bitmap")
                        val croppedUri = fileRepository.saveBitmapAndGetUri(
                            croppedBitmap,
                            when (stateAtCapture) {
                                is CameraState.PalmDetected -> "Palm"
                                is CameraState.FingerDetected -> stateAtCapture.fingerName
                                is CameraState.VerificationHandDetected -> "Verification_Attempt"
                                else -> "cropped_image"
                            }
                        )
                        // Delete the original full-size image as it's no longer needed
                        fileRepository.deleteFile(savedUri)

                        Log.d("CameraFragment", "Calling ViewModel capture methods on Main thread")
                        withContext(Dispatchers.Main) {
                            when (stateAtCapture) {
                                is CameraState.PalmDetected -> cameraViewModel.capturePalm(
                                    croppedUri,
                                    stateAtCapture.handType,
                                    stateAtCapture.sessionId
                                )
                                is CameraState.FingerDetected -> cameraViewModel.captureFinger(
                                    croppedUri,
                                    stateAtCapture.handType,
                                    stateAtCapture.fingerName,
                                    stateAtCapture.fingerIndex,
                                    stateAtCapture.sessionId
                                )
                                is CameraState.VerificationHandDetected -> {
                                    cameraViewModel.onVerificationImageCaptured(croppedUri)
                                }
                                else -> {}
                            }
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraFragment", "Photo capture failed: ${exc.message}", exc)
                    cameraViewModel.cancelCapture() // Cancel capture on error
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Camera capture failed. Please try again.", Toast.LENGTH_SHORT).show()
                        cameraViewModel.onError(exc)
                    }
                }
            }
        )
        Log.d("CameraFragment", "Called imageCapture.takePicture()")
    }

    private fun cropImageFromOverlay(imageUri: android.net.Uri, state: CameraState): Bitmap {
        // 1. Read the bitmap and its EXIF orientation
        val inputStream = requireContext().contentResolver.openInputStream(imageUri)
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        val exifInputStream = requireContext().contentResolver.openInputStream(imageUri)
        val exif = ExifInterface(exifInputStream!!)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        exifInputStream.close()

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        // 2. Rotate the bitmap to match the preview orientation
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
        if (rotatedBitmap != originalBitmap) {
            originalBitmap.recycle()
        }

        // 3. Get the overlay for cropping. If none, return the rotated image.
        val overlayRect = binding.overlayView.getOverlayRect()
        if (overlayRect == null) {
            return rotatedBitmap
        }

        // 4. Calculate scaling and crop the correctly rotated image
        val previewWidth = binding.previewView.width.toFloat()
        val previewHeight = binding.previewView.height.toFloat()

        val scaleX = rotatedBitmap.width.toFloat() / previewWidth
        val scaleY = rotatedBitmap.height.toFloat() / previewHeight

        val cropX = (overlayRect.left * scaleX).toInt().coerceAtLeast(0)
        val cropY = (overlayRect.top * scaleY).toInt().coerceAtLeast(0)
        val cropWidth = (overlayRect.width() * scaleX).toInt().coerceAtMost(rotatedBitmap.width - cropX)
        val cropHeight = (overlayRect.height() * scaleY).toInt().coerceAtMost(rotatedBitmap.height - cropY)
        
        Log.d("CameraFragment", "Cropping rotated: original=${originalBitmap.width}x${originalBitmap.height}, rotated=${rotatedBitmap.width}x${rotatedBitmap.height}, preview=${previewWidth}x${previewHeight}, crop=($cropX, $cropY, $cropWidth, $cropHeight)")

        val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, cropWidth, cropHeight)
        if (croppedBitmap != rotatedBitmap) {
            rotatedBitmap.recycle()
        }
        return croppedBitmap
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, handAnalyzer)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("CameraFragment", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cameraExecutor.shutdown()
    }
} 
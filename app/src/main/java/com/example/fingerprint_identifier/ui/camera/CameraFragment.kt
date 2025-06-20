package com.example.fingerprint_identifier.ui.camera

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.fingerprint_identifier.databinding.FragmentCameraBinding
import com.example.fingerprint_identifier.ui.base.BaseFragment
import com.example.fingerprint_identifier.utils.PermissionManager
import com.example.fingerprint_identifier.viewmodel.CameraState
import com.example.fingerprint_identifier.viewmodel.CameraViewModel
import com.example.fingerprint_identifier.analyzer.HandAnalyzer
import com.example.fingerprint_identifier.data.FileRepository
import com.example.fingerprint_identifier.analyzer.HandType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : BaseFragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val cameraViewModel: CameraViewModel by viewModels()

    private lateinit var permissionManager: PermissionManager
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissionManager.handlePermissionsResult(permissions)
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
            when(val state = cameraViewModel.cameraState.value) {
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
                // Default UI state
                binding.confirmationGroup.visibility = View.GONE
                binding.previewView.visibility = View.VISIBLE
                binding.overlayView.visibility = View.VISIBLE
                binding.captureButton.visibility = View.VISIBLE

                when (state) {
                    is CameraState.AwaitingPalm -> {
                        binding.overlayView.setMode(OverlayView.OverlayMode.PALM_SQUARE)
                        binding.statusText.text = "Please position your palm inside the outline"
                    }
                    is CameraState.PalmDetected -> {
                        val hand = if (state.handType == HandType.LEFT) "Left" else "Right"
                        binding.statusText.text = "$hand Hand Detected. Ready to capture."
                    }
                    is CameraState.AwaitingFinger -> {
                        binding.overlayView.setMode(OverlayView.OverlayMode.FINGER_OVAL)
                        val hand = if (state.handType == HandType.LEFT) "Left" else "Right"
                        binding.statusText.text = "$hand Hand Detected. Scan ${state.fingerName} (${state.fingerIndex + 1}/5)."
                    }
                    is CameraState.FingerDetected -> {
                        binding.statusText.text = "${state.fingerName} Detected. Ready to capture."
                    }
                    is CameraState.PalmCaptureSuccess -> {
                        binding.previewView.visibility = View.GONE
                        binding.captureButton.visibility = View.GONE
                        binding.overlayView.visibility = View.GONE
                        binding.confirmationGroup.visibility = View.VISIBLE
                        binding.confirmationImage.setImageURI(state.imageUri)
                        binding.statusText.text = "Palm Captured. OK to continue?"
                    }
                    is CameraState.FingerCaptureSuccess -> {
                        binding.previewView.visibility = View.GONE
                        binding.captureButton.visibility = View.GONE
                        binding.overlayView.visibility = View.GONE
                        binding.confirmationGroup.visibility = View.VISIBLE
                        binding.confirmationImage.setImageURI(state.imageUri)
                        binding.statusText.text = "${state.fingerName} Captured. OK to continue?"
                    }
                    is CameraState.AllCapturesDone -> {
                        binding.captureButton.visibility = View.GONE
                        binding.overlayView.setMode(OverlayView.OverlayMode.NONE)
                        // Potentially navigate back or to a summary screen after a delay
                        Toast.makeText(context, "All captures complete!", Toast.LENGTH_LONG).show()
                        activity?.supportFragmentManager?.popBackStack()
                    }
                    is CameraState.Error -> {
                        binding.captureButton.visibility = View.GONE
                        Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {

                    }
                }
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val currentState = cameraViewModel.cameraState.value
        val handTypeToCapture = when(currentState) {
            is CameraState.AwaitingPalm -> HandType.RIGHT
            is CameraState.PalmDetected -> currentState.handType
            is CameraState.AwaitingFinger -> currentState.handType
            is CameraState.FingerDetected -> currentState.handType
            else -> null
        }

        if (handTypeToCapture == null) {
            Log.w("CameraFragment", "TakePhoto called in an invalid state: $currentState")
            return
        }

        val fileRepository = FileRepository(requireContext())
        
        val outputFileOptions = if (currentState is CameraState.AwaitingFinger) {
            fileRepository.createImageFileOptions(handTypeToCapture, currentState.fingerName)
        } else {
            fileRepository.createImageFileOptions(handTypeToCapture, "Palm")
        }

        imageCapture.takePicture(
            outputFileOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    activity?.runOnUiThread {
                         val state = cameraViewModel.cameraState.value
                        if (state is CameraState.AwaitingPalm || state is CameraState.PalmDetected) {
                            cameraViewModel.capturePalm(handTypeToCapture, output.savedUri!!)
                        } else if (state is CameraState.AwaitingFinger || state is CameraState.FingerDetected) {
                            cameraViewModel.captureFinger(output.savedUri!!)
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraFragment", "Photo capture failed: ${exc.message}", exc)
                    activity?.runOnUiThread {
                        cameraViewModel.onError(exc)
                    }
                }
            }
        )
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

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
//                    it.setAnalyzer(cameraExecutor, HandAnalyzer(cameraViewModel))
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
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
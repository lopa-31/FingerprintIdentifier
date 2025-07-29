public class FingerprintCameraManager {
    private static final String TAG = "FingerprintCamera";
    
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    
    // Focus state tracking
    private boolean isFocusLocked = false;
    private boolean isWaitingForFocus = false;
    private Handler backgroundHandler;
    
    // Focus areas for fingerprint scanning
    private static final int FOCUS_AREA_SIZE = 200; // pixels
    private MeteringRectangle[] focusAreas;
    private MeteringRectangle[] meteringAreas;
    
    public void setupCamera(String cameraId, Surface previewSurface) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            
            // Check if camera supports manual focus control
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            boolean supportsAutoFocus = false;
            for (int mode : afModes) {
                if (mode == CameraCharacteristics.CONTROL_AF_MODE_AUTO ||
                    mode == CameraCharacteristics.CONTROL_AF_MODE_MACRO) {
                    supportsAutoFocus = true;
                    break;
                }
            }
            
            if (!supportsAutoFocus) {
                Log.e(TAG, "Camera doesn't support auto focus");
                return;
            }
            
            // Setup ImageReader for high-res capture
            Size[] jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);
            Size largestSize = Collections.max(Arrays.asList(jpegSizes), new CompareSizesByArea());
            
            imageReader = ImageReader.newInstance(largestSize.getWidth(), largestSize.getHeight(),
                    ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(imageReaderListener, backgroundHandler);
            
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception", e);
        }
    }
    
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }
        
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }
        
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };
    
    private void createCameraPreviewSession() {
        try {
            Surface previewSurface = // Your preview surface here
            
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);
            
            // Configure for fingerprint scanning
            setupFingerprintCaptureSettings();
            
            cameraDevice.createCaptureSession(
                Arrays.asList(previewSurface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        
                        captureSession = session;
                        try {
                            previewRequest = previewRequestBuilder.build();
                            captureSession.setRepeatingRequest(previewRequest, 
                                captureCallback, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Exception creating preview session", e);
                        }
                    }
                    
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "Configuration failed");
                    }
                }, null);
                
        } catch (CameraAccessException e) {
            Log.e(TAG, "Exception creating camera preview session", e);
        }
    }
    
    private void setupFingerprintCaptureSettings() {
        // Use macro mode if available, otherwise auto
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, 
            CaptureRequest.CONTROL_AF_MODE_MACRO);
        
        // Use single point auto focus for precision
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, 
            CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        
        // Enable auto exposure and white balance
        previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, 
            CaptureRequest.CONTROL_AE_MODE_ON);
        previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, 
            CaptureRequest.CONTROL_AWB_MODE_AUTO);
        
        // Use flash if needed for fingerprint clarity
        previewRequestBuilder.set(CaptureRequest.FLASH_MODE, 
            CaptureRequest.FLASH_MODE_TORCH);
        
        // Optimize for close-up shots
        previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.1f); // Close focus
        previewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, 
            CaptureRequest.CONTROL_SCENE_MODE_DISABLED);
    }
    
    // Main focus trigger method for fingerprint capture
    public void triggerFingerprintFocus(float x, float y, int viewWidth, int viewHeight) {
        if (cameraDevice == null || captureSession == null) return;
        
        try {
            // Calculate focus area based on touch coordinates
            calculateFocusAreas(x, y, viewWidth, viewHeight);
            
            // Set focus areas
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, focusAreas);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringAreas);
            
            // Trigger auto focus
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, 
                CaptureRequest.CONTROL_AF_TRIGGER_START);
            
            isWaitingForFocus = true;
            isFocusLocked = false;
            
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
            
            // Reset AF trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, 
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Exception during focus trigger", e);
        }
    }
    
    // Auto-trigger focus for center of frame (good for fingerprint scanning)
    public void triggerCenterFocus() {
        // Focus on center of the frame - ideal for fingerprint scanning
        triggerFingerprintFocus(0.5f, 0.5f, 1, 1);
    }
    
    private void calculateFocusAreas(float x, float y, int viewWidth, int viewHeight) {
        // Convert touch coordinates to camera coordinates (-1000 to 1000)
        int centerX = (int) ((x / viewWidth) * 2000 - 1000);
        int centerY = (int) ((y / viewHeight) * 2000 - 1000);
        
        int halfSize = FOCUS_AREA_SIZE / 2;
        
        Rect focusRect = new Rect(
            Math.max(centerX - halfSize, -1000),
            Math.max(centerY - halfSize, -1000),
            Math.min(centerX + halfSize, 1000),
            Math.min(centerY + halfSize, 1000)
        );
        
        focusAreas = new MeteringRectangle[]{ 
            new MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX) 
        };
        meteringAreas = focusAreas; // Use same area for exposure metering
    }
    
    private final CameraCaptureSession.CaptureCallback captureCallback = 
        new CameraCaptureSession.CaptureCallback() {
        
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                     @NonNull CaptureRequest request,
                                     @NonNull TotalCaptureResult result) {
            
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            
            if (isWaitingForFocus && afState != null) {
                handleAutoFocusState(afState);
            }
        }
        
        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                  @NonNull CaptureRequest request,
                                  @NonNull CaptureFailure failure) {
            Log.e(TAG, "Capture failed");
        }
    };
    
    private void handleAutoFocusState(int afState) {
        switch (afState) {
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                // Focus is locked - good or bad
                isFocusLocked = true;
                isWaitingForFocus = false;
                
                if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                    Log.d(TAG, "Focus achieved - ready for fingerprint capture");
                    onFocusAchieved();
                } else {
                    Log.w(TAG, "Focus failed - retrying");
                    retryFocus();
                }
                break;
                
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN:
                Log.d(TAG, "Auto focus scanning...");
                break;
                
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:
                Log.d(TAG, "Passive focus scanning...");
                break;
                
            case CaptureResult.CONTROL_AF_STATE_INACTIVE:
                Log.d(TAG, "Auto focus inactive");
                break;
        }
    }
    
    private void onFocusAchieved() {
        // Focus is good - now we can capture the fingerprint
        // Notify UI that focus is ready
        if (focusListener != null) {
            focusListener.onFocusReady();
        }
        
        // Optionally auto-capture after short delay
        backgroundHandler.postDelayed(() -> {
            if (isFocusLocked) {
                captureFingerprint();
            }
        }, 500); // 500ms delay for stability
    }
    
    private void retryFocus() {
        // If focus failed, try again with slightly different parameters
        backgroundHandler.postDelayed(() -> {
            if (!isFocusLocked) {
                triggerCenterFocus();
            }
        }, 1000); // Wait 1 second before retry
    }
    
    public void captureFingerprint() {
        if (cameraDevice == null || !isFocusLocked) {
            Log.w(TAG, "Cannot capture - camera not ready or focus not locked");
            return;
        }
        
        try {
            CaptureRequest.Builder captureBuilder = 
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            
            captureBuilder.addTarget(imageReader.getSurface());
            
            // Use the same settings as preview for consistent focus
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, 
                CaptureRequest.CONTROL_AF_MODE_MACRO);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, 
                CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.FLASH_MODE, 
                CaptureRequest.FLASH_MODE_TORCH);
            
            // Maintain focus areas
            if (focusAreas != null) {
                captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, focusAreas);
                captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringAreas);
            }
            
            captureSession.capture(captureBuilder.build(), null, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Exception during fingerprint capture", e);
        }
    }
    
    private final ImageReader.OnImageAvailableListener imageReaderListener = 
        new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                // Process the captured fingerprint image
                processFingerprintImage(image);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };
    
    private void processFingerprintImage(Image image) {
        // Convert Image to byte array or Bitmap for further processing
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        
        // Save or process the fingerprint image
        // This is where you'd integrate with your fingerprint processing logic
        Log.d(TAG, "Fingerprint captured: " + bytes.length + " bytes");
        
        if (focusListener != null) {
            focusListener.onFingerprintCaptured(bytes);
        }
    }
    
    // Continuous focus mode for real-time fingerprint detection
    public void enableContinuousFocus() {
        if (previewRequestBuilder == null) return;
        
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, 
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            
            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Exception enabling continuous focus", e);
        }
    }
    
    public void unlockFocus() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
            
            isFocusLocked = false;
            isWaitingForFocus = false;
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Exception unlocking focus", e);
        }
    }
    
    // Interface for focus callbacks
    public interface FocusListener {
        void onFocusReady();
        void onFingerprintCaptured(byte[] imageData);
        void onFocusFailed();
    }
    
    private FocusListener focusListener;
    
    public void setFocusListener(FocusListener listener) {
        this.focusListener = listener;
    }
    
    // Utility class for size comparison
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                              (long) rhs.getWidth() * rhs.getHeight());
        }
    }
    
    public void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
}
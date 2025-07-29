### 4. **Smart Triggering** (continued)
- Only triggers macro focus when object is within close range (>2 diopters)
- Avoids unnecessary focus sweeps for distant objects
- Uses focus distance change thresholds to detect when fingers approach

### 5. **Focus Stability & Lock**
- Waits for focus to stabilize before locking
- Performs multiple contrast checks to ensure optimal focus
- Holds focus lock briefly before returning to continuous AF

## Key Implementation Details:

### **Distance Thresholds**:
```kotlin
private const val CLOSE_OBJECT_THRESHOLD = 2.0f  // ~50cm - triggers close object detection
private const val MACRO_RANGE_THRESHOLD = 5.0f   // ~20cm - optimal fingerprint range
```

### **Focus Sweep Process**:
1. **Detection**: Focus distance jumps above threshold
2. **Manual Mode**: Switch from continuous to manual focus
3. **Sweep**: Step through focus distances (0.1 diopter increments)
4. **Analysis**: Calculate contrast at each position
5. **Lock**: Lock at position with highest contrast
6. **Return**: Return to continuous AF after 2 seconds

### **Performance Optimizations**:

```kotlin
// Rate limiting for performance
private const val CONTRAST_ANALYSIS_INTERVAL = 100L // ms
private const val FOCUS_SWEEP_STEP = 0.1f
private const val FOCUS_STABILITY_CHECKS = 3
```

## Usage Example:

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var fingerprintCamera: FingerprintAutoFocusCamera
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        fingerprintCamera = FingerprintAutoFocusCamera(this)
        
        // Setup camera surfaces (TextureView/SurfaceView for preview, ImageReader for capture)
        val previewSurface = textureView.surfaceTexture?.let { Surface(it) }
        val captureSurface = imageReader.surface
        
        // Initialize with automatic macro focus
        fingerprintCamera.initializeCamera(
            getSystemService(CameraManager::class.java),
            previewSurface!!,
            captureSurface
        )
        
        // Set up focus callbacks
        fingerprintCamera.setFingerprintFocusListener(object : FingerprintAutoFocusCamera.FingerprintFocusListener {
            override fun onFingerDetected(focusDistance: Float) {
                statusText.text = "👆 Finger detected - Auto-focusing..."
                statusText.setTextColor(Color.YELLOW)
            }
            
            override fun onFingerprintFocusStarted() {
                statusText.text = "🔍 Focusing on fingerprint..."
                progressBar.visibility = View.VISIBLE
            }
            
            override fun onFingerprintFocusCompleted(distance: Float, quality: Float) {
                progressBar.visibility = View.GONE
                
                when {
                    quality > 0.8f -> {
                        statusText.text = "✅ Perfect focus - Capturing..."
                        statusText.setTextColor(Color.GREEN)
                    }
                    quality > 0.6f -> {
                        statusText.text = "⚡ Good focus - Capturing..."
                        statusText.setTextColor(Color.BLUE)
                    }
                    else -> {
                        statusText.text = "⚠️ Focus achieved - Try holding still"
                        statusText.setTextColor(Color.ORANGE)
                    }
                }
            }
            
            override fun onFingerprintReadyForCapture() {
                statusText.text = "📸 Ready! Hold still..."
            }
            
            override fun onFingerprintCaptured() {
                statusText.text = "✨ Fingerprint captured successfully!"
                statusText.setTextColor(Color.GREEN)
                
                // Process captured fingerprint
                processFingerprint()
            }
            
            override fun onFingerprintCaptureFailed() {
                statusText.text = "❌ Capture failed - Please try again"
                statusText.setTextColor(Color.RED)
            }
        })
    }
    
    // Manual trigger if needed
    private fun onManualFocusButton() {
        fingerprintCamera.forceFingerprintFocus()
    }
    
    override fun onPause() {
        super.onPause()
        fingerprintCamera.pauseAutoFocus()
    }
    
    override fun onResume() {
        super.onResume()
        fingerprintCamera.resumeAutoFocus()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        fingerprintCamera.closeCamera()
    }
}
```

## Advanced Features You Can Add:

### **1. Real Image Contrast Analysis**:
```kotlin
// Instead of simulated contrast, analyze actual image data
private fun calculateRealContrast(image: Image): Float {
    val planes = image.planes
    val buffer = planes[0].buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    
    // Calculate Sobel edge detection or Laplacian variance
    return calculateSobelVariance(data, image.width, image.height)
}
```

### **2. Focus Hunting Prevention**:
```kotlin
private var focusHuntingCount = 0
private const val MAX_HUNTING_ATTEMPTS = 3

private fun preventFocusHunting() {
    if (focusHuntingCount > MAX_HUNTING_ATTEMPTS) {
        // Lock current focus and wait longer before retry
        holdCurrentFocus(5000) // 5 second hold
        focusHuntingCount = 0
    }
}
```

### **3. Environmental Adaptation**:
```kotlin
private fun adaptToLighting(result: TotalCaptureResult) {
    val luxLevel = result.get(CaptureResult.STATISTICS_SCENE_FLICKER)
    
    when {
        luxLevel != null && luxLevel < 50 -> {
            // Low light - use slower, more precise focus
            focusSweepInterval = 100L
        }
        else -> {
            // Good light - use faster focus
            focusSweepInterval = 50L
        }
    }
}
```

This implementation gives you the same automatic macro focus behavior as modern phone camera apps - it detects when a finger approaches, automatically switches to macro focus mode, finds the optimal focus distance, and locks focus for capture!
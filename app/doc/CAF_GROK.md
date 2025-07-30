### Direct Answer

- **Key Points**:  
  - Research suggests that phone camera apps use continuous autofocus to automatically focus on close objects without user input, likely using the `CONTROL_AF_MODE_CONTINUOUS_PICTURE` mode in the Camera2 API.  
  - It seems likely that this mode keeps the focus adjusting dynamically, which can lock onto close objects if they are within the camera’s focus range.  
  - The evidence leans toward this being effective for close objects, but performance may vary by device, especially for very close macro shots.

**How It Works**  
Phone camera apps automatically focus on close objects by using continuous autofocus, which constantly adjusts the lens to keep the subject sharp. This is done without any taps or manual input, relying on the camera’s built-in algorithms to detect and focus on the nearest object in view. For close objects, the camera likely uses contrast detection or phase detection to find the sharpest focus, and it seems to "lock on" by maintaining focus as long as the object is stable relative to the camera.

**Implementing in Camera2 API**  
To replicate this in your Android app using Kotlin and the Camera2 API, set the autofocus mode to `CONTROL_AF_MODE_CONTINUOUS_PICTURE`. This mode ensures the camera continuously adjusts focus, suitable for automatically focusing on close objects. Here’s a simple setup:  
- Open the camera and create a preview session.  
- Set the capture request with `CONTROL_AF_MODE_CONTINUOUS_PICTURE` to enable continuous autofocus.  
- Use a repeating request to keep the preview running with this mode.

**Example Code Snippet**  
```kotlin
val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
previewRequestBuilder.addTarget(previewSurface)
previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
```

**Considerations**  
- Some devices may have limitations for very close objects; check `LENS_INFO_MINIMUM_FOCUS_DISTANCE` in camera characteristics to see if macro focus is supported.  
- Testing on different devices is recommended, as camera hardware varies.

---

### Survey Note: Detailed Analysis of Autofocus on Close Objects Using Camera2 API in Kotlin

This section provides a comprehensive exploration of how phone camera apps perform autofocus on close objects automatically without user input and how to implement this using the Camera2 API in Kotlin. It includes all relevant details from the research, technical insights, and practical considerations, expanding on the direct answer with a professional, detailed approach.

#### Background and Mechanism of Autofocus in Phone Cameras

Phone camera apps, such as those on modern smartphones, leverage advanced autofocus systems to automatically focus on objects, including those close to the camera, without requiring user taps or manual adjustments. Research from various sources, including articles on Medium, Stack Overflow, and Android Developers documentation, indicates that these apps typically use continuous autofocus modes. The primary methods include:

- **Contrast Detection Autofocus (CDAF)**: This method, commonly used in smartphones, adjusts the lens position back and forth to maximize contrast in the image, which is particularly effective for static scenes and close objects. It’s noted in sources like "How Smartphone Cameras Focus" (thesmartphonephotographer.com) that CDAF is slower but works well in good lighting conditions with high contrast, suitable for close-up shots.
- **Phase Detection Autofocus (PDAF)**: Used in higher-end devices, PDAF splits light to compare images from different lens parts, offering faster focusing, which can be beneficial for dynamic scenes, including close objects. This is mentioned in discussions on Reddit (r/askscience) and Wikipedia entries on autofocus.
- **Laser Autofocus**: Some devices, like LG G3/G4, use laser autofocus for precise distance measurement, especially effective for close objects, as noted in giffgaff’s blog on smartphone autofocus.

For close objects, the camera often relies on these systems to detect and focus on the nearest subject, with continuous autofocus ensuring the focus adjusts dynamically. The "lock on" behavior, as mentioned by the user, likely refers to the camera maintaining focus on a stable close object, which is achieved through continuous autofocus modes like `CONTROL_AF_MODE_CONTINUOUS_PICTURE` in the Camera2 API.

#### Implementing Autofocus in Camera2 API for Close Objects

The Camera2 API, introduced in Android 5.0 (API level 21), provides fine-grained control over camera features, including autofocus. To replicate the automatic focusing and "lock on" behavior for close objects without user input, the following approach is recommended based on the research:

1. **Autofocus Mode Selection**:  
   - Set the autofocus mode to `CONTROL_AF_MODE_CONTINUOUS_PICTURE`, which is designed for continuous autofocus during preview or video recording. This mode ensures the camera constantly adjusts focus to keep the subject sharp, suitable for automatically focusing on close objects. This is supported by documentation in "CaptureRequest | Android Developers" and discussions on Stack Overflow (e.g., stackoverflow.com/questions/46823116).
   - Alternatively, `CONTROL_AF_MODE_AUTO` can be used with a trigger (`CONTROL_AF_TRIGGER_START`) for a one-time autofocus sweep, but continuous mode is more appropriate for automatic, ongoing focusing without user input.

2. **Camera Setup and Preview**:  
   - Open the camera using `CameraManager` and create a capture session with a preview surface (e.g., from `TextureView` or `SurfaceView`).  
   - Configure the capture request with `CONTROL_AF_MODE_CONTINUOUS_PICTURE` and set it as a repeating request to maintain continuous autofocus during preview. This is detailed in articles like "Mastering Camera2 API in Kotlin" (medium.com/@rezaramesh) and "Android Camera2 – How to Use the Camera2 API" (freecodecamp.org/news/android-camera2-api-take-photos-and-videos/).

3. **Checking Macro Focus Support**:  
   - To ensure the camera can focus on very close objects, check `LENS_INFO_MINIMUM_FOCUS_DISTANCE` in `CameraCharacteristics`. If this value is greater than 0, the camera supports macro focusing, which is crucial for close-up shots. This is mentioned in discussions on Stack Overflow (e.g., stackoverflow.com/questions/31797821) and aligns with findings from "How do smartphone cameras work?" (androidpolice.com).

4. **Focus Locking Behavior**:  
   - The "lock on" behavior, as requested, is inherently handled by `CONTROL_AF_MODE_CONTINUOUS_PICTURE`, which adjusts focus continuously to maintain sharpness on the subject. If the object is stable relative to the camera, the focus effectively "locks" on it, though it will adjust if the object or camera moves. For explicit focus locking, you can switch to `CONTROL_AF_MODE_AUTO`, trigger autofocus, and wait for `CONTROL_AF_STATE_FOCUSED_LOCKED` in the capture callback, as discussed in stackoverflow.com/questions/42127464.

#### Code Example and Implementation Details

Below is a detailed Kotlin implementation using the Camera2 API, focusing on continuous autofocus for close objects. This code assumes a basic setup with a `TextureView` for preview and handles camera lifecycle events.

```kotlin
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSize: Size? = null
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private val semaphore = Semaphore(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textureView = findViewById(R.id.texture_view)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera(width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun openCamera(width: Int, height: Int) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (camId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(camId)
                val streamConfig = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (streamConfig != null) {
                    previewSize = streamConfig.getOutputSizes(SurfaceTexture::class.java)[0]
                    cameraId = camId
                    break
                }
            }

            if (cameraId == null) {
                throw RuntimeException("No camera found")
            }

            // Check if autofocus is supported
            val afAvailable = manager.getCameraCharacteristics(cameraId!!).get(CameraCharacteristics.CONTROL_AF_AVAILABLE)
            if (afAvailable != null && afAvailable) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
                    return
                }
                manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            val previewSurface = Surface(surfaceTexture)

            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)

            // Set continuous autofocus for close objects
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) return
        try {
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(textureView.surface)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = textureView.surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
            backgroundThread = null
            backgroundHandler = Handler(null)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}
```

This code sets up a basic camera preview with continuous autofocus, ensuring the camera automatically focuses on close objects without user input. The `CONTROL_AF_MODE_CONTINUOUS_PICTURE` mode is used to maintain dynamic focus adjustment, mimicking the behavior of standard phone camera apps.

#### Technical Considerations and Device Variability

- **Device Variability**: Research from sources like "Trials and Tribulations with Android Camera2 API" (hofmadresu.com) highlights that camera hardware varies across devices, especially between Samsung and non-Samsung phones. This can affect autofocus performance, particularly for close objects. Testing on multiple devices is crucial, as noted in discussions on Stack Overflow (e.g., stackoverflow.com/questions/31797821).
- **Macro Focus Support**: Some devices struggle with close-up focusing, as seen in forum posts like "Focussing issues when using Open Camera with Camera2 API" (forum.fairphone.com/t/56100). Checking `LENS_INFO_MINIMUM_FOCUS_DISTANCE` can help determine if macro focus is supported, with values greater than 0 indicating capability for close-up shots.
- **Autofocus Modes and Limitations**: While `CONTROL_AF_MODE_CONTINUOUS_PICTURE` is ideal, some devices may not support it at the LEGACY hardware level, as mentioned in stackoverflow.com/questions/33922670. Ensure the camera supports autofocus by checking `CONTROL_AF_AVAILABLE` in `CameraCharacteristics`.

#### Comparative Analysis of Autofocus Methods

To provide a clearer understanding, here’s a table comparing different autofocus modes in the Camera2 API, based on research from Android Developers documentation and Stack Overflow discussions:

| **Autofocus Mode**               | **Description**                                      | **Suitable for Close Objects?** | **Locking Capability**         |
|-----------------------------------|-----------------------------------------------------|----------------------------------|-------------------------------|
| `CONTROL_AF_MODE_OFF`             | Manual focus, no automatic adjustment               | No, requires manual setting      | N/A                          |
| `CONTROL_AF_MODE_AUTO`            | One-time autofocus sweep, triggered manually        | Yes, if triggered appropriately | Can lock after focus achieved |
| `CONTROL_AF_MODE_CONTINUOUS_PICTURE` | Continuous autofocus, adjusts focus dynamically    | Yes, ideal for automatic focus   | No explicit lock, adjusts continuously |
| `CONTROL_AF_MODE_MACRO` (if supported) | Focuses on very close objects, specific to macro   | Yes, if supported by hardware    | Depends on implementation     |

This table highlights that `CONTROL_AF_MODE_CONTINUOUS_PICTURE` is the most suitable for the user’s requirement of automatic focusing on close objects without user input, with dynamic adjustment akin to "locking on" when the object is stable.

#### Conclusion and Recommendations

Given the user’s requirement for automatic autofocus on close objects without taps and with a "lock on" behavior, using `CONTROL_AF_MODE_CONTINUOUS_PICTURE` in the Camera2 API is the recommended approach. This mode ensures continuous focus adjustment, effectively maintaining focus on close objects as long as they remain in view and stable. For implementation, follow the provided code example, ensuring proper camera lifecycle management and testing across devices to account for hardware variations. Additionally, checking `LENS_INFO_MINIMUM_FOCUS_DISTANCE` can confirm macro focus support, enhancing performance for very close objects.

This approach aligns with how modern phone camera apps operate, providing a seamless, automatic focusing experience for close-up photography.
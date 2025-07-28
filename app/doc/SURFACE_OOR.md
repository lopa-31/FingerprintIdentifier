The errors you're encountering, specifically `Surface$OutOfResourcesException` and `SurfaceComposer Client::createSurface error Invalid argument`, point directly to a critical issue with resource management in your application. When using a custom `SurfaceView` with the Camera2 API, the system is unable to allocate the necessary graphics memory for a new `Surface`, which is essential for displaying the camera preview. This is a common but serious problem that can lead to crashes.

The root cause is almost always a memory leak, where your application holds onto resources—particularly the `SurfaceView` or the camera device itself—after they are no longer needed. This prevents the Android system's garbage collector from freeing up memory.

Here is a detailed guide on how to diagnose and fix this issue by implementing robust lifecycle and resource management.

### 1. Master the SurfaceView and Activity/Fragment Lifecycles

The most critical step is to correctly manage the camera's lifecycle in coordination with your `Activity` or `Fragment` and the `SurfaceView` itself. The camera is a shared system resource and must be released when your app is not in the foreground.

**Key Principles:**

*   **Open Camera in `onResume()`:** The camera should be initialized and opened when your activity or fragment becomes visible and interactive.
*   **Release Camera in `onPause()`:** You **must** release the camera when your activity or fragment is paused. This ensures other apps can use the camera and prevents your app from holding onto resources when in the background.
*   **Use `SurfaceHolder.Callback`:** A `SurfaceView`'s underlying `Surface` is created and destroyed asynchronously. You need to react to these events to know when it's safe to start the camera preview.

**Actionable Steps:**

1.  **Implement `SurfaceHolder.Callback`** in your class that manages the `SurfaceView`.
2.  **Structure your camera logic** around the lifecycle methods as shown below.

```java
public class CameraActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        mSurfaceView = findViewById(R.id.surfaceView);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        // Surface is ready. Open the camera here.
        openCamera();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        // Handle surface size changes if necessary
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // Surface is being destroyed. Release the camera here.
        closeCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the surface is already available, open the camera.
        // Otherwise, wait for surfaceCreated.
        if (mSurfaceHolder.getSurface() != null) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        // Release the camera immediately on pause.
        closeCamera();
        super.onPause();
    }

    private void openCamera() {
        // ... Your camera opening logic ...
        // Ensure you don't try to open a camera that's already open.
        if (mCameraDevice != null) {
            return;
        }
        // Get CameraManager, choose a camera, and call cameraManager.openCamera()
    }

    private void closeCamera() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }
}
```

### 2. Ensure Complete and Correct Camera Resource Release

A common source of leaks is failing to close all Camera2 objects in the correct order. Simply calling `cameraDevice.close()` is not enough.

**Correct Release Order:**

1.  **Close the `CameraCaptureSession`:** This stops the camera from sending any more frames.
2.  **Close the `CameraDevice`:** This releases the camera hardware itself.
3.  **Close any `ImageReader` instances:** If you are using an `ImageReader` for capturing stills, it must also be closed to release its internal surfaces.
4.  **Stop the background `HandlerThread`:** Camera operations should run on a background thread. This thread must be safely stopped when the camera is closed.

**Example `closeCamera()` Implementation:**

```java
private void closeCamera() {
    try {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        stopBackgroundThread(); // Method to quit your HandlerThread safely
    } catch (Exception e) {
        // Log the exception, don't crash
    }
}
```

It is best to wait for the `onClosed` callback of the `CameraCaptureSession` before releasing the surface, though releasing it after `session.close()` might work with some error logging.

### 3. Be Mindful of Context and Memory Leaks

*   **Avoid Leaking Context:** When getting the `CameraManager`, use the application context (`getApplicationContext()`) instead of the `Activity` context. This can prevent a known memory leak on some older Android versions where the `CameraManager` would hold a reference to the `Activity`, preventing it from being garbage collected.

    ```java
    CameraManager manager = (CameraManager) getApplicationContext().getSystemService(Context.CAMERA_SERVICE);
    ```
*   **Use the Android Profiler:** Use the memory profiler in Android Studio to actively hunt for leaks. After opening and closing your camera activity multiple times, force garbage collection. If you see the instance count for your `CameraActivity` or `CameraFragment` continuously rising, you have a memory leak.

### 4. Considerations for the UIDAI SDK

The presence of the `in.gov.uidai.contactlessfingersdk` adds another layer of complexity.

*   **Resource Contention:** The SDK might be attempting to use the camera or create its own UI components that require significant graphics memory. Ensure that your camera implementation and the SDK's operations are not conflicting.
*   **SDK Documentation:** Review the documentation for the UIDAI SDK for any specific guidelines on memory management or interaction with the camera. There may be specific methods you need to call to release its resources properly.

By rigorously implementing these lifecycle and resource management practices, you should be able to resolve the `OutOfResourcesException` and create a stable camera application.
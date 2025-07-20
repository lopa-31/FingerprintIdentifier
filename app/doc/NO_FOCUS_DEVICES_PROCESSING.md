Excellent question. This is a critical edge case that many developers encounter, especially when creating apps for a wide range of Android devices, including entry-level models.

If a device supports neither manual focus nor auto-focus, it means you are dealing with a **Fixed-Focus camera**.

### What is a Fixed-Focus Camera?

A fixed-focus camera has a lens that is locked at a specific distance. It cannot be changed by software. The lens is set at the factory to its [hyperfocal distance](https://en.wikipedia.org/wiki/Hyperfocal_distance), a fancy term for a distance that keeps a wide range of objects (from a few feet away to infinity) in *acceptable* focus.

These cameras are common on low-cost smartphones and tablets. They are designed for general-purpose shots like selfies or scenery, not for close-up macro work like scanning a fingerprint.

**The major implication for your project is that you will likely never get a perfectly sharp, "tack-sharp" image of a fingerprint.** The finger will almost certainly be closer to the lens than the camera's fixed optimal distance, resulting in an inherently soft or blurry image.

### How to Programmatically Check for a Fixed-Focus Camera

You can determine if a camera is fixed-focus by inspecting its `CameraCharacteristics`. There are two key indicators you should check:

1.  **Minimum Focus Distance:** A fixed-focus lens will report a minimum focus distance of `0.0f`. This signifies that the lens position is fixed and cannot be controlled.
2.  **Available AF Modes:** The list of available auto-focus modes will either be empty or contain only `CONTROL_AF_MODE_OFF`.

Here is a function you can use to check for this capability:

```kotlin
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

fun isFixedFocusCamera(cameraManager: CameraManager, cameraId: String): Boolean {
    val characteristics = cameraManager.getCameraCharacteristics(cameraId)

    // 1. Check the minimum focus distance. If it's 0, the lens is fixed.
    val minFocusDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    if (minFocusDist == null || minFocusDist == 0.0f) {
        // This is a strong indicator of a fixed-focus camera.
        
        // 2. As a definitive confirmation, check the available AF modes.
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (afModes == null || afModes.isEmpty() || (afModes.size == 1 && afModes[0] == CameraCharacteristics.CONTROL_AF_MODE_OFF)) {
            // No AF modes are supported, confirming it's fixed-focus.
            return true
        }
    }
    
    return false
}
```

You should run this check when you are selecting which camera to use.

### What Can Be Done? Strategies for Fixed-Focus Cameras

Since you cannot control the lens, your entire strategy must shift from *controlling the camera* to **guiding the user** and **finding the least-blurry image possible**.

Your goal is to help the user position their finger at the camera's "sweet spot" of focus, even if that spot isn't perfect.

#### Strategy 1: User Guidance with Real-Time Sharpness Feedback

This is your most powerful tool. You must provide instant feedback to the user about the quality of the image being captured.

1.  **Implement Sharpness Analysis:** The `calculateSharpness` logic (using Laplacian variance) from the previous answer is **even more critical** here. You will run this analysis on every frame from the `ImageReader`.

2.  **Provide Visual Feedback:** Display the sharpness score on the screen or use a simpler visual cue.
    *   **Color Indicator:** The border of your fingerprint cutout overlay can change color:
        *   **Red:** Very blurry.
        *   **Yellow:** Getting better.
        *   **Green:** Best possible sharpness found.
    *   **On-Screen Text:** Display simple instructions like "Move finger further away" or "Move finger closer."

3.  **Automatic Capture:** The app shouldn't wait for the user to press a button. It should automatically capture the image the moment the sharpness score reaches its peak.

Here's how the logic inside your `ImageReader.OnImageAvailableListener` would adapt:

```kotlin
private var bestSharpnessSoFar = 0.0
private var bestImage: Image? = null // To hold the sharpest image

override fun onImageAvailable(reader: ImageReader) {
    val image = reader.acquireLatestImage() ?: return

    val currentSharpness = calculateSharpness(image)
    
    // Provide live feedback to the UI
    // (e.g., using a callback or LiveData to update the UI)
    uiFeedbackCallback.onSharpnessUpdate(currentSharpness)
    
    // The goal is to find the PEAK sharpness.
    // If sharpness is increasing, we're getting a better image.
    if (currentSharpness > bestSharpnessSoFar) {
        bestSharpnessSoFar = currentSharpness
        
        // Temporarily store this as the best image, but close the old one
        bestImage?.close() 
        bestImage = image
        
    } else {
        // If sharpness starts to decrease after peaking, it means the user moved past the best spot.
        // You could trigger the capture here, or wait for another peak.
        // For simplicity, we just close the current image.
        image.close()
    }

    // You need a mechanism to decide WHEN to capture. For example,
    // if there hasn't been a new "best" image for half a second,
    // assume the current `bestImage` is the one to use.
    // Then you would send `bestImage` for processing and reset `bestSharpnessSoFar`.
}
```

#### Strategy 2: Image Post-Processing

Because the source image will be soft, you can apply software sharpening filters to enhance the edges of the fingerprint ridges.

*   **Unsharp Mask:** This is a classic sharpening algorithm. Many image processing libraries (like OpenCV or RenderScript) provide implementations.
*   **Contrast Enhancement:** Increasing local contrast can also make the ridges more distinct.

**Warning:** Post-processing cannot create detail that wasn't captured in the first place. It can only enhance what is already there. Over-sharpening can introduce artifacts that might confuse your AI model.

#### Strategy 3: Adapt Your AI Model

This is a data science solution. If your app must support these low-end devices, your AI model **must be trained on images produced by them**.

*   **Collect a Diverse Dataset:** Capture fingerprint images from a variety of devices, including several fixed-focus models.
*   **Data Augmentation:** During training, apply blur (Gaussian blur) to your sharp images to simulate the output of fixed-focus cameras. This will make your model more robust and less sensitive to variations in image quality.

### Summary Workflow for a Fixed-Focus Scenario

1.  **On Startup:** Detect if the camera is fixed-focus using the `isFixedFocusCamera()` function.
2.  **Adapt UI:** If it is fixed-focus, switch to a "guidance UI" that includes a real-time sharpness indicator (e.g., a colored overlay).
3.  **Start Capture Session:** Begin streaming frames to your `ImageReader`.
4.  **Analyze and Guide:** In the `OnImageAvailableListener`, calculate the sharpness of every frame and update the UI to guide the user to move their finger to the optimal distance.
5.  **Auto-Capture:** Automatically select and save the frame with the highest sharpness score.
6.  **Process:** Send this "best possible" image to your pipeline, which may include a software sharpening step before it goes to the AI model.
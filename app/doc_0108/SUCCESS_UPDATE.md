Excellent idea! That's a very standard and user-friendly workflow. Adding a confirmation screen with the captured image gives the user confidence in the result and clear options for what to do next.

Here is the code and step-by-step guide to implement this functionality.

### Step 1: Update Your Layout (`fragment_camera.xml`)

First, we need to add the UI elements for the success screen. We'll add an `ImageView` to show the captured fingerprint and two `Button`s for "Recapture" and "Confirm". We will wrap them in a `ConstraintLayout` and set its initial visibility to `gone`.

Add this `ConstraintLayout` inside the root layout of your `fragment_camera.xml`, after the `viewFinder` and other overlays.

```xml
<!-- Add this block to your fragment_camera.xml -->
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@+id/success_layout"
    android:layout_width="0dp"
    android:layout_height="0dp"
    android:background="#B3000000"
    android:visibility="gone"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintTop_toTopOf="parent">

    <ImageView
        android:id="@+id/image_view_captured"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_margin="32dp"
        android:contentDescription="@string/captured_fingerprint_image"
        android:scaleType="fitCenter"
        app:layout_constraintBottom_toTopOf="@id/button_recapture"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        tools:src="@tools:sample/avatars" />

    <Button
        android:id="@+id/button_recapture"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginEnd="8dp"
        android:layout_marginBottom="16dp"
        android:text="@string/recapture"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toStartOf="@+id/button_confirm"
        app:layout_constraintStart_toStartOf="parent" />

    <Button
        android:id="@+id/button_confirm"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="16dp"
        android:text="@string/confirm"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toEndOf="@id/button_recapture" />

</androidx.constraintlayout.widget.ConstraintLayout>```
*(You'll need to add the `@string` resources for `captured_fingerprint_image`, `recapture`, and `confirm` in your `strings.xml` file.)*

### Step 2: Modify `CameraFragment.kt`

Now, let's add the logic to control the new UI, pause the preview, and handle the button clicks.

```kotlin
// ... other imports
import android.widget.Button
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isInvisible
// ...

@AndroidEntryPoint
class CameraFragment : Fragment() {

    // ... (keep all your existing properties)

    // --- Add references for the new UI elements ---
    private lateinit var successLayout: ConstraintLayout
    private lateinit var capturedImageView: ImageView
    private lateinit var recaptureButton: Button
    private lateinit var confirmButton: Button


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // --- Initialize your new UI elements ---
        successLayout = fragmentCameraBinding.successLayout
        capturedImageView = fragmentCameraBinding.imageViewCaptured
        recaptureButton = fragmentCameraBinding.buttonRecapture
        confirmButton = fragmentCameraBinding.buttonConfirm

        // ... (rest of your existing onViewCreated code)

        setupClickListeners() // Call the new method to set up button clicks
    }
    
    // --- New method to set up click listeners ---
    private fun setupClickListeners() {
        recaptureButton.setOnClickListener {
            // Hide the success screen
            successLayout.visibility = View.GONE
            
            // Clear the buffer and reset the processing state
            cameraViewModel.clearProcessingState() // We'll add this to the ViewModel
            
            // Resume the camera preview
            resumePreview()
        }

        confirmButton.setOnClickListener {
            // Handle the confirmation action, e.g., navigate back with the result
            Toast.makeText(requireContext(), "Fingerprint Confirmed!", Toast.LENGTH_SHORT).show()
            activity?.supportFragmentManager?.popBackStack()
        }
    }

    // --- Method to pause the camera preview ---
    private fun pausePreview() {
        try {
            captureSession.stopRepeating()
            Log.d(TAG, "Camera preview paused.")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to pause camera preview.", e)
        }
    }

    // --- Method to resume the camera preview ---
    private fun resumePreview() {
        try {
            // Use the same repeating request builder you configured earlier
            captureSession.setRepeatingRequest(
                captureRequestBuilder.build(),
                null,
                cameraPreviewHandler
            )
            Log.d(TAG, "Camera preview resumed.")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to resume camera preview.", e)
        }
    }

    // --- Update your UI state handling logic ---
    private fun updateUIForState(state: UIState) {
        val biometricOverlay = fragmentCameraBinding.biometricOverlayViewTop
        
        // Hide success layout by default unless in SUCCESS state
        if (state != UIState.SUCCESS) {
            successLayout.visibility = View.GONE
        }
        
        when (state) {
            UIState.INITIAL -> {
                // ... (your existing code)
            }

            UIState.VALIDATION -> {
                // ... (your existing code)
            }

            UIState.SUCCESS -> {
                // Green, Solid, No animation
                biometricOverlay.setColor(Color.GREEN)
                biometricOverlay.setStyle(BiometricOverlayView.OverlayStyle.SOLID)
                biometricOverlay.setAnimationEnabled(false)
                fragmentCameraBinding.biometricOverlayHeading.text =
                    getString(`in`.gov.uidai.capture.R.string.heading_success_state)
                Log.d(TAG, "State: Success")

                // --- NEW LOGIC FOR SUCCESS STATE ---
                // 1. Pause the camera preview to freeze the screen
                pausePreview()

                // 2. Get the best image from the processor's buffer
                val bestImage = cameraViewModel.getProcessedImages().firstOrNull()
                bestImage?.finalBitmap?.let {
                    capturedImageView.setImageBitmap(it)
                }

                // 3. Show the success layout
                successLayout.visibility = View.VISIBLE
            }
        }
    }

    // ... (rest of your CameraFragment code)
}
```

### Step 3: Update `CameraViewModel.kt`

We need a way for the Fragment to easily tell the `ImageProcessor` to clear its buffer and reset the state. The ViewModel is the perfect place for this.

```kotlin
// In your CameraViewModel.kt

// ... (other viewmodel code)

// Assume you have a reference to your ImageProcessor instance accessible here
// If not, you'll need to pass it to the ViewModel or have a shared instance.
// For this example, let's assume you pass it during initialization.
// A cleaner way would be to inject it with Hilt.

// Let's create a placeholder for the processor
// You should replace this with your actual processor instance.
private lateinit var imageProcessor: ImageProcessor

fun setImageProcessor(processor: ImageProcessor) {
    this.imageProcessor = processor
}


// Method to get the final images
fun getProcessedImages(): List<ProcessedImage> {
    // Delegate the call to the image processor
    return imageProcessor.getProcessedImages()
}

// Method to clear the state, called on "Recapture"
fun clearProcessingState() {
    // Delegate the call to the image processor
    imageProcessor.clearBuffer()
}

// ... (rest of your viewmodel code)
```

**Finally, connect this in `CameraFragment`:**

In `CameraFragment.kt`'s `onViewCreated`, after you initialize your `imageProcessor`, make sure the `ViewModel` has a reference to it.

```kotlin
// In CameraFragment.kt -> onViewCreated()
val imageProcessor = ImageProcessor(
    cameraViewModel,
    lifecycleScope,
    tfLiteInterpreter
).apply {
    // ... your existing setup
}

// Give the ViewModel a reference to the processor
cameraViewModel.setImageProcessor(imageProcessor)

imageReader.setOnImageAvailableListener(
    imageProcessor, imageReaderHandler
)
```

### Summary of Changes

1.  **UI:** A new layout (`success_layout`) is added, containing an `ImageView` and two `Button`s. It's hidden by default.
2.  **State Change (`UIState.SUCCESS`):**
    *   The `updateUIForState` function now handles the `SUCCESS` case.
    *   It calls `pausePreview()` to stop the camera feed.
    *   It fetches the best image from the `ImageProcessor` (via the `ViewModel`) and displays it in the `ImageView`.
    *   It makes the `success_layout` visible.
3.  **User Actions:**
    *   The **"Recapture"** button calls `clearProcessingState()` on the `ViewModel`, which tells the `ImageProcessor` to clear its buffer and reset the UI state back to `INITIAL`. It then calls `resumePreview()` to restart the camera.
    *   The **"Confirm"** button can now be used to finalize the process and navigate away.
4.  **Camera Control:** New `pausePreview()` and `resumePreview()` methods give you direct control over the camera's repeating request, allowing you to freeze and unfreeze the live feed efficiently.
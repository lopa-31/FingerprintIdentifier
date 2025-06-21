# Fingerprint Identifier

This is a native Android application that uses the device's camera to perform real-time hand and finger detection. The application identifies the user's palm, validates individual fingers against the initial scan, and saves the finger images. The core computer vision tasks are implemented using Google's MediaPipe framework.

## Tech Stack

- **Language**: Kotlin
- **Architecture**: Model-View-ViewModel (MVVM)
- **Asynchronous Programming**: Kotlin Coroutines
- **UI**: Android SDK, Material Components
- **Camera**: CameraX
- **Machine Learning / CV**:
    - **Google MediaPipe**: For real-time hand landmark detection.
    - **OpenCV**: For image processing tasks like blur detection.

## Project Setup

### Prerequisites
- Android Studio Iguana | 2023.2.1 or newer.
- An Android device with a camera.

### Instructions

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/FingerprintIdentifier.git
    ```
2.  **Open in Android Studio:**
    - Open Android Studio.
    - Click on `File` > `Open`.
    - Navigate to the cloned repository folder and select it.
3.  **Gradle Sync:**
    - Android Studio will automatically start building the project and downloading the required dependencies using Gradle. This might take a few minutes.
4.  **Run the application:**
    - Connect your Android device or start an emulator.
    - Click the 'Run' button (▶️) in Android Studio.
    - The app will request **Camera** permissions at runtime. Please grant them to use the app.

The project includes the required MediaPipe model (`hand_landmarker.task`) in the `app/src/main/assets` directory, so no additional setup is needed for the ML model.


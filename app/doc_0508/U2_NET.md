Dependencies in build.gradle (app)
```
implementation 'org.tensorflow:tensorflow-lite:2.13.0' // or compatible version
implementation 'org.tensorflow:tensorflow-lite-gpu:2.13.0'
implementation 'org.tensorflow:tensorflow-lite-support:0.3.1
```

MainActivity.kt (Core parts)
```
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var interpreter: Interpreter
    private val modelInputSize = 320

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        interpreter = Interpreter(loadModelFile("u2net_quant_float16.tflite"))

        // Load image
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.test_finger)
        val preprocessed = preprocessImage(bitmap)

        // Run inference
        val output = runInference(preprocessed)

        // Postprocess
        val maskBitmap = postprocess(output)

        // Show mask
        findViewById<ImageView>(R.id.resultView).setImageBitmap(maskBitmap)
    }

    // Load .tflite from assets
    private fun loadModelFile(filename: String): ByteBuffer {
        val fileDescriptor = assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * modelInputSize * modelInputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(modelInputSize * modelInputSize)
        resized.getPixels(intValues, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        return byteBuffer
    }

    private fun runInference(input: ByteBuffer): Array<Array<Array<FloatArray>>> {
        val output = Array(1) { Array(320) { Array(320) { FloatArray(1) } } }
        interpreter.run(input, output)
        return output
    }

    private fun postprocess(output: Array<Array<Array<FloatArray>>>): Bitmap {
        val mask = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)

        // Threshold and write to mask
        for (y in 0 until 320) {
            for (x in 0 until 320) {
                val prob = output[0][y][x][0]
                val binary = if (prob > 0.5f) 255 else 0
                val pixel = (0xFF shl 24) or (binary shl 16) or (binary shl 8) or binary
                mask.setPixel(x, y, pixel)
            }
        }

        // Optional: Centering (bounding box logic)
        return centerAlignMask(mask)
    }

    private fun centerAlignMask(mask: Bitmap): Bitmap {
        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x] and 0xFF
                if (pixel > 0) {
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                }
            }
        }

        if (minX >= maxX || minY >= maxY) return mask

        val cropped = Bitmap.createBitmap(mask, minX, minY, maxX - minX + 1, maxY - minY + 1)
        val centered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val offsetX = (width - cropped.width) / 2
        val offsetY = (height - cropped.height) / 2

        val canvas = android.graphics.Canvas(centered)
        canvas.drawBitmap(cropped, offsetX.toFloat(), offsetY.toFloat(), null)

        return centered
    }
}
```




Add Model to Assets
```
Place your quantized TFLite model in app/src/main/assets/
```

U2net_quant_float16.tflite

Layout Example (activity_main.xml)
```
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    
    <ImageView
        android:id="@+id/resultView"
        android:layout_width="320dp"
        android:layout_height="320dp"
        android:layout_centerInParent="true" />
</RelativeLayout>
```



Saving the Centered Mask
You can use this utility to save the Bitmap (centered mask) to internal storage:
```
private fun saveBitmapToStorage(bitmap: Bitmap, filename: String): File {
    val dir = File(getExternalFilesDir(null), "segment_outputs")
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, filename)

    val outputStream = FileOutputStream(file)
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    outputStream.flush()
    outputStream.close()

    return file
}
```






Usage:
```
val centeredMask = centerAlignMask(predictedMask)
val savedFile = saveBitmapToStorage(centeredMask, "centered_mask.png")
Log.d("MASK_SAVED", "Saved to: ${savedFile.absolutePath}")
```

This will store the mask inside:/storage/emulated/0/Android/data/your.app.package/files/segment_outputs/centered_mask.png



Auto-Cropping the Fingerprint Region
To extract just the bounding box of the fingerprint mask as a new bitmap:
```
private fun autoCropFromMask(mask: Bitmap, originalImage: Bitmap): Bitmap {
    val width = mask.width
    val height = mask.height
    val pixels = IntArray(width * height)
    mask.getPixels(pixels, 0, width, 0, 0, width, height)

    var minX = width
    var minY = height
    var maxX = 0
    var maxY = 0

    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = pixels[y * width + x] and 0xFF
            if (pixel > 0) {
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }
    }

    // Handle empty mask
    if (minX >= maxX || minY >= maxY) return originalImage

    // Crop the same area from original image
    return Bitmap.createBitmap(originalImage, minX, minY, maxX - minX + 1, maxY - minY + 1)
}
```


Usage:
```
val croppedFingerprint = autoCropFromMask(centeredMask, inputImage)
val croppedFile = saveBitmapToStorage(croppedFingerprint, "cropped_fingerprint.png")
```

✅ Final Integration: After Inference
In your pipeline after you get predictedMask:
```
val centeredMask = centerAlignMask(predictedMask)
saveBitmapToStorage(centeredMask, "centered_mask.png")

val croppedFingerprint = autoCropFromMask(centeredMask, originalImage)
saveBitmapToStorage(croppedFingerprint, "cropped_fingerprint.png")
```








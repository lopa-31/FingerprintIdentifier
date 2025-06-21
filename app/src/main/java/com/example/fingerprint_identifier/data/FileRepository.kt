package com.example.fingerprint_identifier.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import com.example.fingerprint_identifier.analyzer.HandType
import java.text.SimpleDateFormat
import java.util.*

class FileRepository(private val context: Context) {

    private val nameDateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)

    fun createImageFileOptions(handType: HandType, fingerName: String): ImageCapture.OutputFileOptions {
        // Example format: "Right_Hand_Thumb_Finger_JohnDoe_2023-10-27-10-30-00-123.jpg"
        val timeStamp = nameDateFormat.format(System.currentTimeMillis())
        val fileName = "${handType}_${fingerName}_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // This saves to the "Pictures/Finger Data" directory.
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Finger Data")
            }
        }

        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
    }

    fun saveBitmapAndGetUri(bitmap: android.graphics.Bitmap, captureName: String): android.net.Uri {
        val timeStamp = nameDateFormat.format(System.currentTimeMillis())
        val fileName = "${captureName}_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Finger Data")
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            val outputStream = context.contentResolver.openOutputStream(it)
            outputStream?.use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, stream)
            }
        }
        return uri!!
    }

    fun deleteFile(uri: android.net.Uri) {
        context.contentResolver.delete(uri, null, null)
    }
} 
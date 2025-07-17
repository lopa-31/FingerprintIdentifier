import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves a Bitmap to the device's public Pictures directory.
 *
 * @param context The application context.
 * @param bitmap The bitmap image to save.
 * @param displayName The desired name for the file (e.g., "cropped_image.jpg").
 * @return The Uri of the saved image, or null on failure.
 */
fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    displayName: String = "cropped_image_${System.currentTimeMillis()}.jpg"
): Uri? {
    val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(imageCollection, contentValues)

    uri?.let {
        try {
            val outputStream: OutputStream? = resolver.openOutputStream(it)
            outputStream?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    // Compression failed
                    return null
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }

            return it
        } catch (e: Exception) {
            // If something goes wrong, delete the incomplete entry
            resolver.delete(it, null, null)
            e.printStackTrace()
            return null
        }
    }
    return null
}
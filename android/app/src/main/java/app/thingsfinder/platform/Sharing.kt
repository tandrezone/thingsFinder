package app.thingsfinder.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** FileProvider-backed temp files under cache/shared/ (see res/xml/file_paths.xml). */
object Sharing {
    private fun authority(context: Context) = "${context.packageName}.files"

    private fun sharedDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }

    /** A fresh content:// URI for the camera to write an OCR photo into. The previous photo is overwritten. */
    fun newPhotoUri(context: Context): Uri {
        val file = File(sharedDir(context), "ocr-photo.jpg")
        if (file.exists()) file.delete()
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /** Deletes the temporary OCR photo — like the web app, the photo itself is never kept. */
    fun deletePhoto(context: Context) {
        File(sharedDir(context), "ocr-photo.jpg").delete()
    }

    suspend fun sharePng(context: Context, bitmap: Bitmap, fileName: String, title: String) {
        val uri = withContext(Dispatchers.IO) {
            val file = File(sharedDir(context), fileName)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, authority(context), file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

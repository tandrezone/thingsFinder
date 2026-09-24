package app.thingsfinder.platform

import android.content.Context
import android.net.Uri
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.OcrLines
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * "Add items from a photo": the web app shells out to Tesseract on the
 * server; here ML Kit's bundled Latin text recognizer runs entirely on the
 * phone (no network, the photo is never uploaded). Like the web version,
 * this reads *text* — point it at a written or printed list.
 */
class TextScanner(private val context: Context) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Returns the candidate lines, or throws with a readable message if the image can't be read. */
    suspend fun extractItems(uri: Uri): List<ItemDraft> {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't read that photo.", e)
        }
        val text = recognizer.process(image).await()
        return OcrLines.parse(text.text)
    }
}

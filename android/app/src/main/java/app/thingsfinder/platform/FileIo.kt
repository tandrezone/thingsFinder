package app.thingsfinder.platform

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Storage Access Framework reads/writes, kept out of the ViewModels so they stay Context-free. */
interface FileIo {
    suspend fun writeText(uri: Uri, text: String)
    suspend fun readText(uri: Uri, maxBytes: Int = 20 * 1024 * 1024): String
}

class ContentResolverFileIo(context: Context) : FileIo {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        // "wt" truncates, so re-exporting over an existing file never leaves stale bytes at the end.
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Can't open $uri for writing")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun readText(uri: Uri, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Can't open $uri")
        input.use {
            val bytes = it.readAtMost(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("File too large")
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}

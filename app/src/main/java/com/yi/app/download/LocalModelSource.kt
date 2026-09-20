package com.yi.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * User-chosen model directory (SAF tree) — checked BEFORE offering a download.
 * llama.cpp opens models by path, so SAF-picked files are copied into
 * app-private storage; direct filesystem paths are reused when the picked tree
 * maps onto real storage (externalstorage.documents trees usually do).
 */
class LocalModelSource(private val context: Context) {
    private val prefs = context.getSharedPreferences("model_source", Context.MODE_PRIVATE)

    fun saveDir(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        prefs.edit().putString(KEY_DIR, uri.toString()).apply()
    }

    fun savedDirUri(): Uri? = prefs.getString(KEY_DIR, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun clearDir() = prefs.edit().remove(KEY_DIR).apply()

    /** All .gguf files in the saved tree, largest first. */
    suspend fun listGgufs(): List<DocumentFile> = withContext(Dispatchers.IO) {
        val rootUri = savedDirUri() ?: return@withContext emptyList()
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext emptyList()
        root.listFiles()
            .filter { it.isFile && it.name?.endsWith(".gguf", ignoreCase = true) == true }
            .sortedByDescending { it.length() }
    }

    /** Direct filesystem path when the picked tree maps onto real storage. */
    fun directPath(doc: DocumentFile): String? {
        val uri = doc.uri
        if (uri.scheme != "content") return null
        val seg = uri.lastPathSegment ?: return null
        // content://com.android.externalstorage.documents/tree/…/document/primary%3AModels%2Hy-MT2…gguf
        val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
        if (!decoded.contains(":")) return null
        return File("/storage/emulated/0", decoded.substringAfter(':'))
            .takeIf { it.isFile }?.absolutePath
    }

    /**
     * Copy a SAF-picked file into app-private storage (llama.cpp needs a real
     * path; content:// cannot be mmapped).
     */
    suspend fun copyIntoAppDir(doc: DocumentFile, targetName: String): File = withContext(Dispatchers.IO) {
        val dest = File(context.filesDir, "models/imported/$targetName")
        dest.parentFile?.mkdirs()
        val part = File(dest.absolutePath + ".part")
        context.contentResolver.openInputStream(doc.uri)?.use { ins ->
            part.outputStream().use { out ->
                ins.copyTo(out, 256 shl 10)
            }
        } ?: throw java.io.IOException("cannot open ${doc.uri}")
        check(part.renameTo(dest)) { "rename ${part.name} failed" }
        dest
    }

    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1048576)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_DIR = "model_dir_uri"
    }
}

package com.yair.printer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Puts a finished scan somewhere the rest of the phone can reach it: the public
 * Downloads folder, so any app can open it later, and a private cache copy that
 * backs the share sheet (a MediaStore item cannot be shared directly without
 * handing out a permission this app has no reason to hold).
 */
object Saver {

    data class Saved(val name: String, val downloads: Uri?, val shareable: Uri?, val bytes: Int)

    fun save(context: Context, page: Escl.Page, index: Int, total: Int): Saved {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val suffix = if (total > 1) "-${index + 1}" else ""
        val name = "scan-$stamp$suffix.${page.format.ext}"

        val downloads = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, page.format.mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

            uri?.also {
                resolver.openOutputStream(it)?.use { out -> out.write(page.bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(it, values, null, null)
            }
        }.getOrNull()

        val shareable = runCatching {
            val dir = File(context.cacheDir, "scans").apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(page.bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()

        return Saved(name, downloads, shareable, page.bytes.size)
    }

    fun shareIntent(saved: Saved, mime: String): Intent? {
        val uri = saved.shareable ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openIntent(saved: Saved, mime: String): Intent? {
        val uri = saved.shareable ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Only used in the label under a saved file, so the folder reads plainly. */
    val downloadsLabel: String = Environment.DIRECTORY_DOWNLOADS
}

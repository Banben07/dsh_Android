package dev.harness.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.harness.core.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DraftAttachment(
    val id: String, val uri: Uri, val name: String, val mimeType: String, val size: Long?,
    val uploaded: UploadedFile? = null, val transferred: Long = 0, val status: String = "待发送",
    val error: String? = null,
) {
    val isImage get() = mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    fun source(resolver: ContentResolver) = UploadSource(name, size) {
        resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件，请重新选择")
    }
}

suspend fun readAttachment(resolver: ContentResolver, uri: Uri): DraftAttachment = withContext(Dispatchers.IO) {
    var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "附件" }
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex)?.ifBlank { name } ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
        }
    }
    val mime = resolver.getType(uri)?.lowercase()?.takeUnless { it == "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    DraftAttachment(java.util.UUID.randomUUID().toString(), uri, name, mime, size)
}

fun sizeLabel(bytes: Long?): String = when {
    bytes == null -> "大小未知"
    bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}

fun decodePreview(open: () -> java.io.InputStream, target: Int = 512): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target) sample *= 2
    return open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

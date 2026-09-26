package dev.harness.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.harness.android.*
import dev.harness.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DraftAttachments(attachments: List<DraftAttachment>, sending: Boolean, remove: (String) -> Unit) {
    if (attachments.isEmpty()) return
    val resolver = LocalContext.current.contentResolver
    Column(Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        attachments.forEach { file ->
            key(file.id) {
                val preview by produceState<Bitmap?>(null, file.uri) {
                    if (file.mimeType.startsWith("image/")) value = withContext(Dispatchers.IO) { runCatching { decodePreview(file.source(resolver).open, 160) }.getOrNull() }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (preview != null) Image(preview!!.asImageBitmap(), file.name, Modifier.size(42.dp), contentScale = ContentScale.Fit)
                    else Icon(if (file.isImage) Icons.Default.Image else Icons.Default.Description, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(if (file.error != null) file.error else "${if (file.isImage) "图片" else "文件"} · ${sizeLabel(file.size)} · ${file.status}",
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                            color = if (file.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sending && file.status.startsWith("正在")) {
                            if (file.size != null && file.size > 0) LinearProgressIndicator(progress = { (file.transferred.toFloat() / file.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                            Text("已传输 ${sizeLabel(file.transferred)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = { remove(file.id) }, enabled = !sending) { Icon(Icons.Default.Close, "移除 ${file.name}", Modifier.size(17.dp)) }
                }
            }
        }
    }
}

@Composable
fun MessageAttachments(attachments: List<DisplayAttachment>, loadImage: (suspend (String) -> ByteArray)?) {
    attachments.forEach { attachment ->
        key(attachment.id) {
            var showImage by remember { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = attachment.kind == "image" && loadImage != null) { showImage = true }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (attachment.kind == "image") Icons.Default.Image else Icons.Default.Description, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(attachment.name, style = MaterialTheme.typography.bodySmall)
                        Text("${sizeLabel(attachment.bytes)}${if (attachment.kind == "image") " · 点击查看" else " · 已发送"}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (showImage && loadImage != null) {
                val preview by produceState<Result<Bitmap>?>(null, attachment.id) {
                    value = try {
                        val bytes = loadImage(attachment.id)
                        Result.success(withContext(Dispatchers.Default) { decodePreview({ bytes.inputStream() }, 1400) } ?: error("无法解码图片"))
                    } catch (e: Exception) { if (e is CancellationException) throw e; Result.failure(e) }
                }
                Dialog(onDismissRequest = { showImage = false }) {
                    Surface(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(attachment.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(12.dp))
                            if (preview == null) CircularProgressIndicator(Modifier.padding(32.dp))
                            preview?.getOrNull()?.let { Image(it.asImageBitmap(), attachment.name, Modifier.fillMaxWidth().heightIn(max = 500.dp), contentScale = ContentScale.Fit) }
                            preview?.exceptionOrNull()?.let { Text(it.message ?: "图片加载失败", color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { showImage = false }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}

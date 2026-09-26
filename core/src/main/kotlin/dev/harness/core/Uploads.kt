package dev.harness.core

import java.io.FilterOutputStream
import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

data class UploadSource(val name: String, val size: Long?, val open: () -> InputStream)
data class UploadedFile(val receiptId: String, val name: String, val bytes: Long)

sealed interface PromptPart {
    data class Value(val value: JsonObject) : PromptPart
    data class Image(val source: UploadSource, val mediaType: String) : PromptPart
}

internal fun copyUpload(source: UploadSource, output: java.io.OutputStream, progress: (Long) -> Unit) {
    source.open().use { input -> copyTransfer(input, output, progress) }
}

internal fun copyTransfer(input: InputStream, output: java.io.OutputStream, progress: (Long) -> Unit): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var reportedAt = 0L
    progress(0)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        total += count
        val now = System.nanoTime()
        if (now - reportedAt >= 100_000_000L) { progress(total); reportedAt = now }
    }
    progress(total)
    return total
}

internal class FileUploadBody(private val source: UploadSource, private val progress: (Long) -> Unit) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = source.size ?: -1L
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = copyUpload(source, sink.outputStream(), progress)
}

/** Encode image bytes directly into the request stream instead of retaining large base64 strings. */
internal class PromptBody(
    private val rpcId: String,
    private val request: JsonObject,
    private val parts: List<PromptPart>,
    private val progress: (Int, Long) -> Unit,
    private val command: Boolean = false,
) : RequestBody() {
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        val method = if (command) "commands/execute" else "session/prompt"
        sink.writeUtf8("{\"type\":\"client-request\",\"rpcId\":${str(rpcId)},\"method\":${str(method)},\"payload\":{\"args\":")
        if (!command) sink.writeUtf8("{\"request\":")
        sink.writeUtf8(request.toString().dropLast(1) + ",\"${if (command) "submittedAttachments" else "content"}\":[")
        parts.forEachIndexed { index, part ->
            if (index > 0) sink.writeUtf8(",")
            when (part) {
                is PromptPart.Value -> sink.writeUtf8(part.value.toString())
                is PromptPart.Image -> {
                    sink.writeUtf8("{\"type\":\"image\",\"mediaType\":${str(part.mediaType)},\"name\":${str(part.source.name)},\"data\":\"")
                    val unclosed = object : FilterOutputStream(sink.outputStream()) {
                        override fun close() { flush() }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
                    }
                    Base64.getEncoder().wrap(unclosed).use { output -> copyUpload(part.source, output) { progress(index, it) } }
                    sink.writeUtf8("\"}")
                }
            }
        }
        sink.writeUtf8(if (command) "]}}}" else "]}}}}")
    }
}

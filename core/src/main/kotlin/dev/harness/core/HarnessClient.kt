package dev.harness.core

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class HarnessException(val code: String, message: String) : IOException(message)

/** Implements the real harness Connection RPC envelope and Gateway mux, not a model-provider API. */
class HarnessClient(val address: ServerAddress, cookies: CookieJar) : Closeable {
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false) // Never automatically repeat a prompt/approval POST.
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val transferHttp = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()

    suspend fun login(token: String) {
        require(token.isNotBlank()) { "请输入启动链接中的 token" }
        val login = address.base.newBuilder().addQueryParameter("token", token).build()
        val issuedCookie = http.newCall(request(login).get().build()).readResponse { response ->
            if (response.code !in listOf(200, 302, 303)) throw response.failure()
            // The cookie jar receives Set-Cookie even though redirects are deliberately disabled.
            response.headers("Set-Cookie").isNotEmpty()
        }
        // A still-valid cookie may produce a clean-root redirect without minting another cookie.
        // Verify it through an authenticated RPC; a redirect or HTML page alone is not proof of login.
        if (!issuedCookie) listSessions()
    }

    suspend fun probe(): Int = http.newCall(request(address.base).get().build()).readResponse { it.code }

    suspend fun rpc(endpoint: String, args: JsonObject = emptyObject): JsonElement {
        val id = UUID.randomUUID().toString()
        val payload = jsonObject(
            "type" to str("client-request"), "rpcId" to str(id), "method" to str(endpoint),
            "payload" to jsonObject("args" to args),
        )
        return executeRpc(endpoint, id, payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    }

    private suspend fun executeRpc(endpoint: String, id: String, body: RequestBody, transport: OkHttpClient = http): JsonElement {
        val url = address.base.newBuilder().addPathSegments("api/$endpoint").build()
        val req = request(url).post(body).build()
        return transport.newCall(req).readResponse { response ->
            if (!response.isSuccessful) throw response.failure()
            val body = response.body?.string().orEmpty()
            val envelope = try { parseObject(body) } catch (_: Exception) {
                throw HarnessException("protocol/json", "服务器返回了非 JSON 响应，请检查地址是否指向 harness 服务")
            }
            if (envelope.text("type") != "server-response" || envelope.text("rpcId") != id) {
                throw HarnessException("protocol/envelope", "服务器 RPC 协议不匹配，请确认 harness 版本")
            }
            val result = envelope["result"].obj()
            if (!result.flag("ok")) {
                val err = result["error"].obj()
                throw HarnessException(err.text("code"), err.text("message").ifBlank { "服务器拒绝了请求" })
            }
            result["value"] ?: JsonNull
        }
    }

    suspend fun uploadFile(sessionId: String, source: UploadSource, progress: (Long) -> Unit = {}): UploadedFile {
        val url = address.base.newBuilder().addPathSegments("api/session/uploadFileBinary")
            .addQueryParameter("sessionId", sessionId).addQueryParameter("name", source.name).build()
        return transferHttp.newCall(request(url).post(FileUploadBody(source, progress)).build()).readResponse { response ->
            if (!response.isSuccessful) throw response.failure()
            val result = try { parseObject(response.body?.string().orEmpty()) } catch (_: Exception) {
                throw HarnessException("protocol/upload", "上传接口返回了无效响应，请确认后端支持文件上传")
            }
            if (!result.flag("ok")) {
                val error = result["error"].obj()
                throw HarnessException(error.text("code").ifBlank { "upload/failed" }, error.text("message").ifBlank { "文件上传失败" })
            }
            val value = result["value"].obj(); val file = value["file"].obj()
            if (value.text("receiptId").isBlank()) throw HarnessException("protocol/upload", "服务器没有返回文件上传凭据")
            UploadedFile(value.text("receiptId"), file.text("name").ifBlank { source.name }, file.long("bytes", source.size ?: 0))
        }
    }

    suspend fun prompt(request: JsonObject, content: List<PromptPart>, progress: (Int, Long) -> Unit = { _, _ -> }): JsonElement {
        val id = UUID.randomUUID().toString()
        return executeRpc("session/prompt", id, PromptBody(id, request, content, progress), transferHttp)
    }
    suspend fun executeCommand(agentId: String, line: String, attachments: List<PromptPart>, progress: (Int, Long) -> Unit = { _, _ -> }): JsonElement {
        val id = UUID.randomUUID().toString()
        val args = jsonObject("agentId" to str(agentId), "line" to str(line))
        return executeRpc("commands/execute", id, PromptBody(id, args, attachments, progress, command = true), transferHttp.newBuilder().readTimeout(0, TimeUnit.SECONDS).build())
    }

    /** Open the destination only after a successful authenticated ZIP response. Never buffer the archive. */
    suspend fun exportSession(sessionId: String, destination: () -> OutputStream, progress: (Long) -> Unit = {}): Long {
        val url = address.base.newBuilder().addPathSegments("api/session.export")
            .addQueryParameter("sessionId", sessionId).addQueryParameter("includeDescendants", "true").build()
        return transferHttp.newCall(request(url).get().build()).readResponse { response ->
            if (!response.isSuccessful) throw response.failure()
            val body = response.body ?: throw HarnessException("export/empty", "服务器没有返回会话文件")
            if (body.contentType()?.subtype != "zip") throw HarnessException("export/format", "导出接口没有返回 ZIP 文件，请检查后端版本")
            destination().use { output -> body.byteStream().use { input -> copyTransfer(input, output, progress) } }
        }
    }

    suspend fun command(endpoint: String, request: JsonObject): JsonElement = rpc(endpoint, jsonObject("request" to request))
    // The generated Session Controller names this one parameter `_request` on the wire.
    suspend fun listSessions(): JsonObject = rpc("session/list", jsonObject("_request" to emptyObject)).obj()
    fun mux(): HarnessMux = HarnessMux(http, request(address.base.newBuilder().addPathSegments("api/remote.mux").build()).build())
    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Origin", address.origin).header("User-Agent", "HarnessAndroid/0.2")
    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}

class HarnessMux(http: OkHttpClient, request: Request) : Closeable {
    val frames = Channel<JsonObject>(1024)
    @Volatile private var closed = false
    private val socket = http.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(openFrame("events", "\$events", emptyObject).toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                if (!frames.trySend(parseObject(text)).isSuccess && !closed) {
                    frames.close(HarnessException("stream/overflow", "实时消息过多，正在重新同步"))
                    webSocket.cancel()
                }
            } catch (_: Exception) {
                frames.close(HarnessException("protocol/frame", "无法读取实时数据，请检查 harness 版本"))
                webSocket.cancel()
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            frames.close(response?.failure() ?: t)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            frames.close(if (closed) null else HarnessException("stream/closed", "连接已断开"))
        }
    })
    fun open(id: String, endpoint: String, args: JsonObject = emptyObject) {
        check(socket.send(openFrame(id, endpoint, args).toString())) { "连接已断开" }
    }
    fun cancel(id: String) { socket.send(jsonObject("type" to str("cancel"), "streamId" to str(id)).toString()) }
    override fun close() { closed = true; socket.cancel(); frames.close() }
    companion object {
        fun openFrame(id: String, endpoint: String, args: JsonObject) = jsonObject(
            "type" to str("open"), "streamId" to str(id), "endpoint" to str(endpoint),
            "payload" to jsonObject("args" to args),
        )
    }
}

/** Read and decode the response on OkHttp's worker, keeping cancellation active through the entire body. */
internal suspend fun <T> Call.readResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                if (continuation.isActive) continuation.resume(value)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

private fun Response.failure(): HarnessException = when (code) {
    401 -> HarnessException("auth/required", "登录已失效，请粘贴 harness 启动时显示的带 token 链接重新连接")
    403 -> HarnessException("auth/host", "服务拒绝了这个地址，请检查 harness 的 trustedHosts 是否包含当前 IP:端口")
    404 -> HarnessException("protocol/not-found", "没有找到 harness 接口，请检查端口和后端版本")
    else -> HarnessException("http/$code", "服务器请求失败（HTTP $code）")
}

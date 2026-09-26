package dev.harness.core

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Test

class UploadsTest {
    @Test fun `file upload sends exact bytes with encoded name and session binding`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(200_003) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody("""{"ok":true,"value":{"receiptId":"receipt-1","file":{"name":"测试 & report.pdf","bytes":200003}}}"""))
            HarnessClient(ServerAddress.parse(server.url("/").toString()), CookieJar.NO_COOKIES).use { api ->
                var progress = 0L
                val result = api.uploadFile("session & 1", UploadSource("测试 & report.pdf", bytes.size.toLong()) { bytes.inputStream() }) { progress = it }
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/api/session/uploadFileBinary", request.requestUrl!!.encodedPath)
                assertEquals("session & 1", request.requestUrl!!.queryParameter("sessionId"))
                assertEquals("测试 & report.pdf", request.requestUrl!!.queryParameter("name"))
                assertEquals("application/octet-stream", request.getHeader("Content-Type"))
                assertContentEquals(bytes, request.body.readByteArray())
                assertEquals("receipt-1", result.receiptId)
                assertEquals(bytes.size.toLong(), progress)
            }
        }
    }
    @Test fun `mixed attachment prompt streams base64 without corrupting later JSON`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = echoRpc()
            val bytes = ByteArray(131_073) { (it % 127).toByte() }
            HarnessClient(ServerAddress.parse(server.url("/").toString()), CookieJar.NO_COOKIES).use { api ->
                api.prompt(jsonObject("sessionId" to str("s1"), "requestId" to str("p1"), "mode" to str("queue")), listOf(
                    PromptPart.Image(UploadSource("图\"片.png", null) { bytes.inputStream() }, "image/png"),
                    PromptPart.Value(jsonObject("type" to str("file"), "receiptId" to str("r1"))),
                    PromptPart.Value(jsonObject("type" to str("text"), "text" to str("分析这些文件\n请保留格式"))),
                ))
                val r = server.takeRequest(2, TimeUnit.SECONDS)!!
                val content = parseObject(r.body.readUtf8())["payload"].obj()["args"].obj()["request"].obj()["content"].array()
                assertEquals(3, content.size)
                assertContentEquals(bytes, Base64.getDecoder().decode(content[0].obj().text("data")))
                assertEquals("图\"片.png", content[0].obj().text("name"))
                assertEquals("r1", content[1].obj().text("receiptId"))
                assertEquals("分析这些文件\n请保留格式", content[2].obj().text("text"))
            }
        }
    }
    @Test fun `slash commands use execute endpoint and explicit attachment argument`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.dispatcher = echoRpc()
            HarnessClient(ServerAddress.parse(server.url("/").toString()), CookieJar.NO_COOKIES).use { api ->
                api.executeCommand("s1", "/compact", emptyList())
                val r = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/api/commands/execute", r.path)
                val args = parseObject(r.body.readUtf8())["payload"].obj()["args"].obj()
                assertEquals(setOf("agentId", "line", "submittedAttachments"), args.keys)
                assertEquals("/compact", args.text("line"))
                assertEquals("s1", args.text("agentId"))
                assertEquals(JsonArray(emptyList()), args["submittedAttachments"])
            }
        }
    }
    @Test fun `upload rejection stays an error and returns no usable receipt`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"ok":false,"error":{"code":"session/attachment-invalid","message":"file too large"}}"""))
            HarnessClient(ServerAddress.parse(server.url("/").toString()), CookieJar.NO_COOKIES).use { api ->
                val error = assertFailsWith<HarnessException> { api.uploadFile("s1", UploadSource("a", 1) { byteArrayOf(1).inputStream() }) }
                assertEquals("session/attachment-invalid", error.code)
            }
        }
    }
    @Test fun `session export streams ZIP to destination and preserves session identity`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(190_007) { (it % 239).toByte() }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(bytes)))
            val output = java.io.ByteArrayOutputStream()
            HarnessClient(ServerAddress.parse(server.url("/").toString()), CookieJar.NO_COOKIES).use { api ->
                assertEquals(bytes.size.toLong(), api.exportSession("session & 1", { output }))
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("/api/session.export", request.requestUrl!!.encodedPath)
                assertEquals("session & 1", request.requestUrl!!.queryParameter("sessionId"))
                assertEquals("true", request.requestUrl!!.queryParameter("includeDescendants"))
                assertContentEquals(bytes, output.toByteArray())
            }
        }
    }
    @Test fun `export rejection or login HTML never opens the destination`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
            HarnessClient(ServerAddress.parse(server.url("/").toString()), CookieJar.NO_COOKIES).use { api ->
                var opened = false
                repeat(2) { assertFailsWith<HarnessException> { api.exportSession("s1", { opened = true; java.io.ByteArrayOutputStream() }) } }
                assertFalse(opened)
            }
        }
    }
    private fun echoRpc() = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val id = parseObject(request.body.clone().readUtf8()).text("rpcId")
            return MockResponse().setBody("""{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":{"accepted":true}}}""")
        }
    }
}

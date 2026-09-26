package dev.harness.core

import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class HarnessClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HarnessClient
    private val saved = mutableListOf<Cookie>()
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = HarnessClient(ServerAddress.parse(server.url("/").toString()), object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { saved.clear(); saved.addAll(cookies) }
            override fun loadForRequest(url: HttpUrl) = saved.filter { it.matches(url) }
        })
    }
    @After fun teardown() { api.close(); server.shutdown() }

    @Test fun `exchanges root token for cookie without following redirects`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/").addHeader("Set-Cookie", "dsh_session=valid; Path=/; HttpOnly"))
        api.login("test-token")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/?token=test-token", request.path)
        assertEquals("valid", saved.single().value)
        assertEquals(1, server.requestCount)
        assertNull(request.getHeader("Authorization"))
    }
    @Test fun `uses connection envelope and matches correlated response`() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = parseObject(request.body.readUtf8())
                assertEquals("/api/session/list", request.path)
                assertEquals("client-request", body.text("type"))
                assertEquals("session/list", body.text("method"))
                assertEquals(emptyObject, body["payload"].obj()["args"].obj()["_request"])
                return response(body.text("rpcId"), jsonObject("items" to JsonArray(emptyList())))
            }
        }
        val result = api.listSessions()
        assertTrue(result["items"].array().isEmpty())
    }
    @Test fun `refuses an unrelated RPC response`() = runBlocking {
        server.enqueue(response("not-this-request", emptyObject))
        val e = assertFailsWith<HarnessException> { api.rpc("session/modelCatalog") }
        assertEquals("protocol/envelope", e.code)
    }
    @Test fun `does not automatically repeat failed prompt POST`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertFails { api.command("session/prompt", jsonObject("requestId" to str("one"))) }
        assertEquals(1, server.requestCount)
    }
    @Test fun `expired cookie gives actionable login error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertFailsWith<HarnessException> { api.command("session/list", emptyObject) }
        assertEquals("auth/required", e.code)
        assertTrue(e.message.orEmpty().contains("token"))
    }
    @Test fun `mux opens event stream and carries actual session follow payload`() = runBlocking {
        val opened = CompletableDeferred<JsonObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = parseObject(text)
                if (frame.text("streamId") == "events") {
                    assertEquals("\$events", frame.text("endpoint"))
                    webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready","clientId":"client-1","host":{"home":"/home/test"}}}""")
                } else opened.complete(frame)
            }
        }))
        api.mux().use { mux ->
            val ready = withTimeout(3000) { mux.frames.receive() }
            assertEquals("ready", ready["value"].obj().text("type"))
            mux.open("chat", "session/follow", jsonObject("request" to jsonObject("address" to jsonObject("kind" to str("session"), "sessionId" to str("s1")), "assistantStream" to JsonPrimitive(true))))
            val follow = withTimeout(3000) { opened.await() }
            assertEquals("open", follow.text("type"))
            assertTrue(follow["payload"].obj()["args"].obj()["request"].obj().flag("assistantStream"))
        }
    }
    @Test fun `HTTP cancellation cancels the underlying request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = launch { api.rpc("session/modelCatalog") }
        delay(150)
        withTimeout(2000) { task.cancelAndJoin() }
        assertTrue(task.isCancelled)
    }
    private fun response(id: String, value: JsonElement) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        jsonObject("type" to str("server-response"), "rpcId" to str(id), "result" to jsonObject("ok" to JsonPrimitive(true), "value" to value)).toString())
}

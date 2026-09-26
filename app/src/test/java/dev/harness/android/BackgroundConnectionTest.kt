package dev.harness.android

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.harness.core.*
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundConnectionTest {
    @Test fun returningFromBackgroundChecksAndReusesTheConnectedSocket() = Backend().use { backend ->
        val model = backend.model
        model.pause()
        backend.looper.idleFor(Duration.ofSeconds(30))
        assertTrue("Backgrounding keeps the same connection", model.state.value.connected)
        model.resume()
        backend.looper.idleFor(Duration.ofSeconds(6))
        assertEquals("A responsive socket is reused", 1, backend.upgrades.get())
        model.setBackgroundConnection(false)
        model.pause()
        backend.looper.idleFor(Duration.ofSeconds(9))
        assertEquals(ConnectionStatus.OFFLINE, model.state.value.connection)
    }

    @Test fun reconnectingAfterServerSocketFailureKeepsMessages() = Backend().use { backend ->
        val model = backend.model
        backend.rejectReconnect = true
        model.reconnect()
        backend.until { model.state.value.connection == ConnectionStatus.RETRYING }
        assertEquals("retained conversation", model.state.value.messages.single().text)
        backend.rejectReconnect = false
        model.reconnect()
        backend.until { backend.upgrades.get() >= 3 && model.state.value.connected && !model.state.value.syncing }
        assertEquals("retained conversation", model.state.value.messages.single().text)
        assertNull(model.state.value.error)
    }

    @Test fun sustainedRecoveryFailureStillShowsTheError() = Backend().use { backend ->
        backend.rejectReconnect = true
        backend.model.reconnect()
        backend.until(advanceClock = true) { backend.model.state.value.error != null }
        assertTrue(backend.model.state.value.error!!.contains("正在自动重连"))
        assertEquals("retained conversation", backend.model.state.value.messages.single().text)
    }

    private class Backend : AutoCloseable {
        val server = MockWebServer()
        val upgrades = AtomicInteger()
        val requestPaths = ConcurrentLinkedQueue<String>()
        @Volatile var rejectReconnect = false
        val looper = shadowOf(Looper.getMainLooper())
        val model: HarnessViewModel
        init {
            val app = ApplicationProvider.getApplicationContext<Application>()
            SessionStore(app).apply { this.server = ""; notifications = false; keepBackgroundConnection = true }
            NotificationMonitor.appVisible = false
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestPaths.add(request.path.orEmpty())
                    if (request.path == "/api/remote.mux") {
                        upgrades.incrementAndGet()
                        if (rejectReconnect) return MockResponse().setResponseCode(503)
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val frame = parseObject(text)
                                val stream = frame.text("streamId")
                                when (frame.text("endpoint")) {
                                    "\$events" -> webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready","clientId":"test-client"}}""")
                                    "workspace/follow" -> Unit
                                    "session/follow" -> webSocket.send("""{"type":"item","streamId":"$stream","value":{"type":"snapshot","cursor":0,"hasMore":false,"records":[{"event":{"seq":0,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"retained conversation"}]}}}],"projections":{"values":{}},"assistantStream":{"revision":0}}}""")
                                }
                            }
                        })
                    }
                    val id = parseObject(request.body.readUtf8()).text("rpcId")
                    val value = if (request.path == "/api/session/list") """{"items":[{"sessionId":"a"}]}""" else if (request.path == "/api/commands/list") "[]" else "{}"
                    return MockResponse().setBody("""{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":$value}}""")
                }
            }
            server.start()
            model = HarnessViewModel(app)
            model.connect(server.url("/").toString())
            until { model.state.value.connected }
            model.selectSession("a")
            until { !model.state.value.syncing }
        }
        fun until(advanceClock: Boolean = false, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) {
                if (advanceClock) looper.idleFor(Duration.ofMillis(100)) else looper.idle()
                Thread.sleep(10)
            }
            assertTrue("Condition did not become true; upgrades=${upgrades.get()} paths=$requestPaths state=${model.state.value}", condition())
        }
        override fun close() { model.logout(); looper.idle(); server.close() }
    }
}

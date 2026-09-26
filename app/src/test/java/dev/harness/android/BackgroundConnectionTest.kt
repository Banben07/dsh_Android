package dev.harness.android

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.harness.core.*
import java.time.Duration
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ServerSocketFactory
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
        backend.until { backend.probes.get() == 1 && backend.cancelledProbes.get() == 1 }
        backend.looper.idleFor(Duration.ofSeconds(6))
        assertEquals("A responsive socket is reused", 1, backend.upgrades.get())
        model.setBackgroundConnection(false)
        model.pause()
        backend.looper.idleFor(Duration.ofSeconds(9))
        assertEquals(ConnectionStatus.OFFLINE, model.state.value.connection)
    }

    @Test fun resumingAnUnresponsiveSocketReconnectsWithoutClearingMessages() = Backend(replyToProbe = false).use { backend ->
        val model = backend.model
        model.pause()
        model.resume()
        backend.until { backend.probes.get() == 1 }
        assertEquals("retained conversation", model.state.value.messages.single().text)
        backend.looper.idleFor(Duration.ofSeconds(6))
        backend.until { backend.upgrades.get() == 2 && model.state.value.connected && !model.state.value.syncing }
        assertEquals("retained conversation", model.state.value.messages.single().text)
        assertNull(model.state.value.error)
    }

    @Test fun returningDuringSocketFailureRetriesImmediatelyWithoutATransientErrorBanner() = Backend().use { backend ->
        val model = backend.model
        model.pause()
        backend.breakConnections()
        backend.until { model.state.value.connection == ConnectionStatus.RETRYING }
        assertNull("One dropped background socket is a recovery status", model.state.value.error)
        assertEquals("retained conversation", model.state.value.messages.single().text)
        model.resume()
        // Do not advance the retry-delay clock. Returning should trigger an immediate attempt.
        backend.until { backend.upgrades.get() == 2 && model.state.value.connected && !model.state.value.syncing }
        assertEquals("retained conversation", model.state.value.messages.single().text)
    }

    @Test fun sustainedRecoveryFailureStillShowsTheError() = Backend().use { backend ->
        backend.rejectReconnect = true
        backend.model.pause()
        backend.breakConnections()
        backend.until { backend.model.state.value.connection == ConnectionStatus.RETRYING }
        backend.until(advanceClock = true) { backend.model.state.value.error != null }
        assertTrue(backend.model.state.value.error!!.contains("正在自动重连"))
        assertEquals("retained conversation", backend.model.state.value.messages.single().text)
    }

    private class Backend(private val replyToProbe: Boolean = true) : AutoCloseable {
        val server = MockWebServer()
        val upgrades = AtomicInteger()
        val probes = AtomicInteger()
        val cancelledProbes = AtomicInteger()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        @Volatile var rejectReconnect = false
        val looper = shadowOf(Looper.getMainLooper())
        val model: HarnessViewModel
        init {
            val app = ApplicationProvider.getApplicationContext<Application>()
            SessionStore(app).apply { this.server = ""; notifications = false; keepBackgroundConnection = true }
            NotificationMonitor.appVisible = false
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/remote.mux") {
                        upgrades.incrementAndGet()
                        if (rejectReconnect) return MockResponse().setResponseCode(503)
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val frame = parseObject(text)
                                val stream = frame.text("streamId")
                                if (frame.text("type") == "cancel" && stream.startsWith("resume-")) cancelledProbes.incrementAndGet()
                                when (frame.text("endpoint")) {
                                    "\$events" -> webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready","clientId":"test-client"}}""")
                                    "workspace/follow" -> if (stream.startsWith("resume-")) {
                                        probes.incrementAndGet()
                                        if (replyToProbe) webSocket.send("""{"type":"item","streamId":"$stream","value":{"type":"baseline","value":{"items":[]}}}""")
                                    }
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
            server.serverSocketFactory = object : ServerSocketFactory() {
                override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                    override fun accept(): Socket = super.accept().also { sockets.add(it) }
                }
                override fun createServerSocket(port: Int): ServerSocket = error("Unexpected factory overload")
                override fun createServerSocket(port: Int, backlog: Int): ServerSocket = error("Unexpected factory overload")
                override fun createServerSocket(port: Int, backlog: Int, address: InetAddress): ServerSocket = error("Unexpected factory overload")
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
            assertTrue("Condition did not become true; state=${model.state.value}", condition())
        }
        fun breakConnections() {
            sockets.forEach { if (!it.isClosed) { it.setSoLinger(true, 0); it.close() } }
        }
        override fun close() { model.logout(); looper.idle(); server.close() }
    }
}

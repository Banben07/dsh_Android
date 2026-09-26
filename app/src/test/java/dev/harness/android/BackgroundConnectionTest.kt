package dev.harness.android

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.harness.core.*
import java.time.Duration
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
    @Test fun returningFromBackgroundReusesTheConnectedSocket() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = SessionStore(app)
        store.server = ""
        store.notifications = false
        store.keepBackgroundConnection = true
        NotificationMonitor.appVisible = false // The test exercises the UI connection without starting an Android service.
        val looper = shadowOf(Looper.getMainLooper())
        val upgrades = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/remote.mux") {
                        upgrades.incrementAndGet()
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (parseObject(text).text("endpoint") == "\$events") webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready","clientId":"test-client"}}""")
                            }
                        })
                    }
                    val id = parseObject(request.body.readUtf8()).text("rpcId")
                    return MockResponse().setBody("""{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":{"items":[]}}}""")
                }
            }
            server.start()
            val model = HarnessViewModel(app)
            try {
                model.connect(server.url("/").toString())
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!model.state.value.connected && System.nanoTime() < deadline) { looper.idle(); Thread.sleep(10) }
                assertTrue("Initial connection completed", model.state.value.connected)
                model.pause()
                looper.idleFor(Duration.ofSeconds(30))
                assertTrue("Backgrounding keeps the same connection", model.state.value.connected)
                model.resume()
                looper.idle()
                assertEquals("Returning does not open another WebSocket", 1, upgrades.get())
                model.setBackgroundConnection(false)
                model.pause()
                looper.idleFor(Duration.ofSeconds(9))
                assertEquals(ConnectionStatus.OFFLINE, model.state.value.connection)
            } finally {
                model.logout()
                looper.idle()
            }
        }
    }
}

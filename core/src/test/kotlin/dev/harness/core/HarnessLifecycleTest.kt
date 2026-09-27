package dev.harness.core

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Test

/** Observe real socket teardown at the same boundary that triggered the Android TLS crash. */
class HarnessLifecycleTest {
    private class ClosingSockets : SocketFactory() {
        val threads = LinkedBlockingQueue<Thread>()
        override fun createSocket(): Socket = object : Socket() {
            override fun close() { threads.offer(Thread.currentThread()); super.close() }
        }
        override fun createSocket(host: String, port: Int): Socket = error("Unexpected connected socket factory call")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected connected socket factory call")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected connected socket factory call")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected connected socket factory call")
        fun assertClosedOff(caller: Thread) {
            val thread = threads.poll(3, TimeUnit.SECONDS)
            assertNotNull(thread, "Socket was actually closed")
            assertNotSame(caller, thread, "Socket teardown must not run on the caller/UI thread")
        }
    }

    @Test fun `switching servers releases the old pool off the caller thread`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(3) {
                val sockets = ClosingSockets()
                val transport = OkHttpClient.Builder().socketFactory(sockets).build()
                val api = HarnessClient(ServerAddress.parse(server.url("/").toString()), transport)
                server.enqueue(MockResponse().setBody("connected"))
                assertEquals(200, api.probe())
                assertEquals(1, transport.connectionPool.idleConnectionCount())
                val caller = Thread.currentThread()
                api.close()
                sockets.assertClosedOff(caller)
                assertEquals(0, transport.connectionPool.connectionCount())
            }
        }
    }

    @Test fun `recovery drops idle sockets off the caller thread without cancelling active streams`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val sockets = ClosingSockets()
            val transport = OkHttpClient.Builder().socketFactory(sockets).build()
            HarnessClient(ServerAddress.parse(server.url("/").toString()), transport).use { api ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready"}}""")
                    }
                }))
                api.mux().use { mux ->
                    withTimeout(3000) { mux.frames.receive() }
                    server.enqueue(MockResponse().setBody("connected"))
                    assertEquals(200, api.probe())
                    val caller = Thread.currentThread()
                    api.discardIdleConnections()
                    sockets.assertClosedOff(caller)
                    assertEquals(0, transport.connectionPool.idleConnectionCount())
                    mux.open("still-active", "workspace/follow")
                    withTimeout(3000) { mux.frames.receive() }
                }
            }
        }
    }

    @Test fun `closing a mux releases its socket off the caller thread`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val sockets = ClosingSockets()
            val transport = OkHttpClient.Builder().socketFactory(sockets).build()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready"}}""")
                }
            }))
            HarnessClient(ServerAddress.parse(server.url("/").toString()), transport).use { api ->
                val failures = LinkedBlockingQueue<String>()
                val mux = api.mux { failures.offer(it) }
                withTimeout(3000) { mux.frames.receive() }
                val caller = Thread.currentThread()
                mux.close()
                sockets.assertClosedOff(caller)
                assertNull(failures.poll(200, TimeUnit.MILLISECONDS), "Intentional teardown must not be logged as a transport failure")
            }
        }
    }

    @Test fun `protocol failure is recorded before consumption and not overwritten by cancellation`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("invalid-json") }
            }))
            HarnessClient(ServerAddress.parse(server.url("/").toString()), OkHttpClient()).use { api ->
                val failures = LinkedBlockingQueue<String>()
                api.mux { failures.offer(it) }.use { mux ->
                    assertEquals("protocol/frame: 无法读取实时数据", failures.poll(3, TimeUnit.SECONDS))
                    val failure = withTimeout(3000) { mux.frames.receiveCatching() }.exceptionOrNull()
                    assertEquals("protocol/frame", (failure as HarnessException).code)
                    assertNull(failures.poll(200, TimeUnit.MILLISECONDS), "Socket cancellation must not replace the original failure")
                    assertEquals("protocol/frame: 无法读取实时数据", mux.closeReason)
                }
            }
        }
    }

    @Test fun `cancelling an HTTP coroutine closes the socket off the caller thread`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val sockets = ClosingSockets()
            val transport = OkHttpClient.Builder().socketFactory(sockets).build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            HarnessClient(ServerAddress.parse(server.url("/").toString()), transport).use { api ->
                val pending = launch { api.probe() }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                val caller = Thread.currentThread()
                pending.cancelAndJoin()
                sockets.assertClosedOff(caller)
            }
        }
    }
}

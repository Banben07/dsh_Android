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
                val mux = api.mux()
                withTimeout(3000) { mux.frames.receive() }
                val caller = Thread.currentThread()
                mux.close()
                sockets.assertClosedOff(caller)
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

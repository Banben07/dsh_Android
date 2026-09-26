package dev.harness.android

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.harness.core.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
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
class SessionNavigationTest {
    @Test fun createOpensImmediatelyAndBecomesUsableWithoutListRefreshOrSnapshot() = Backend().use { backend ->
        val model = backend.model
        model.selectSession("a")
        backend.snapshot(backend.follow("a"), "previous conversation")
        backend.until { !model.state.value.syncing }
        model.quickCreateSession()
        val id = model.state.value.selectedId!!
        assertNotEquals("a", id)
        assertTrue(model.state.value.creatingSelected)
        assertTrue(model.state.value.messages.isEmpty())
        model.quickCreateSession() // Repeated taps must not create duplicates.
        backend.until { backend.creates.get() == 1 }
        assertTrue(backend.follows.none { it.sessionId == id })
        model.send("must not send before creation", false) {}
        assertFalse(model.state.value.sending)
        backend.releaseCreate.countDown()
        backend.until { !model.state.value.creating }
        backend.follow(id) // Deliberately withhold this first snapshot.
        assertEquals(id, model.state.value.selectedId)
        assertFalse("The first snapshot no longer blocks composing", model.state.value.loading)
        assertTrue(model.state.value.syncing)
        assertTrue(model.state.value.sessions.any { it.id == id })
        assertEquals("No extra list roundtrip on the creation path", 1, backend.lists.get())
        model.send("first message", false) {}
        backend.until { backend.prompts.get() == 1 && !model.state.value.sending }
        assertEquals(1, backend.creates.get())
    }

    @Test fun switchingBackShowsCachedMessagesBeforeTheNetworkRepliesAndIgnoresOldStream() = Backend().use { backend ->
        val model = backend.model
        model.selectSession("a")
        backend.snapshot(backend.follow("a"), "A cached")
        backend.until { !model.state.value.syncing }
        model.setDraft("unfinished A draft")
        model.selectSession("b")
        val b = backend.follow("b")
        backend.snapshot(b, "B cached")
        backend.until { !model.state.value.syncing }
        model.selectSession("a")
        assertEquals("A cached", model.state.value.messages.single().text)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.syncing)
        assertEquals("unfinished A draft", model.draft())
        backend.snapshot(b, "late B must be ignored")
        backend.snapshot(backend.follow("a"), "A updated")
        backend.until { !model.state.value.syncing }
        assertEquals("a", model.state.value.selectedId)
        assertEquals("A updated", model.state.value.messages.single().text)
    }

    @Test fun failedCreationRestoresPreviousConversationAndDraft() = Backend(failCreate = true).use { backend ->
        val model = backend.model
        model.selectSession("a")
        backend.snapshot(backend.follow("a"), "A cached")
        backend.until { !model.state.value.syncing }
        model.setDraft("keep this draft")
        model.quickCreateSession()
        backend.releaseCreate.countDown()
        backend.until { !model.state.value.creating }
        assertEquals("a", model.state.value.selectedId)
        assertEquals("A cached", model.state.value.messages.single().text)
        assertEquals("keep this draft", model.draft())
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.creatingSessionId)
    }

    @Test fun creationFinishingAfterAnotherSelectionDoesNotStealTheScreen() = Backend().use { backend ->
        val model = backend.model
        model.quickCreateSession()
        val newId = model.state.value.selectedId!!
        model.selectSession("b")
        backend.snapshot(backend.follow("b"), "B selected")
        backend.until { !model.state.value.syncing }
        backend.releaseCreate.countDown()
        backend.until { !model.state.value.creating }
        assertEquals("b", model.state.value.selectedId)
        assertEquals("B selected", model.state.value.messages.single().text)
        assertTrue(model.state.value.sessions.any { it.id == newId })
        assertTrue(backend.follows.none { it.sessionId == newId })
    }

    @Test fun logoutClearsCachedHistoryEvenWhenTheNextLoginHasTheSameSessionIds() = Backend().use { backend ->
        val model = backend.model
        model.selectSession("a")
        backend.snapshot(backend.follow("a"), "private old history")
        backend.until { !model.state.value.syncing }
        model.selectSession("b") // Saves A in the view cache.
        model.logout()
        model.connect(backend.server.url("/").toString())
        backend.until { model.state.value.connected }
        model.selectSession("a")
        assertTrue(model.state.value.messages.isEmpty())
        assertTrue(model.state.value.loading)
    }

    private data class Follow(val streamId: String, val sessionId: String, val socket: WebSocket)

    private class Backend(val failCreate: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val releaseCreate = CountDownLatch(1)
        val creates = AtomicInteger()
        val lists = AtomicInteger()
        val prompts = AtomicInteger()
        val follows = ConcurrentLinkedQueue<Follow>()
        val model: HarnessViewModel
        private val looper = shadowOf(Looper.getMainLooper())

        init {
            val app = ApplicationProvider.getApplicationContext<Application>()
            SessionStore(app).apply { this.server = ""; notifications = false; keepBackgroundConnection = true }
            NotificationMonitor.appVisible = false
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/remote.mux") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = parseObject(text)
                            when (frame.text("endpoint")) {
                                "\$events" -> webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready","clientId":"test-client"}}""")
                                "session/follow" -> follows.add(Follow(frame.text("streamId"), frame["payload"].obj()["args"].obj()["request"].obj()["address"].obj().text("sessionId"), webSocket))
                            }
                        }
                    })
                    val envelope = parseObject(request.body.readUtf8())
                    val id = envelope.text("rpcId")
                    val value = when (request.path) {
                        "/api/session/list" -> {
                            lists.incrementAndGet()
                            """{"items":[{"sessionId":"a","cwd":"/a"},{"sessionId":"b","cwd":"/b"}]}"""
                        }
                        "/api/session/create" -> {
                            creates.incrementAndGet()
                            check(releaseCreate.await(10, TimeUnit.SECONDS)) { "Test never released session/create" }
                            if (failCreate) return MockResponse().setResponseCode(500)
                            val sessionId = envelope["payload"].obj()["args"].obj()["request"].obj().text("sessionId")
                            """{"sessionId":"$sessionId"}"""
                        }
                        "/api/session/prompt" -> { prompts.incrementAndGet(); "{}" }
                        "/api/commands/list" -> "[]"
                        else -> "{}"
                    }
                    return MockResponse().setBody("""{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":$value}}""")
                }
            }
            server.start()
            model = HarnessViewModel(app)
            model.connect(server.url("/").toString())
            until { model.state.value.connected }
        }

        fun until(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) { looper.idle(); Thread.sleep(10) }
            assertTrue("Condition did not become true; state=${model.state.value}", condition())
        }
        fun follow(sessionId: String): Follow {
            until { follows.any { it.sessionId == sessionId } }
            return follows.first { it.sessionId == sessionId }.also { follows.remove(it) }
        }
        fun snapshot(follow: Follow, text: String) {
            follow.socket.send("""{"type":"item","streamId":"${follow.streamId}","value":{"type":"snapshot","cursor":0,"hasMore":false,"records":[{"event":{"seq":0,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"$text"}]}}}],"projections":{"values":{}},"assistantStream":{"revision":0}}}""")
        }
        override fun close() {
            releaseCreate.countDown()
            model.logout()
            looper.idle()
            server.close()
        }
    }
}

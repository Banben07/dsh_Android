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
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun switchingBackReusesLiveHistoryAndKeepsBackgroundUpdatesOffTheCurrentScreen() = Backend().use { backend ->
        val model = backend.model
        model.selectSession("a")
        val a = backend.follow("a")
        backend.snapshot(a, "A cached")
        backend.until { !model.state.value.syncing && !model.state.value.commandsLoading }
        model.setDraft("unfinished A draft")
        model.selectSession("b")
        val b = backend.follow("b")
        backend.snapshot(b, "B cached")
        backend.until { !model.state.value.syncing && !model.state.value.commandsLoading }
        backend.event(a, 1, "A background update")
        model.selectSession("a")
        assertEquals("A cached", model.state.value.messages.first().text)
        assertFalse(model.state.value.loading)
        assertFalse("An existing live subscription needs no fresh snapshot", model.state.value.syncing)
        assertEquals("unfinished A draft", model.draft())
        backend.until { model.state.value.messages.size == 2 }
        assertEquals("A background update", model.state.value.messages.last().text)
        backend.snapshot(b, "B updated in background")
        model.selectSession("b")
        backend.until { model.state.value.messages.singleOrNull()?.text == "B updated in background" }
        model.selectSession("a")
        assertEquals("A background update", model.state.value.messages.last().text)
        assertEquals(2, backend.totalFollows.get())
        assertEquals(1, backend.upgrades.get())
        assertEquals("Slash commands are reused too", 2, backend.commandLists.get())
    }

    @Test fun rapidSwitchBeforeSnapshotReusesThePendingSubscription() = Backend().use { backend ->
        val model = backend.model
        model.selectSession("a")
        val a = backend.follow("a")
        model.selectSession("b")
        val b = backend.follow("b")
        model.selectSession("a")
        backend.snapshot(b, "B must not replace A")
        backend.snapshot(a, "A first snapshot")
        backend.until { !model.state.value.syncing }
        assertEquals("A first snapshot", model.state.value.messages.single().text)
        assertEquals(2, backend.totalFollows.get())
        assertEquals(1, backend.upgrades.get())
    }

    @Test fun evictedSubscriptionUsesCachedViewAndIgnoresItsLateFrames() = Backend().use { backend ->
        val model = backend.model
        var oldA: Follow? = null
        for (id in listOf("a", "b", "c", "d")) {
            model.selectSession(id)
            val follow = backend.follow(id)
            if (id == "a") oldA = follow
            backend.snapshot(follow, "$id cached")
            backend.until { !model.state.value.syncing }
        }
        backend.until { backend.cancels.contains(oldA!!.streamId) }
        model.selectSession("a")
        assertEquals("a cached", model.state.value.messages.single().text)
        assertTrue(model.state.value.syncing)
        val newA = backend.follow("a")
        backend.event(oldA!!, 1, "obsolete subscription")
        backend.snapshot(newA, "A refreshed")
        backend.until { !model.state.value.syncing }
        assertEquals("A refreshed", model.state.value.messages.single().text)
        assertEquals(5, backend.totalFollows.get())
        assertEquals(1, backend.upgrades.get())
    }

    @Test fun gapResynchronizesOnlyTheAffectedSession() = Backend().use { backend ->
        val model = backend.model
        model.selectSession("b")
        backend.snapshot(backend.follow("b"), "B retained")
        backend.until { !model.state.value.syncing }
        model.selectSession("a")
        val a = backend.follow("a")
        backend.snapshot(a, "A retained")
        backend.until { !model.state.value.syncing }
        backend.event(a, 3, "missing events before this")
        val replacement = backend.follow("a")
        assertEquals("A retained", model.state.value.messages.single().text)
        backend.snapshot(replacement, "A resynchronized")
        backend.until { !model.state.value.syncing }
        model.selectSession("b")
        assertEquals("B retained", model.state.value.messages.single().text)
        assertFalse(model.state.value.syncing)
        assertEquals(3, backend.totalFollows.get())
        assertEquals(1, backend.upgrades.get())
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

    @Test fun newSessionStaysBlankUntilItHasMessages() = Backend().use { backend ->
        val model = backend.model
        backend.releaseCreate.countDown()
        model.quickCreateSession()
        backend.until { !model.state.value.creating }
        val id = model.state.value.selectedId!!
        assertTrue("An unused session is hidden from the list after leaving it", model.state.value.sessions.single { it.id == id }.blank)
        backend.snapshot(backend.follow(id), "first message")
        backend.until { !model.state.value.sessions.single { it.id == id }.blank }
    }

    @Test fun openingOneSessionDoesNotPrefetchOtherHistories() = Backend().use { backend ->
        backend.model.selectSession("b")
        backend.snapshot(backend.follow("b"), "B selected")
        backend.until { !backend.model.state.value.syncing }
        assertEquals(1, backend.totalFollows.get())
        assertTrue(backend.prefetches.isEmpty())
    }

    @Test fun reconnectKeepsTheViewButReplacesSubscriptionsOnTheNewSocket() = Backend().use { backend ->
        val model = backend.model
        model.selectSession("a")
        val a = backend.follow("a")
        backend.snapshot(a, "A before disconnect")
        backend.until { !model.state.value.syncing }
        model.reconnect()
        assertEquals("A before disconnect", model.state.value.messages.single().text)
        val refreshed = backend.follow("a")
        backend.snapshot(refreshed, "A after reconnect")
        backend.until { !model.state.value.syncing }
        assertEquals("A after reconnect", model.state.value.messages.single().text)
        assertEquals(2, backend.upgrades.get())
        assertNotEquals(a.streamId, refreshed.streamId)
    }

    @Test fun paginationAndLiveEventsStayOrderedWhileSwitchingSessions() = Backend(holdPage = true).use { backend ->
        val model = backend.model
        model.selectSession("a")
        val a = backend.follow("a")
        backend.snapshot(a, "A recent", seq = 10, hasMore = true)
        backend.until { !model.state.value.syncing }
        model.loadOlder()
        backend.until { backend.pages.get() == 1 }
        model.selectSession("b")
        backend.snapshot(backend.follow("b"), "B visible")
        backend.until { !model.state.value.syncing }
        backend.event(a, 11, "A live update")
        backend.releasePage.countDown()
        model.selectSession("a")
        backend.until { !model.state.value.loadingOlder && model.state.value.messages.size == 3 }
        assertEquals(listOf("A older", "A recent", "A live update"), model.state.value.messages.map { it.text })
        assertFalse(model.state.value.hasMore)
        assertEquals(2, backend.totalFollows.get())
    }

    private data class Follow(val streamId: String, val sessionId: String, val socket: WebSocket)

    private class Backend(val failCreate: Boolean = false, val holdPage: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val releaseCreate = CountDownLatch(1)
        val releasePage = CountDownLatch(1)
        val pages = AtomicInteger()
        val creates = AtomicInteger()
        val lists = AtomicInteger()
        val prompts = AtomicInteger()
        val upgrades = AtomicInteger()
        val totalFollows = AtomicInteger()
        val commandLists = AtomicInteger()
        val cancels = ConcurrentLinkedQueue<String>()
        val follows = ConcurrentLinkedQueue<Follow>()
        val prefetches = ConcurrentLinkedQueue<Follow>()
        val model: HarnessViewModel
        private val looper = shadowOf(Looper.getMainLooper())

        init {
            val app = ApplicationProvider.getApplicationContext<Application>()
            SessionStore(app).apply { this.server = ""; notifications = false; keepBackgroundConnection = true }
            NotificationMonitor.appVisible = false
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/remote.mux") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { upgrades.incrementAndGet() }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = parseObject(text)
                            if (frame.text("type") == "cancel") cancels.add(frame.text("streamId"))
                            when (frame.text("endpoint")) {
                                "\$events" -> webSocket.send("""{"type":"item","streamId":"events","value":{"type":"ready","clientId":"test-client"}}""")
                                "session/follow" -> if (frame["payload"].obj()["args"].obj()["request"].obj()["assistantStream"] == JsonPrimitive(false)) {
                                    // Harness validates `assistantStream` as z.literal(true).optional().
                                    webSocket.send("""{"type":"error","streamId":"${frame.text("streamId")}","error":{"code":"gateway/arguments-invalid","message":"assistantStream"}}""")
                                } else Follow(frame.text("streamId"), frame["payload"].obj()["args"].obj()["request"].obj()["address"].obj().text("sessionId"), webSocket)
                                    .let { totalFollows.incrementAndGet(); if (it.streamId.startsWith("prefetch-")) prefetches.add(it) else follows.add(it) }
                            }
                        }
                    })
                    val envelope = parseObject(request.body.readUtf8())
                    val id = envelope.text("rpcId")
                    val value = when (request.path) {
                        "/api/session/list" -> {
                            lists.incrementAndGet()
                            """{"items":[{"sessionId":"a","cwd":"/a"},{"sessionId":"b","cwd":"/b"},{"sessionId":"c","cwd":"/c"},{"sessionId":"d","cwd":"/d"}]}"""
                        }
                        "/api/session/create" -> {
                            creates.incrementAndGet()
                            check(releaseCreate.await(10, TimeUnit.SECONDS)) { "Test never released session/create" }
                            if (failCreate) return MockResponse().setResponseCode(500)
                            val sessionId = envelope["payload"].obj()["args"].obj()["request"].obj().text("sessionId")
                            """{"sessionId":"$sessionId"}"""
                        }
                        "/api/session/prompt" -> { prompts.incrementAndGet(); "{}" }
                        "/api/session/page" -> {
                            pages.incrementAndGet()
                            if (holdPage) check(releasePage.await(10, TimeUnit.SECONDS))
                            """{"records":[{"event":{"seq":0,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"A older"}]}}}],"hasMore":false}"""
                        }
                        "/api/commands/list" -> { commandLists.incrementAndGet(); "[]" }
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
        fun snapshot(follow: Follow, text: String, seq: Int = 0, hasMore: Boolean = false) {
            follow.socket.send("""{"type":"item","streamId":"${follow.streamId}","value":{"type":"snapshot","cursor":$seq,"hasMore":$hasMore,"records":[{"event":{"seq":$seq,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"$text"}]}}}],"projections":{"values":{}},"assistantStream":{"revision":0}}}""")
        }
        fun event(follow: Follow, seq: Int, text: String) {
            follow.socket.send("""{"type":"item","streamId":"${follow.streamId}","value":{"type":"event","event":{"seq":$seq,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"$text"}]}}}}""")
        }
        override fun close() {
            releaseCreate.countDown()
            releasePage.countDown()
            model.logout()
            looper.idle()
            server.close()
        }
    }
}

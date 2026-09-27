package dev.harness.android

import dev.harness.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** One ordered reducer per subscription. Journal work never blocks the mux or the UI thread. */
internal class LiveSession(
    val id: String,
    val streamId: String,
    scope: CoroutineScope,
    private val onUpdate: (LiveSession, Update) -> Unit,
    private val onFailure: (LiveSession, Exception) -> Unit,
) {
    data class Update(
        val view: SessionViewCache.View, val cursor: Long, val firstSeq: Long?,
        val retainedCharacters: Long, val baseline: Boolean, val userMessage: Boolean,
    )
    private data class Work(val value: JsonObject, val page: Boolean = false, val done: CompletableDeferred<Unit>? = null)
    private val inbox = Channel<Work>(128)
    private val journal = SessionJournal()
    private val mutex = Mutex()
    private var receivedCharacters = 0L
    var latest: Update? = null; private set
    var view: SessionViewCache.View? = null; private set
    var loadingOlder = false
    private val job = scope.launch {
        try {
            for (work in inbox) {
                try {
                    val next = withContext(Dispatchers.Default) {
                        mutex.withLock {
                            val baseline = !work.page && work.value.text("type") == "snapshot"
                            if (work.page) journal.prepend(work.value) else journal.accept(work.value)
                            val size = work.value.toString().length.toLong()
                            receivedCharacters = if (baseline) size else receivedCharacters + size
                            val rendered = SessionViewCache.View(journal.messages(), journal.projections["modelSelection"].obj()["next"].obj(), journal.hasMore)
                            Update(rendered, journal.cursor, journal.firstSeq, receivedCharacters + rendered.characters, baseline,
                                !work.page && work.value.text("type") == "event" && work.value["event"].obj().text("type") == "user/message")
                        }
                    }
                    latest = next
                    // Control-stream model updates remain authoritative between baselines.
                    view = next.view.copy(model = if (next.baseline) next.view.model else view?.model ?: next.view.model)
                    onUpdate(this@LiveSession, next)
                    work.done?.complete(Unit)
                } catch (e: Exception) {
                    work.done?.completeExceptionally(e)
                    throw e
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            onFailure(this@LiveSession, e)
        } finally { inbox.cancel() }
    }
    fun offer(value: JsonObject): Boolean = inbox.trySend(Work(value)).isSuccess
    suspend fun prepend(page: JsonObject) {
        val done = CompletableDeferred<Unit>(job)
        inbox.send(Work(page, page = true, done = done))
        done.await()
    }
    suspend fun hasPrompt(requestId: String): Boolean = withContext(Dispatchers.Default) { mutex.withLock { journal.hasPrompt(requestId) } }
    fun setModel(model: JsonObject) { view = view?.copy(model = model) }
    fun close() { job.cancel(); inbox.cancel() }
}

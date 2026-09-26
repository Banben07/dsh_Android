package dev.harness.core

import kotlinx.serialization.json.JsonObject

/** Only inspect sessions observed running; an idle baseline must never notify for old conversations. */
class CompletionTracker {
    private var running = mutableSetOf<String>()
    fun baseline(sessions: List<SessionSummary>): Set<String> {
        val ended = sessions.filter { !it.running && it.id in running }.map { it.id }.toSet()
        running = sessions.filter { it.running && !it.isChild }.map { it.id }.toMutableSet()
        return ended
    }
    fun status(sessionId: String, active: Boolean): Boolean {
        if (active) { running.add(sessionId); return false }
        return running.remove(sessionId)
    }
}

data class CompletedReply(val seq: Long, val preview: String)
fun completedReply(snapshot: JsonObject): CompletedReply? {
    val events = snapshot["records"].array().map { it.obj()["event"].obj() }.sortedBy { it.long("seq") }
    val end = events.lastOrNull { it.text("type") == "turn/end" } ?: return null
    if (end["data"].obj()["reason"].obj().text("kind") != "completed") return null
    val turn = end["data"].obj().long("turn")
    val message = events.lastOrNull { it.text("type") == "assistant/message" && it.long("seq") < end.long("seq") && it["data"].obj().long("turn") == turn }
    return CompletedReply(end.long("seq"), blockText(message?.get("data").obj()["message"].obj()["content"]).take(240).ifBlank { "回复已完成，点击查看会话。" })
}

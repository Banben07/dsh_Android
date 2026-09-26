package dev.harness.core

import kotlinx.serialization.json.*

/** Replaces baselines on reconnect, detects gaps and keeps durable history separate from live attempts. */
class SessionJournal {
    private val events = sortedMapOf<Long, JsonObject>()
    var cursor: Long = -1; private set
    var hasMore = false; private set
    var initialized = false; private set
    var header: JsonObject = emptyObject; private set
    var projections: JsonObject = emptyObject; private set
    private var revision = -1L
    private var attempt = ""
    private var attemptTurn = 0L
    private var attemptStep = 0L
    private var startedAfter = -1L
    private var nextIndex = 0L
    private val blocks = sortedMapOf<Long, JsonObject>()
    private var cachedMessages: List<DisplayMessage>? = null
    val firstSeq: Long? get() = events.keys.firstOrNull()
    fun hasPrompt(requestId: String) = events.values.any { it.text("type") == "user/message" && it["data"].obj()["source"].obj().text("rpcId") == requestId }

    fun accept(value: JsonObject) {
        when (value.text("type")) {
            "snapshot" -> {
                cachedMessages = null
                events.clear(); blocks.clear(); attempt = ""
                cursor = value.long("cursor", -1)
                header = value["header"].obj()
                projections = value["projections"].obj()["values"].obj()
                addRecords(value["records"].array())
                hasMore = value.flag("hasMore")
                val stream = value["assistantStream"].obj()
                revision = stream.long("revision", -1)
                val active = stream["activeAttempt"].obj()
                attempt = active.text("attemptId")
                attemptTurn = active.long("turn"); attemptStep = active.long("step"); startedAfter = active.long("startedAfterSeq", -1)
                nextIndex = active.long("nextIndex")
                active["stream"].array().forEach { expandCompact(it.obj()) }
                initialized = true
            }
            "event" -> {
                check(initialized) { "历史快照尚未到达" }
                val event = value["event"].obj()
                val seq = event.long("seq", -1)
                if (seq <= cursor) return
                if (seq != cursor + 1) throw HarnessException("journal/gap", "历史事件不连续，正在重新同步")
                events[seq] = event; cursor = seq
                cachedMessages = null
            }
            "assistant-stream" -> acceptAssistant(value["frame"].obj())
        }
    }
    fun prepend(page: JsonObject) { addRecords(page["records"].array()); hasMore = page.flag("hasMore"); cachedMessages = null }
    private fun addRecords(records: JsonArray) {
        records.forEach { record ->
            val event = record.obj()["event"].obj()
            if (event.isNotEmpty()) events[event.long("seq")] = event
        }
    }
    private fun acceptAssistant(frame: JsonObject) {
        val rev = frame.long("revision")
        if (frame.text("type") == "start" && rev == 1L && attempt.isEmpty()) revision = 0
        if (rev <= revision) return
        if (revision >= 0 && rev != revision + 1) throw HarnessException("stream/gap", "回复流不连续，正在重新同步")
        revision = rev
        when (frame.text("type")) {
            "start" -> {
                cachedMessages = null
                if (attempt.isNotEmpty()) throw HarnessException("stream/gap", "上一段回复尚未结束，正在重新同步")
                attempt = frame.text("attemptId"); nextIndex = 0; blocks.clear()
                attemptTurn = frame.long("turn"); attemptStep = frame.long("step"); startedAfter = frame.long("startedAfterSeq", cursor)
            }
            "chunk" -> {
                if (frame.text("attemptId") != attempt || frame.long("index") != nextIndex) {
                    throw HarnessException("stream/gap", "回复片段顺序不一致，正在重新同步")
                }
                acceptChunk(frame["chunk"].obj()); nextIndex++
            }
            "end" -> {
                if (frame.text("attemptId") != attempt || frame.long("index") != nextIndex) {
                    throw HarnessException("stream/gap", "回复结束位置不一致，正在重新同步")
                }
                val outcome = frame["outcome"].obj()
                if (outcome.text("kind") == "committed" && events[outcome.long("seq")] == null) {
                    throw HarnessException("stream/gap", "回复历史尚未同步，正在重新读取")
                }
                attempt = ""; blocks.clear()
                cachedMessages = null
            }
        }
    }
    private fun expandCompact(r: JsonObject) {
        when (r.text("type")) {
            "text-chunks", "reasoning-chunks" -> r["texts"].array().forEach { text ->
                acceptChunk(jsonObject("type" to str(if (r.text("type") == "text-chunks") "text-delta" else "reasoning-delta"), "index" to r["index"], "text" to text))
            }
            "tool-call-chunks" -> r["args"].array().forEachIndexed { index, args ->
                acceptChunk(jsonObject("type" to str("tool-call-delta"), "index" to r["index"], "id" to r["id"], "name" to if (index == 0) r["name"] else null, "argumentsDelta" to args))
            }
            "chunk" -> acceptChunk(r["chunk"].obj())
        }
    }
    private fun acceptChunk(c: JsonObject) {
        val index = c.long("index")
        val previous = blocks[index] ?: emptyObject
        when (c.text("type")) {
            "block-start" -> blocks[index] = jsonObject("type" to c["blockType"])
            "text-delta", "reasoning-delta" -> {
                val type = if (c.text("type") == "text-delta") "text" else "reasoning"
                blocks[index] = jsonObject("type" to str(type), "text" to str(previous.text("text") + c.text("text")))
            }
            "tool-call-delta" -> blocks[index] = jsonObject(
                "type" to str("tool-call"), "id" to (c["id"] ?: previous["id"]), "name" to (c["name"] ?: previous["name"]),
                "arguments" to str(previous.text("arguments") + c.text("argumentsDelta")),
            )
            "block-end" -> blocks[index] = c["block"].obj()
        }
    }
    fun messages(): List<DisplayMessage> {
        val result = (cachedMessages ?: durableMessages().also { cachedMessages = it }).toMutableList()
        if (attempt.isNotEmpty()) {
            val content = JsonArray(blocks.values.toList())
            result += DisplayMessage("live-$attempt", "assistant", blockText(content),
                blocks.values.filter { it.text("type") == "reasoning" }.joinToString("\n") { it.text("text") }, streaming = true)
            blocks.entries.filter { it.value.text("type") == "tool-call" }.forEach { (index, block) ->
                result += DisplayMessage("live-tool-$attempt-$index", "tool", name = block.text("name"), arguments = block.text("arguments"), streaming = true, callId = block.text("id"))
            }
        }
        return result
    }
    private fun durableMessages(): List<DisplayMessage> {
        val result = mutableListOf<DisplayMessage>()
        val toolIndices = mutableMapOf<String, Int>()
        val commandIndices = mutableMapOf<String, Int>()
        // The journal is an audit timeline: replacements remain visible with a clear context marker.
        events.values.forEach { e ->
            val data = e["data"].obj(); val key = "event-${e.long("seq")}"; val type = e.text("type")
            if (e["surfaceOp"].obj().text("op") == "replace") {
                val content = if (type == "user/message") data["content"] else data["message"].obj()["content"]
                result += DisplayMessage("$key-context", "context", "服务端整理后的上下文：\n" + blockText(content))
                return@forEach
            }
            when (type) {
                "command/run" -> {
                    commandIndices[data.text("commandId")] = result.size
                    result += DisplayMessage(key, "command", "正在执行…", name = "/${data.text("name")}")
                }
                "command/done" -> {
                    val index = commandIndices[data.text("commandId")]
                    val text = data.text("text").ifBlank { if (data.text("kind") == "error") "执行失败" else "执行完成" }
                    if (index != null) result[index] = result[index].copy(text = text, isError = data.text("kind") == "error")
                    else result += DisplayMessage(key, "command", text, name = "命令结果", isError = data.text("kind") == "error")
                }
                "user/message" -> {
                    val source = data["source"].obj()
                    val human = source.text("kind") == "user"
                    val attachments = if (human) messageAttachments(data["content"]) else emptyList()
                    val content = if (attachments.isNotEmpty()) JsonArray(data["content"].array().filter { it.obj().text("type") !in listOf("image", "file") }) else data["content"]
                    result += DisplayMessage(key, if (human) "user" else "context", blockText(content), attachments = attachments)
                }
                "assistant/message" -> if (!isPendingSettlement(e)) {
                    val content = data["message"].obj()["content"].array()
                    val text = blockText(content)
                    val reasoning = content.filter { it.obj().text("type") == "reasoning" }.joinToString("\n") { it.obj().text("text") }
                    if (text.isNotBlank() || reasoning.isNotBlank()) result += DisplayMessage(key, "assistant", text, reasoning, interrupted = data.flag("interrupted"))
                }
                "tool/call" -> {
                    toolIndices[data.text("callId")] = result.size
                    result += DisplayMessage(key, "tool", name = data.text("name"), arguments = data.text("arguments"), callId = data.text("callId"))
                }
                "tool/result" -> {
                    val block = data["message"].obj()["content"].array().firstOrNull().obj()
                    val callId = block.text("callId").ifBlank { block.text("toolCallId") }
                    val index = toolIndices[callId]
                    val text = blockText(block["content"]).ifBlank { block["content"]?.let(::pretty).orEmpty() }
                    if (index != null) result[index] = result[index].copy(result = text, isError = block.flag("isError"))
                    else result += DisplayMessage(key, "tool", name = "工具结果", result = text, isError = block.flag("isError"))
                }
                "assistant/attempt" -> {
                    val errors = data["stream"].array().map { it.obj()["chunk"].obj() }.filter { it.text("type") == "error" }
                    if (errors.isNotEmpty()) result += DisplayMessage(key, "notice", errors.joinToString("\n") { it.text("message") }, isError = true)
                }
                "turn/end" -> {
                    val reason = data["reason"].obj()
                    when (reason.text("kind")) {
                        "error" -> result += DisplayMessage(key, "notice", reason["error"].obj().text("message").ifBlank { "本轮执行失败" }, isError = true)
                        "aborted", "interrupted" -> result += DisplayMessage(key, "notice", "本轮已停止")
                        "max-tokens" -> result += DisplayMessage(key, "notice", "回复达到输出长度上限")
                    }
                }
            }
        }
        return result
    }
    private fun isPendingSettlement(event: JsonObject): Boolean = attempt.isNotEmpty() &&
        event.long("seq") > startedAfter && event["data"].obj().long("turn") == attemptTurn &&
        event["data"].obj().long("step") == attemptStep && (event["surfaceOp"] == null || event["surfaceOp"].string() == "append")
}

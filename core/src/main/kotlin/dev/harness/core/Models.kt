package dev.harness.core

import kotlinx.serialization.json.*

data class SessionSummary(
    val id: String, val title: String, val cwd: String, val updatedAt: Long,
    val running: Boolean, val isChild: Boolean = false,
) {
    companion object {
        fun parse(value: JsonObject): SessionSummary {
            val id = value.text("sessionId")
            return SessionSummary(id,
                value["projections"].obj()["values"].obj()["title"].string().ifBlank { "新会话 · ${id.take(6)}" },
                value.text("cwd"), value.long("updatedAt"), value.flag("running"), value.text("origin") == "subagent")
        }
    }
}
data class Workspace(val id: String, val title: String, val path: String, val sessions: List<String>) {
    companion object { fun parse(j: JsonObject) = Workspace(j.text("workspaceId"), j.text("title"), j.text("path"), j["sessionIds"].array().map { it.string() }) }
}
data class ModelOption(val provider: String, val id: String, val name: String, val efforts: List<Pair<String, String>>, val defaultEffort: String)
data class Preset(val id: String, val name: String, val description: String, val isDefault: Boolean)
data class PendingQuestion(val eventId: String, val agentId: String, val kind: String, val request: JsonObject)
data class DisplayMessage(
    val key: String, val kind: String, val text: String = "", val reasoning: String = "",
    val name: String = "", val arguments: String = "", val result: String? = null,
    val isError: Boolean = false, val streaming: Boolean = false, val interrupted: Boolean = false,
)

fun blockText(blocks: JsonElement?): String = blocks.array().mapNotNull { raw ->
    val b = raw.obj()
    when (b.text("type")) {
        "text" -> b.text("text")
        "image" -> "[图片]"
        "file" -> "[文件：${b.text("name").ifBlank { b.text("path") }}]"
        "tool-result" -> blockText(b["content"])
        else -> null
    }
}.joinToString("\n")

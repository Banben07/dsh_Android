package dev.harness.android

import dev.harness.core.DisplayMessage
import kotlinx.serialization.json.JsonObject

/** Immutable fallback views survive subscription eviction and transport reconnects. */
internal class SessionViewCache {
    data class View(val messages: List<DisplayMessage>, val model: JsonObject, val hasMore: Boolean) {
        val characters: Long = messages.sumOf {
            it.key.length.toLong() + it.text.length + it.reasoning.length + it.arguments.length +
                (it.result?.length ?: 0) + it.name.length + it.callId.length +
                it.attachments.sumOf { file -> file.id.length + file.name.length }
        }
    }
    private val views = LinkedHashMap<String, View>(8, .75f, true)
    operator fun get(id: String): View? = views[id]
    fun put(id: String, view: View) {
        views.remove(id)
        // Bound both retained text and object counts, including very large tool histories.
        if (view.characters > 2_000_000 || view.messages.size > 500) return
        views[id] = view
        while (views.size > 6 || views.values.sumOf { it.characters } > 2_000_000) {
            views.remove(views.keys.first())
        }
    }
    fun remove(id: String) { views.remove(id) }
    fun clear() { views.clear() }
}

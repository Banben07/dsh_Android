package dev.harness.core

import kotlinx.serialization.json.*

val wireJson = Json { ignoreUnknownKeys = true }
val emptyObject = JsonObject(emptyMap())
fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: emptyObject
fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
fun JsonElement?.string(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.text(key: String): String = this[key].string()
fun JsonObject.long(key: String, fallback: Long = 0): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: fallback
fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
fun jsonObject(vararg fields: Pair<String, JsonElement?>) = JsonObject(fields.filter { it.second != null }.associate { it.first to it.second!! })
fun str(value: String) = JsonPrimitive(value)
fun parseObject(text: String) = wireJson.parseToJsonElement(text).jsonObject
fun pretty(value: JsonElement): String = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value)

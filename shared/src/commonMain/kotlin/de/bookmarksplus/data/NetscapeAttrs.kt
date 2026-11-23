package de.bookmarksplus.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Helper functions to map Netscape attributes losslessly onto columns + a JSON field `extra`.
 * Special keys in the JSON start with `#`.
 */
internal object NetscapeAttrs {
    const val ORDER = "#order"
    const val DD = "#dd"

    val LINK_COLUMNS = setOf("HREF", "ADD_DATE", "LAST_MODIFIED", "DESCRIPTION", "ICON")
    val FOLDER_COLUMNS = setOf("ADD_DATE", "LAST_MODIFIED")

    private val json = Json { encodeDefaults = false }

    /** Standard order like Chrome/Vivaldi: fixed attributes first, DESCRIPTION/ICON last. */
    fun canonicalOrder(keys: Collection<String>): List<String> {
        val head = listOf("HREF", "ADD_DATE", "LAST_MODIFIED").filter { it in keys }
        val tail = listOf("DESCRIPTION", "ICON").filter { it in keys }
        return head + keys.filter { it !in head && it !in tail } + tail
    }

    fun ordered(map: Map<String, String>, explicitOrder: List<String>?): LinkedHashMap<String, String> {
        val order = explicitOrder?.filter { it in map }?.let { it + map.keys.filter { k -> k !in it } }
            ?: canonicalOrder(map.keys)
        return LinkedHashMap<String, String>().apply { order.forEach { put(it, map.getValue(it)) } }
    }

    fun encode(values: Map<String, JsonElement>): String? =
        if (values.isEmpty()) null else json.encodeToString(JsonObject.serializer(), JsonObject(values))

    fun decode(text: String?): Map<String, JsonElement> =
        if (text.isNullOrEmpty()) emptyMap() else json.parseToJsonElement(text) as JsonObject

    fun string(e: JsonElement?): String? = if (e == null || e is JsonNull) null else e.jsonPrimitive.content

    /** `extra` for an element: all attributes without their own column, plus order if not canonical. */
    fun extraFor(attrs: Map<String, String>, columns: Set<String>, extraFlags: Map<String, JsonElement> = emptyMap()): String? {
        val values = LinkedHashMap<String, JsonElement>()
        attrs.forEach { (k, v) -> if (k !in columns) values[k] = JsonPrimitive(v) }
        val keys = attrs.keys.toList()
        if (keys != canonicalOrder(keys)) values[ORDER] = JsonPrimitive(keys.joinToString(","))
        values.putAll(extraFlags)
        return encode(values)
    }

    fun orderOf(extra: Map<String, JsonElement>): List<String>? = string(extra[ORDER])?.split(',')

    /** Difference of `target` compared to `base` (null value = attribute missing in target). */
    fun diff(base: Map<String, String>, target: Map<String, String>): LinkedHashMap<String, JsonElement> {
        val d = LinkedHashMap<String, JsonElement>()
        target.forEach { (k, v) -> if (base[k] != v) d[k] = JsonPrimitive(v) }
        base.keys.forEach { if (it !in target) d[it] = JsonNull }
        return d
    }

    fun applyDiff(base: Map<String, String>, diff: Map<String, JsonElement>): LinkedHashMap<String, String> {
        val m = LinkedHashMap(base)
        diff.forEach { (k, v) ->
            if (k == ORDER) return@forEach
            if (v is JsonNull) m.remove(k) else m[k] = v.jsonPrimitive.content
        }
        return m
    }
}

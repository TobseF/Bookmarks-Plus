package de.bookmarksplus.data

import de.bookmarksplus.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A stored image of a bookmark (screenshot of an application) including origin and hide flag. */
data class Shot(
    /** Content hash of the original (detects duplicates); for migrated legacy data the file path. */
    val hash: String,
    val path: String,
    val source: String,
    val hidden: Boolean = false,
)

/** Screenshots are stored as a JSON list in `bookmark.shots_json`: `[{"h":…, "p":…, "s":…, "x":0|1}]`. */
object Shots {
    const val UPLOAD = "upload"
    const val FLATHUB = "flathub"
    const val ALTERNATIVETO = "alternativeto"
    const val SOURCEFORGE = "sourceforge"
    const val ITCHIO = "itchio"
    const val UNKNOWN = "unknown"

    fun label(source: String): String = when (source) {
        UPLOAD -> "Upload"
        FLATHUB -> "Flathub"
        ALTERNATIVETO -> "AlternativeTo"
        SOURCEFORGE -> "SourceForge"
        ITCHIO -> "itch.io"
        else -> tr("shared.shots.unknown")
    }

    fun parse(json: String?): List<Shot> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val path = s("p") ?: return@mapNotNull null
            val x = (o["x"] as? JsonPrimitive)?.let { it.booleanOrNull ?: ((it.intOrNull ?: 0) != 0) } ?: false
            Shot(s("h") ?: path, path, s("s") ?: UNKNOWN, x)
        }
    }

    fun encode(shots: List<Shot>): String? =
        if (shots.isEmpty()) null
        else buildJsonArray {
            shots.forEach { s ->
                add(buildJsonObject { put("h", s.hash); put("p", s.path); put("s", s.source); put("x", if (s.hidden) 1 else 0) })
            }
        }.toString()

    /** Append new images; whatever is already there by hash (even hidden) stays unchanged. */
    fun merge(current: List<Shot>, added: List<Shot>): List<Shot> {
        val known = current.mapTo(HashSet()) { it.hash }
        return current + added.filter { known.add(it.hash) }
    }

    fun setHidden(current: List<Shot>, hash: String, hidden: Boolean): List<Shot> =
        current.map { if (it.hash == hash) it.copy(hidden = hidden) else it }
}

package de.bookmarksplus.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Saved search ("Smart Collection"): name + complete filter. */
@Serializable
data class SavedSearch(val id: String, val name: String, val filter: Filter)

/** Saved searches are stored as JSON in the setting table. */
class SavedSearchRepository(private val settings: SettingsRepository) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun list(): List<SavedSearch> =
        settings.get(KEY)?.let { runCatching { json.decodeFromString<List<SavedSearch>>(it) }.getOrNull() }.orEmpty()

    fun save(all: List<SavedSearch>) = settings.put(KEY, json.encodeToString(all))

    private companion object { const val KEY = "savedSearches" }
}

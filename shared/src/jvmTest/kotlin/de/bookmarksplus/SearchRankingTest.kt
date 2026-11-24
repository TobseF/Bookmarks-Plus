package de.bookmarksplus

import de.bookmarksplus.data.ContentLang
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.FilterEngine
import de.bookmarksplus.data.FolderInfo
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.PlacementInfo
import de.bookmarksplus.data.Relevance
import de.bookmarksplus.data.SearchScope
import de.bookmarksplus.data.SortField
import de.bookmarksplus.data.TagInfo
import de.bookmarksplus.data.Term
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRankingTest {

    private fun item(
        id: Long, title: String, description: String? = null, url: String = "https://example.com/$id",
        titleDe: String? = null, titleEn: String? = null, descDe: String? = null, descEn: String? = null, addDate: Long = id,
    ) = Item(
        id = id, url = url, title = title, aiTitle = null, description = description, summary = null, domain = "example.com",
        faviconHash = null, thumbnailPath = null, cardImagePath = null, cardImageUrl = null, repoUrl = null, websiteUrl = null,
        stars = null, lastCommit = null, lastRelease = null, lastReleaseDate = null, author = null, org = null, contentType = null,
        linkStatus = null, addDate = addDate, lastModified = null, lastVisited = null, urlNorm = url,
        titleDe = titleDe, titleEn = titleEn, descDe = descDe, descEn = descEn,
    )

    private class Fixture(
        val items: List<Item>,
        val folders: List<FolderInfo>,
        val placements: List<PlacementInfo>,
        val tags: List<TagInfo> = emptyList(),
        val tagsOf: Map<Long, Set<Long>> = emptyMap(),
    ) {
        fun lib(lang: ContentLang = ContentLang.DE) =
            Library(ROOT, folders, items, placements, tags, tagsOf).also { it.contentLang = lang }
    }

    private fun titles(lib: Library, text: String, scope: SearchScope = SearchScope.All) =
        FilterEngine.apply(lib, Filter(text = text, scope = scope), 0).map { it.title }

    @Test
    fun qualityLevels() {
        assertEquals(Relevance.FULL, Relevance.quality("CAD", Term("cad")))
        assertEquals(Relevance.WORD, Relevance.quality("3D CAD", Term("CAD")))
        assertEquals(Relevance.WORD - Relevance.CASE_MISS, Relevance.quality("3d cad", Term("CAD")))
        assertEquals(Relevance.PREFIX - Relevance.CASE_MISS, Relevance.quality("Abc Cadium", Term("CAD")))
        assertEquals(Relevance.INFIX, Relevance.quality("Arcade", Term("cad")))
        // camel case counts as word boundary
        assertEquals(Relevance.WORD, Relevance.quality("JetBrains", Term("brains")))
        assertEquals(0.0, Relevance.quality("nothing", Term("cad")))
    }

    @Test
    fun caseWholeWordAndTiers() {
        val f = Fixture(
            items = listOf(
                item(1, "Abc Cadium"),
                item(2, "3D CAD"),
                item(3, "Arcade games"),
                item(4, "Some tool", description = "a CAD program"),
                item(5, "Bracket"),
            ),
            folders = listOf(FolderInfo(ROOT, null, "root", 0), FolderInfo(10, ROOT, "Werkstatt", 0), FolderInfo(11, 10, "CAD", 0)),
            placements = listOf(
                PlacementInfo(1, 1, 10, 0, true, null), PlacementInfo(2, 2, 10, 1, true, null),
                PlacementInfo(3, 3, 10, 2, true, null), PlacementInfo(4, 4, 10, 3, true, null),
                PlacementInfo(5, 5, 11, 0, true, null),
            ),
        )
        val lib = f.lib()
        // title whole word with matching case › title word start › path hit › title inside word › other field
        assertEquals(listOf("3D CAD", "Abc Cadium", "Bracket", "Arcade games", "Some tool"), titles(lib, "CAD"))
        // folders are found as well
        val res = FilterEngine.search(lib, Filter(text = "cad"), 0)
        assertEquals(listOf(11L), res.folders.map { it.folder.id })
        assertEquals("Werkstatt", res.folders.single().parentPath)
        // tri-state scope
        assertEquals(emptyList(), titles(lib, "cad", SearchScope.Folders))
        assertTrue(FilterEngine.search(lib, Filter(text = "cad", scope = SearchScope.Bookmarks), 0).folders.isEmpty())
        // another sort order chosen while searching
        assertEquals(listOf("Abc Cadium", "Arcade games", "Bracket", "Some tool", "3D CAD").sorted(),
            FilterEngine.apply(lib, Filter(text = "cad", searchSort = SortField.Title, searchDescending = false), 0).map { it.title })
        // operators: no folder search
        assertTrue(FilterEngine.search(lib, Filter(text = "cad is:repo"), 0).folders.isEmpty())
    }

    @Test
    fun completeTagsBeforePartialTags() {
        val f = Fixture(
            items = listOf(item(1, "Alpha"), item(2, "Beta"), item(3, "Gamma", description = "kotlin")),
            folders = listOf(FolderInfo(ROOT, null, "root", 0)),
            placements = emptyList(),
            tags = listOf(TagInfo(100, "kotlinx", 0, 1), TagInfo(101, "kotlin", 0, 1)),
            tagsOf = mapOf(1L to setOf(100L), 2L to setOf(101L)),
        )
        assertEquals(listOf("Beta", "Alpha", "Gamma"), titles(f.lib(), "kotlin"))
    }

    @Test
    fun displayLanguageFirstAndNoDuplicates() {
        val f = Fixture(
            items = listOf(
                item(1, "x1", titleDe = "Werkzeugkasten", titleEn = "Toolbox"),
                item(2, "x2", titleDe = "Toolbox für Profis", titleEn = "Professional kit"),
                item(3, "Toolbox Klassiker"),
            ),
            folders = listOf(FolderInfo(ROOT, null, "root", 0), FolderInfo(10, ROOT, "Toolbox", 0), FolderInfo(11, ROOT, "Mehr", 0)),
            // bookmark 3 lies in two folders, one of them matches → still listed only once
            placements = listOf(PlacementInfo(1, 3, 10, 0, true, null), PlacementInfo(2, 3, 11, 0, false, null)),
        )
        assertEquals(listOf("Toolbox Klassiker", "x2", "x1"), titles(f.lib(ContentLang.DE), "toolbox"))
        val en = titles(f.lib(ContentLang.EN), "toolbox")
        assertEquals(listOf("x1", "Toolbox Klassiker", "x2"), en, "English title preferred with English display language")
        assertEquals(en.size, en.toSet().size)
    }

    @Test
    fun multipleTermsAcrossFields() {
        val f = Fixture(
            items = listOf(item(1, "Compose tutorial"), item(2, "Kotlin Compose", description = "UI"), item(3, "Compose Kotlin samples")),
            folders = listOf(FolderInfo(ROOT, null, "root", 0), FolderInfo(10, ROOT, "Kotlin", 0)),
            placements = listOf(PlacementInfo(1, 1, 10, 0, true, null)),
        )
        // phrase in the title first, then both words in the title, then one word in the path
        assertEquals(listOf("Kotlin Compose", "Compose Kotlin samples", "Compose tutorial"), titles(f.lib(), "kotlin compose"))
    }

    /** Rough timing with 10,000 synthetic bookmarks (prints; generous bound only). */
    @Test
    fun tenThousandBookmarksAreFast() {
        val rnd = Random(42)
        val words = "kotlin compose java script cad tool werkzeug design audio video linux windows android game engine " +
            "photo editor manager browser server docker cloud backup sync note markdown terminal shell git rust python"
        val dict = words.split(' ')
        fun text(n: Int) = (1..n).joinToString(" ") { dict[rnd.nextInt(dict.size)] + if (rnd.nextInt(5) == 0) "s" else "" }
        val folders = listOf(FolderInfo(ROOT, null, "root", 0)) + (1..1500L).map { FolderInfo(it, if (it < 30) ROOT else rnd.nextLong(1, it), text(2), it) }
        val items = (1..10_000L).map {
            item(it, text(4), description = text(40), titleDe = text(4), titleEn = text(4), descDe = text(30), descEn = text(30))
        }
        val placements = items.map { PlacementInfo(it.id, it.id, rnd.nextLong(1, 1500), 0, true, null) }
        val tags = (1..300L).map { TagInfo(1000 + it, text(1) + it, 0, 1) }
        val tagsOf = items.associate { it.id to setOf(1000 + rnd.nextLong(1, 300)) }
        val lib = Library(ROOT, folders, items, placements, tags, tagsOf)
        for (q in listOf("a", "cad", "Kotlin compose", "werkzeug linux", "xyz")) {
            FilterEngine.search(lib, Filter(text = q), 0) // warm-up (lazy caches)
            val t0 = System.nanoTime()
            val r = FilterEngine.search(lib, Filter(text = q), 0)
            val ms = (System.nanoTime() - t0) / 1e6
            println("search '$q': ${"%.1f".format(ms)} ms → ${r.items.size} bookmarks, ${r.folders.size} folders")
            assertTrue(ms < 2000)
        }
    }

    private companion object { const val ROOT = 0L }
}

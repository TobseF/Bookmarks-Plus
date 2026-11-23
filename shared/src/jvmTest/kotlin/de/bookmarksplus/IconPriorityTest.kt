package de.bookmarksplus

import de.bookmarksplus.data.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Icon ranking: Flathub → AlternativeTo → favicon of the (stored) website → GitHub avatar → GitHub logo. */
class IconPriorityTest {
    private fun item(url: String, repoUrl: String? = null, domain: String? = null, favicon: String? = null, avatar: String? = null,
                     flathub: String? = null, alt: String? = null, site: String? = null) = Item(
        id = 1, url = url, title = "t", aiTitle = null, description = null, summary = null, domain = domain, faviconHash = favicon,
        thumbnailPath = null, cardImagePath = null, cardImageUrl = null, repoUrl = repoUrl, websiteUrl = null, stars = null,
        lastCommit = null, lastRelease = null, lastReleaseDate = null, author = null, org = null, contentType = null,
        linkStatus = null, addDate = null, lastModified = null, lastVisited = null, urlNorm = url,
        avatarHash = avatar, flathubIconHash = flathub, alternativetoIconHash = alt, websiteIconHash = site,
    )

    @Test
    fun flathubBeatsEverything() {
        assertEquals("fh", item("https://github.com/a/b", flathub = "fh", alt = "at", site = "ws", avatar = "av", favicon = "fv").iconHash)
    }

    @Test
    fun alternativetoThenWebsiteThenAvatar() {
        assertEquals("at", item("https://github.com/a/b", alt = "at", site = "ws", avatar = "av").iconHash)
        assertEquals("ws", item("https://github.com/a/b", site = "ws", avatar = "av").iconHash)
        assertEquals("av", item("https://github.com/a/b", avatar = "av").iconHash)
    }

    @Test
    fun githubRepoIgnoresGithubsOwnFavicon() {
        // the favicon of github.com is not "the stored website"
        assertEquals("av", item("https://github.com/a/b", favicon = "gh", avatar = "av").iconHash)
        assertNull(item("https://github.com/a/b", favicon = "gh").iconHash)
    }

    @Test
    fun plainBookmarkUsesItsFavicon() {
        assertEquals("fv", item("https://example.org/", domain = "example.org", favicon = "fv", avatar = "av").iconHash)
    }

    @Test
    fun githubFallbackOnlyForRepos() {
        assertTrue(item("https://github.com/a/b", domain = "github.com").iconIsGithubFallback)
        assertTrue(item("https://proj.example/", repoUrl = "https://github.com/a/b").iconIsGithubFallback)
        assertFalse(item("https://example.org/", domain = "example.org").iconIsGithubFallback)
        assertFalse(item("https://github.com/a/b", domain = "github.com", avatar = "av").iconIsGithubFallback)
    }
}

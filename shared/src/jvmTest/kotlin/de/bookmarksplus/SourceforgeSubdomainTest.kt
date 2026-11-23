package de.bookmarksplus

import de.bookmarksplus.util.Urls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceforgeSubdomainTest {
    @Test fun subdomainBecomesProject() {
        assertEquals("egoboo", Urls.sourceforgeSubdomainProject("https://egoboo.sourceforge.net/"))
        assertEquals("egoboo", Urls.anySourceforgeProject("http://EgoBoo.sourceforge.net/index.html"))
        assertEquals("https://sourceforge.net/projects/egoboo/", Urls.sourceforgeProjectUrl("egoboo"))
    }
    @Test fun reservedAndPlainHostsIgnored() {
        assertNull(Urls.sourceforgeSubdomainProject("https://sourceforge.net/projects/egoboo/"))
        assertNull(Urls.sourceforgeSubdomainProject("https://www.sourceforge.net/"))
        assertNull(Urls.sourceforgeSubdomainProject("https://downloads.sourceforge.net/x"))
    }
}

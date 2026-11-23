package de.bookmarksplus.util

object Urls {
    /** Version of the normal form – on changes, stored `url_norm` values are recomputed. */
    const val NORM_VERSION = 2
    private val INDEX_FILE = Regex("/(index|default)\\.(html?|php|aspx?|jsp)$", RegexOption.IGNORE_CASE)
    private val TRACKING_PARAMS = Regex("^(utm_[a-z]+|fbclid|gclid|msclkid|mc_cid|mc_eid|ref|ref_src|igshid|si)$", RegexOption.IGNORE_CASE)

    /** Host without `www.`, lowercase; null for non-HTTP URLs (javascript:, file: …). */
    fun domain(url: String): String? {
        val m = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]*)").find(url) ?: return null
        var host = m.groupValues[1].substringAfterLast('@').substringBefore(':').lowercase()
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        return host.ifEmpty { null }
    }

    /**
     * Normal form for duplicate detection: scheme ignored (http/https), without `www.`, without fragment,
     * without tracking parameters, without trailing slash.
     */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$").find(trimmed)
            ?: return trimmed.lowercase()
        val (scheme, hostRaw, pathRaw, queryRaw) = m.destructured
        val schemeNorm = scheme.lowercase().let { if (it == "https") "http" else it }
        var host = hostRaw.lowercase().removePrefix("www.")
        if (host.endsWith(":80") || host.endsWith(":443")) host = host.substringBeforeLast(':')
        val path = pathRaw.replace(INDEX_FILE, "").trimEnd('/')
        val query = queryRaw.removePrefix("?").split('&')
            .filter { it.isNotEmpty() && !TRACKING_PARAMS.matches(it.substringBefore('=')) }
            .sorted()
            .joinToString("&")
        return buildString {
            append(schemeNorm).append("://").append(host).append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
    }

    /**
     * Is [to] a real redirect from [from]? Not: only http→https, `www.`, slash, tracking parameters.
     */
    fun isMeaningfulRedirect(from: String, to: String?): Boolean = to != null && normalize(from) != normalize(to)

    /** Redirect from a subpage to the home page – usually a sign of a deleted page. */
    fun redirectsToHome(from: String, to: String?): Boolean {
        if (to == null) return false
        val fromPath = Regex("^[a-zA-Z]+://[^/?#]*([^?#]*)").find(from)?.groupValues?.get(1)?.trim('/').orEmpty()
        val toPath = Regex("^[a-zA-Z]+://[^/?#]*([^?#]*)").find(to)?.groupValues?.get(1)?.trim('/').orEmpty()
        return fromPath.isNotEmpty() && (toPath.isEmpty() || toPath.length <= 3)
    }

    private val SOURCEFORGE = Regex("""^https?://(?:www\.)?sourceforge\.net/(?:projects|p)/([A-Za-z0-9][A-Za-z0-9._-]*)""", RegexOption.IGNORE_CASE)

    /** Short name of the SourceForge project (`sourceforge.net/projects/<name>` or `/p/<name>`). */
    fun sourceforgeProject(url: String): String? = SOURCEFORGE.find(url.trim())?.groupValues?.get(1)?.lowercase()

    private val SOURCEFORGE_SUBDOMAIN = Regex("""^https?://([a-z0-9][a-z0-9-]*)\.(?:sourceforge\.net|sf\.net)(?:[:/?#]|$)""", RegexOption.IGNORE_CASE)
    private val SOURCEFORGE_RESERVED = setOf("www", "web", "downloads", "download", "sourceforge", "p", "a", "svn", "git", "cvs", "hg", "static", "fsdn", "master", "forge", "sf", "sourceforge-mirror", "cdn", "images", "apps", "sitemap")

    /** Project subdomain scheme (`<name>.sourceforge.net`) → short name of the project. */
    fun sourceforgeSubdomainProject(url: String): String? {
        val name = SOURCEFORGE_SUBDOMAIN.find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return null
        return name.takeIf { it !in SOURCEFORGE_RESERVED }
    }

    /** Short name of the SourceForge project from project URL or subdomain URL. */
    fun anySourceforgeProject(url: String): String? = sourceforgeProject(url) ?: sourceforgeSubdomainProject(url)

    /** Reconstructed project page `https://sourceforge.net/projects/<name>/`. */
    fun sourceforgeProjectUrl(project: String): String = "https://sourceforge.net/projects/$project/"

    private val GITHUB_REPO = Regex("^https?://(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
    private val GITHUB_RESERVED = setOf("orgs", "topics", "trending", "collections", "marketplace", "settings", "features", "sponsors", "apps", "about", "explore", "search", "login", "pricing")

    /** Returns `owner/repo` if the URL points to a GitHub repository. */
    fun githubRepo(url: String): Pair<String, String>? {
        val m = GITHUB_REPO.find(url) ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        if (owner.lowercase() in GITHUB_RESERVED) return null
        return owner to repo
    }
}

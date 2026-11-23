package de.bookmarksplus.enrich

import de.bookmarksplus.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

data class GitHubInfo(
    val owner: String,
    val name: String,
    val description: String?,
    val homepage: String?,
    val stars: Long,
    val lastCommit: Long?,
    val latestRelease: String?,
    val latestReleaseDate: Long?,
    /** Owner is a user account → author. */
    val author: String?,
    /** Owner is an organization. */
    val organization: String?,
    val readme: String?,
    val archived: Boolean,
    /** GitHub topics of the repo. */
    val topics: List<String> = emptyList(),
    /** SPDX identifier ("MIT", "GPL-3.0") or name if GitHub knows no identifier. */
    val license: String? = null,
) {
    val url: String get() = "https://github.com/$owner/$name"
    /** Social card that GitHub generates for every repo. */
    val cardUrl: String get() = "https://opengraph.githubassets.com/1/$owner/$name"
}

class GitHubRateLimited(val resetAt: Long) : Exception(tr("enrich.github.rateLimit", Instant.ofEpochSecond(resetAt)))

/**
 * GitHub data. With token: one GraphQL request per repo (5,000/h). Without token: REST (60 requests/h)
 * and README via raw.githubusercontent.com (does not count towards the API limit).
 */
class GitHubClient(private val http: Http, private val token: () -> String?) {

    /** Token was rejected by GitHub (expired/revoked) → continue without token for this session. */
    @Volatile var tokenRejected: Boolean = false
        private set

    private val json = Json { ignoreUnknownKeys = true }

    /** null = repo does not (or no longer) exist. */
    fun fetch(owner: String, repo: String): GitHubInfo? {
        val t = token()?.trim()?.takeIf { it.isNotEmpty() && !tokenRejected }
        return if (t != null) graphql(owner, repo, t) else rest(owner, repo)
    }

    fun downloadCard(info: GitHubInfo): ByteArray? =
        http.get(info.cardUrl, maxBytes = 4 shl 20, accept = Http.ACCEPT_IMAGE).takeIf { it.ok }?.body

    // ---------------------------------------------------------------- GraphQL

    private fun graphql(owner: String, repo: String, token: String): GitHubInfo? {
        val readmes = README_NAMES.mapIndexed { i, n ->
            "r$i: object(expression: \"HEAD:$n\") { ... on Blob { text } }"
        }.joinToString("\n")
        val query = """
            query(${'$'}o: String!, ${'$'}r: String!) {
              repository(owner: ${'$'}o, name: ${'$'}r) {
                name description homepageUrl stargazerCount isArchived pushedAt
                owner { login __typename }
                defaultBranchRef { target { ... on Commit { committedDate } } }
                latestRelease { tagName publishedAt }
                repositoryTopics(first: 20) { nodes { topic { name } } }
                licenseInfo { spdxId name }
                $readmes
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject { put("o", owner); put("r", repo) })
        }.toString()
        val res = http.post("https://api.github.com/graphql", body, mapOf("Authorization" to "bearer $token"))
        checkRateLimit(res)
        if (res.status == 401) {
            tokenRejected = true
            return rest(owner, repo)
        }
        val root = res.text()?.let { json.parseToJsonElement(it).jsonObject } ?: error(tr("enrich.github.unreachable", res.status))
        val r = (root["data"] as? JsonObject)?.get("repository") as? JsonObject ?: return null
        val ownerObj = r.obj("owner")
        val ownerLogin = ownerObj?.str("login") ?: owner
        val isOrg = ownerObj?.str("__typename") == "Organization"
        val readme = README_NAMES.indices.firstNotNullOfOrNull { i -> r.obj("r$i")?.str("text") }
        return GitHubInfo(
            owner = ownerLogin,
            name = r.str("name") ?: repo,
            description = r.str("description"),
            homepage = r.str("homepageUrl")?.takeIf { it.isNotBlank() },
            stars = r["stargazerCount"]?.jsonPrimitive?.longOrNull ?: 0,
            lastCommit = r.obj("defaultBranchRef")?.obj("target")?.str("committedDate")?.let(::epoch) ?: r.str("pushedAt")?.let(::epoch),
            latestRelease = r.obj("latestRelease")?.str("tagName"),
            latestReleaseDate = r.obj("latestRelease")?.str("publishedAt")?.let(::epoch),
            author = if (isOrg) null else ownerLogin,
            organization = if (isOrg) ownerLogin else null,
            readme = readme?.take(MAX_README),
            archived = r["isArchived"]?.jsonPrimitive?.content == "true",
            topics = (r.obj("repositoryTopics")?.get("nodes") as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.obj("topic")?.str("name") },
            license = licenseOf(r.obj("licenseInfo")?.str("spdxId"), r.obj("licenseInfo")?.str("name")),
        )
    }

    // ---------------------------------------------------------------- REST

    private fun rest(owner: String, repo: String): GitHubInfo? {
        val headers = mapOf("X-GitHub-Api-Version" to "2022-11-28")
        val res = http.get("https://api.github.com/repos/$owner/$repo", accept = "application/vnd.github+json", extraHeaders = headers)
        checkRateLimit(res)
        if (res.status == 404) return null
        if (!res.ok) error("GitHub-API: HTTP ${res.status}")
        val r = json.parseToJsonElement(res.text()!!).jsonObject
        val ownerObj = r.obj("owner")
        val ownerLogin = ownerObj?.str("login") ?: owner
        val name = r.str("name") ?: repo
        val isOrg = ownerObj?.str("type") == "Organization"

        val rel = http.get("https://api.github.com/repos/$ownerLogin/$name/releases/latest", accept = "application/vnd.github+json", extraHeaders = headers)
        checkRateLimit(rel)
        val release = if (rel.ok) rel.text()?.let { json.parseToJsonElement(it).jsonObject } else null

        val branch = r.str("default_branch") ?: "HEAD"
        val readme = README_NAMES.firstNotNullOfOrNull { n ->
            http.get("https://raw.githubusercontent.com/$ownerLogin/$name/$branch/$n", accept = "text/plain").takeIf { it.ok }?.text()
        }
        return GitHubInfo(
            owner = ownerLogin,
            name = name,
            description = r.str("description"),
            homepage = r.str("homepage")?.takeIf { it.isNotBlank() },
            stars = r["stargazers_count"]?.jsonPrimitive?.longOrNull ?: 0,
            lastCommit = r.str("pushed_at")?.let(::epoch),
            latestRelease = release?.str("tag_name"),
            latestReleaseDate = release?.str("published_at")?.let(::epoch),
            author = if (isOrg) null else ownerLogin,
            organization = if (isOrg) ownerLogin else null,
            readme = readme?.take(MAX_README),
            archived = r["archived"]?.jsonPrimitive?.content == "true",
            topics = (r["topics"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            license = licenseOf(r.obj("license")?.str("spdx_id"), r.obj("license")?.str("name")),
        )
    }

    /**
     * README only: first via raw.githubusercontent.com (default branch via `HEAD`, does not count towards the API limit),
     * otherwise via the API (follows renamed or moved repos; 1 request). null = no README or repo gone.
     */
    fun readme(owner: String, repo: String): String? {
        for (n in README_NAMES) {
            val r = http.get("https://raw.githubusercontent.com/$owner/$repo/HEAD/$n", accept = "text/plain")
            if (r.ok) r.text()?.takeIf { it.isNotBlank() }?.let { return it.take(MAX_README) }
        }
        val headers = buildMap {
            put("X-GitHub-Api-Version", "2022-11-28")
            token()?.trim()?.takeIf { it.isNotEmpty() && !tokenRejected }?.let { put("Authorization", "Bearer $it") }
        }
        val res = http.get("https://api.github.com/repos/$owner/$repo/readme", accept = "application/vnd.github.raw+json", extraHeaders = headers)
        checkRateLimit(res)
        return if (res.ok) res.text()?.takeIf { it.isNotBlank() }?.take(MAX_README) else null
    }

    /**
     * License only (one request; for repos that were enriched before license detection).
     * null = no license specified or repo not found.
     */
    fun license(owner: String, repo: String): String? {
        val t = token()?.trim()?.takeIf { it.isNotEmpty() && !tokenRejected }
        if (t != null) {
            val body = buildJsonObject {
                put("query", "query(${'$'}o: String!, ${'$'}r: String!) { repository(owner: ${'$'}o, name: ${'$'}r) { licenseInfo { spdxId name } } }")
                put("variables", buildJsonObject { put("o", owner); put("r", repo) })
            }.toString()
            val res = http.post("https://api.github.com/graphql", body, mapOf("Authorization" to "bearer $t"))
            checkRateLimit(res)
            if (res.ok) {
                val li = res.text()?.let { json.parseToJsonElement(it).jsonObject }?.obj("data")?.obj("repository")?.obj("licenseInfo")
                return licenseOf(li?.str("spdxId"), li?.str("name"))
            }
        }
        val res = http.get("https://api.github.com/repos/$owner/$repo", accept = "application/vnd.github+json", extraHeaders = mapOf("X-GitHub-Api-Version" to "2022-11-28"))
        checkRateLimit(res)
        if (!res.ok) return null
        val li = res.text()?.let { json.parseToJsonElement(it).jsonObject }?.obj("license")
        return licenseOf(li?.str("spdx_id"), li?.str("name"))
    }

    /** "NOASSERTION" (GitHub does not recognize the license) → name ("Other" is discarded). */
    private fun licenseOf(spdx: String?, name: String?): String? =
        spdx?.takeIf { it.isNotBlank() && it != "NOASSERTION" } ?: name?.takeIf { it.isNotBlank() && it != "Other" }

    private fun checkRateLimit(res: FetchResult) {
        if ((res.status == 403 || res.status == 429) && res.header("x-ratelimit-remaining") == "0") {
            throw GitHubRateLimited(res.header("x-ratelimit-reset")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000 + 3600))
        }
    }

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.content }
    private fun epoch(iso: String): Long? = runCatching { Instant.parse(iso).epochSecond }.getOrNull()

    companion object {
        val README_NAMES = listOf("README.md", "readme.md", "Readme.md", "README.MD", "README.rst", "README", "README.markdown", "README.txt", "docs/README.md")
        const val MAX_README = 200_000
    }
}

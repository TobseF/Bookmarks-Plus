package de.bookmarksplus.enrich.apps

import de.bookmarksplus.data.AppInfo
import java.net.URI
import java.net.URLDecoder

/**
 * Facts already contained in the URL: Flathub/AlternativeTo page, store entries (platform/system),
 * package directories (package ID). Costs no request.
 */
object AppUrls {

    fun facts(url: String): AppInfo {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return AppInfo.EMPTY
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return AppInfo.EMPTY
        val path = u.rawPath.orEmpty()
        val seg = path.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }
        fun query(k: String) = u.rawQuery?.split('&')?.firstOrNull { it.startsWith("$k=") }?.substringAfter('=')?.let { URLDecoder.decode(it, Charsets.UTF_8) }
        return when {
            host == "flathub.org" -> AppInfo.flathubIdOf(url)?.let { AppInfo(flathubUrl = AppInfo.flathubUrl(it), platforms = PC, systems = LINUX) }
            host == "alternativeto.net" -> AppInfo.alternativetoSlugOf(url)?.let { AppInfo(alternativetoUrl = AppInfo.alternativetoUrl(it)) }
            host == "play.google.com" && query("id") != null -> AppInfo(platforms = listOf("smartphone"), systems = listOf("android"))
            host == "f-droid.org" && seg.getOrNull(0)?.length == 2 || host == "f-droid.org" && "packages" in seg ->
                AppInfo(platforms = listOf("smartphone"), systems = listOf("android"))
            host == "apps.apple.com" -> AppInfo(platforms = listOf(if (query("mt") == "12") "pc" else "smartphone"), systems = if (query("mt") == "12") listOf("mac") else emptyList())
            host == "chromewebstore.google.com" || (host == "chrome.google.com" && seg.firstOrNull() == "webstore") ||
                host == "addons.mozilla.org" || host == "microsoftedge.microsoft.com" -> AppInfo(platforms = listOf("browser"))
            host == "hub.docker.com" && (seg.firstOrNull() == "r" || seg.firstOrNull() == "_") -> AppInfo(platforms = listOf("docker"))
            host == "npmjs.com" && seg.firstOrNull() == "package" && seg.size >= 2 ->
                AppInfo(npm = if (seg[1].startsWith("@") && seg.size >= 3) "${seg[1]}/${seg[2]}" else seg[1])
            host == "formulae.brew.sh" && seg.size >= 2 && seg[0] == "formula" -> AppInfo(brew = seg[1], systems = listOf("mac"))
            host == "formulae.brew.sh" && seg.size >= 2 && seg[0] == "cask" -> AppInfo(brew = "--cask ${seg[1]}", platforms = PC, systems = listOf("mac"))
            (host == "community.chocolatey.org" || host == "chocolatey.org") && seg.size >= 2 && seg[0] == "packages" ->
                AppInfo(choco = seg[1], platforms = PC, systems = WINDOWS)
            host == "winstall.app" && seg.size >= 2 && seg[0] == "apps" -> AppInfo(winget = seg[1], platforms = PC, systems = WINDOWS)
            host == "winget.run" && seg.size >= 3 && seg[0] == "pkg" -> AppInfo(winget = "${seg[1]}.${seg[2]}", platforms = PC, systems = WINDOWS)
            host == "apps.microsoft.com" -> AppInfo(platforms = PC, systems = WINDOWS)
            host == "snapcraft.io" && seg.size == 1 -> AppInfo(platforms = PC, systems = LINUX)
            host == "store.steampowered.com" && seg.firstOrNull() == "app" -> AppInfo(platforms = PC)
            else -> null
        } ?: AppInfo.EMPTY
    }

    private val PC = listOf("pc")
    private val LINUX = listOf("linux")
    private val WINDOWS = listOf("windows")
}

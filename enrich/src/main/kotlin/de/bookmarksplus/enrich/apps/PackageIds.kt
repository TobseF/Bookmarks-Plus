package de.bookmarksplus.enrich.apps

import de.bookmarksplus.data.PackageManager

/**
 * Finds package IDs in install commands (README, page text, AlternativeTo "Install" boxes):
 * `winget install -e --id Foo.Bar`, `choco install foo`, `scoop install extras/foo`, `brew install --cask foo`,
 * `npm install -g foo`, `flatpak install flathub org.foo.Bar`.
 */
object PackageIds {

    data class Found(
        val winget: String? = null, val choco: String? = null, val scoop: String? = null,
        val brew: String? = null, val npm: String? = null, val flatpak: String? = null,
    ) {
        fun get(pm: PackageManager): String? = when (pm) {
            PackageManager.Winget -> winget; PackageManager.Chocolatey -> choco; PackageManager.Scoop -> scoop
            PackageManager.Brew -> brew; PackageManager.Npm -> npm
        }
        val isEmpty: Boolean get() = listOfNotNull(winget, choco, scoop, brew, npm, flatpak).isEmpty()
    }

    private const val ID = """[A-Za-z0-9@][A-Za-z0-9._+/@-]*"""

    // options before the name: -e, --exact, --id, --cask, -g, --global, -y …
    private val WINGET = Regex("""\bwinget(?:\.exe)?\s+install\s+((?:--?[a-zA-Z-]+(?:[ =](?!-)\S+)?\s+)*)($ID)""", RegexOption.IGNORE_CASE)
    private val WINGET_ID = Regex("""--id[ =](\S+)""", RegexOption.IGNORE_CASE)
    private val CHOCO = Regex("""\b(?:choco|cinst)(?:\.exe)?\s+(?:install\s+)?((?:-[-a-zA-Z]+\s+)*)($ID)""", RegexOption.IGNORE_CASE)
    private val SCOOP = Regex("""\bscoop\s+install\s+((?:-[-a-zA-Z]+\s+)*)($ID)""", RegexOption.IGNORE_CASE)
    private val BREW = Regex("""\bbrew\s+install\s+((?:--?[a-zA-Z-]+\s+)*)($ID)""", RegexOption.IGNORE_CASE)
    private val NPM = Regex("""\b(?:npm\s+(?:install|i|add)|pnpm\s+(?:add|install)|yarn\s+global\s+add|bun\s+(?:add|install))\s+((?:-[-a-zA-Z]+\s+)*)($ID)""")
    private val FLATPAK = Regex("""\bflatpak\s+install\s+(?:--?[a-zA-Z-]+\s+)*(?:flathub\s+)?([A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+){2,})""")

    /** All finds per package manager (in order of appearance). */
    fun all(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        fun add(k: String, v: String?) {
            if (v == null) return
            val l = out.getOrPut(k) { mutableListOf() }
            if (v !in l) l += v
        }
        WINGET.findAll(text).forEach { m ->
            // "--id" comes before the name; skip other options with a value (--source winget)
            val id = WINGET_ID.find(m.groupValues[1])?.groupValues?.get(1) ?: m.groupValues[2]
            add("winget", clean(id)?.takeUnless { it.startsWith("-") })
        }
        CHOCO.findAll(text).forEach { m -> add("choco", clean(m.groupValues[2])?.takeUnless { it.lowercase() in CHOCO_WORDS }) }
        SCOOP.findAll(text).forEach { m -> add("scoop", clean(m.groupValues[2])?.takeUnless { it.lowercase() == "install" }) }
        BREW.findAll(text).forEach { m ->
            val cask = "--cask" in m.groupValues[1]
            add("brew", clean(m.groupValues[2])?.let { if (cask) "--cask $it" else it })
        }
        NPM.findAll(text).forEach { m -> add("npm", clean(m.groupValues[2])?.takeUnless { it.startsWith("-") || it.startsWith(".") }) }
        FLATPAK.findAll(text).forEach { m -> add("flatpak", m.groupValues[1].trimEnd('.')) }
        return out
    }

    /**
     * Package IDs that match the application [appName] or the repo [repo]. Without a name only those are adopted
     * that are unambiguous for the package manager (READMEs often install dependencies too).
     */
    fun find(text: String, appName: String? = null, repo: String? = null): Found {
        val all = all(text)
        val names = listOfNotNull(appName, repo).map(AppNames::key).filter { it.length >= 2 }.toSet()
        fun pick(k: String): String? {
            val list = all[k].orEmpty()
            if (list.isEmpty()) return null
            if (names.isEmpty()) return list.singleOrNull()
            return list.firstOrNull { id -> names.any { n -> matches(id, n) } }
        }
        return Found(pick("winget"), pick("choco"), pick("scoop"), pick("brew"), pick("npm"), pick("flatpak"))
    }

    /** Does a package ID match the name? "FileZilla.FileZilla", "filezilla", "extras/filezilla", "@scope/filezilla-cli". */
    fun matches(id: String, nameKey: String): Boolean {
        val bare = id.removePrefix("--cask ")
        val parts = bare.split('/', '.', '@').map(AppNames::key).filter { it.isNotEmpty() }
        if (parts.any { it == nameKey }) return true
        val last = AppNames.key(bare.substringAfterLast('/').substringAfterLast('.'))
        return nameKey.length >= 4 && (last.startsWith(nameKey) || AppNames.key(bare).contains(nameKey))
    }

    private val CHOCO_WORDS = setOf("install", "upgrade", "uninstall", "list", "search", "info", "pack", "push", "new", "source", "feature", "config")

    private fun clean(id: String): String? =
        id.trim().trimEnd('.', ',', ';', ')', '`', '"', '\'', ':')
            .takeIf { it.isNotEmpty() && it.length <= 120 && !it.contains('<') && !it.contains('$') }
}

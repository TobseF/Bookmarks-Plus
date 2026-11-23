package de.bookmarksplus.data

/** Platform on which an application runs. */
enum class AppPlatform(val key: String, val label: String) {
    Pc("pc", "PC"), Smartphone("smartphone", "Smartphone"), Browser("browser", "Browser"), Docker("docker", "Docker");

    companion object {
        fun of(key: String): AppPlatform? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** Operating system of an application. */
enum class AppSystem(val key: String, val label: String) {
    Windows("windows", "Windows"), Linux("linux", "Linux"), Mac("mac", "Mac"), Android("android", "Android");

    companion object {
        fun of(key: String): AppSystem? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** Package manager with install command. */
enum class PackageManager(val key: String, val label: String, val command: (String) -> String) {
    Winget("winget", "winget", { "winget install --id $it -e" }),
    Chocolatey("choco", "Chocolatey", { "choco install $it" }),
    Scoop("scoop", "Scoop", { "scoop install $it" }),
    Brew("brew", "Homebrew", { "brew install $it" }),
    Npm("npm", "npm", { "npm install -g $it" }),
}

/**
 * App data of a bookmark (programs and games). Empty fields are hidden in the view.
 * [nameSource]: user, flathub, alternativeto, sourceforge, ai, heuristic – or "none" if the AI determined
 * that it is not a program.
 */
data class AppInfo(
    val name: String? = null,
    val nameSource: String? = null,
    val platforms: List<String> = emptyList(),
    val systems: List<String> = emptyList(),
    val license: String? = null,
    val winget: String? = null,
    val choco: String? = null,
    val brew: String? = null,
    val npm: String? = null,
    val scoop: String? = null,
    val flathubUrl: String? = null,
    val alternativetoUrl: String? = null,
    val flathubInstalls: Long? = null,
    val flathubDownloadSize: Long? = null,
    val flathubInstalledSize: Long? = null,
    /** Manually stored GitHub repo (column `repo_url`); editor only. */
    val repoUrl: String? = null,
) {
    /** Package IDs that are set, in a fixed order. */
    val packages: List<Pair<PackageManager, String>>
        get() = listOfNotNull(
            winget?.let { PackageManager.Winget to it }, choco?.let { PackageManager.Chocolatey to it },
            scoop?.let { PackageManager.Scoop to it }, brew?.let { PackageManager.Brew to it }, npm?.let { PackageManager.Npm to it },
        )

    fun pkg(pm: PackageManager): String? = when (pm) {
        PackageManager.Winget -> winget; PackageManager.Chocolatey -> choco; PackageManager.Scoop -> scoop
        PackageManager.Brew -> brew; PackageManager.Npm -> npm
    }

    fun withPkg(pm: PackageManager, id: String?): AppInfo = when (pm) {
        PackageManager.Winget -> copy(winget = id); PackageManager.Chocolatey -> copy(choco = id); PackageManager.Scoop -> copy(scoop = id)
        PackageManager.Brew -> copy(brew = id); PackageManager.Npm -> copy(npm = id)
    }

    /** Has any app data (then the detail view shows the "Program" section). */
    val isApp: Boolean
        get() = name != null || platforms.isNotEmpty() || systems.isNotEmpty() || license != null || packages.isNotEmpty() ||
            flathubUrl != null || alternativetoUrl != null

    /** Flatpak ID from the Flathub URL. */
    val flathubId: String? get() = flathubUrl?.let(::flathubIdOf)

    companion object {
        val EMPTY = AppInfo()

        fun split(csv: String?): List<String> =
            csv?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

        fun join(values: Collection<String>): String? =
            values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",").ifEmpty { null }

        private val FLATHUB = Regex("""flathub\.org/(?:[a-z]{2}(?:-[A-Za-z]{2,4})?/)?apps/(?:details/)?([A-Za-z0-9_.-]+\.[A-Za-z0-9_.-]+)""")

        fun flathubIdOf(url: String): String? = FLATHUB.find(url)?.groupValues?.get(1)?.trimEnd('.', '/')

        fun flathubUrl(appId: String): String = "https://flathub.org/apps/$appId"

        private val ALTERNATIVETO = Regex("""alternativeto\.net/software/([a-z0-9][a-z0-9-]*)""", RegexOption.IGNORE_CASE)

        fun alternativetoSlugOf(url: String): String? = ALTERNATIVETO.find(url)?.groupValues?.get(1)?.lowercase()

        fun alternativetoUrl(slug: String): String = "https://alternativeto.net/software/$slug/about/"
    }
}

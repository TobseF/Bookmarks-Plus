import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(22)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":enrich"))
    implementation(project(":sync"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.commonmark)
    implementation(libs.serialization.json)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.autolink)
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    implementation(libs.coroutines.swing)
    implementation(libs.sqldelight.sqlite.driver)
}

tasks.withType<Test>().configureEach {
    systemProperty("bookmarks.sample", rootProject.file("bookmarks_20.11.26.html").absolutePath)
    systemProperty("screenshot.dir", rootProject.layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    systemProperty("net", System.getProperty("net") ?: "false")
    // End-to-end run (E2eSyncRun): pass settings through, use a separate user.home instead of real data
    System.getProperties().stringPropertyNames().filter { it.startsWith("e2e.") || it.startsWith("demo.") }.forEach { systemProperty(it, System.getProperty(it)) }
    System.getProperty("e2e.dir")?.let { systemProperty("user.home", "$it/home") }
    if (System.getProperty("demo.out") != null) systemProperty("user.home", rootProject.layout.buildDirectory.dir("demo-home").get().asFile.absolutePath)
    if (System.getProperty("e2e.step") != null || System.getProperty("demo.out") != null) { outputs.upToDateWhen { false }; testLogging.showStandardStreams = true }
    maxHeapSize = "2g"
}

// ---------------------------------------------------------------------------------------------------------------------
// Playwright ships Node.js with drivers for all systems (driver-bundle ≈ 230 MB). For the package of the respective
// system its own driver is enough – the others are removed during resolution via an artifact transform (≈ −180 MB).

val playwrightStripped: Attribute<Boolean> = Attribute.of("bookmarksplus.playwright.stripped", Boolean::class.javaObjectType)

abstract class StripPlaywrightDrivers : TransformAction<StripPlaywrightDrivers.Params> {
    interface Params : TransformParameters {
        @get:Input val keep: Property<String>
    }

    @get:InputArtifact abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jar = input.get().asFile
        if (!jar.name.startsWith("driver-bundle-")) { outputs.file(jar); return }
        val keep = "driver/${parameters.keep.get()}/"
        val out = outputs.file(jar.nameWithoutExtension + "-" + parameters.keep.get() + ".jar")
        ZipFile(jar).use { zip ->
            ZipOutputStream(out.outputStream()).use { zos ->
                zip.entries().asSequence()
                    .filter { e -> !e.name.startsWith("driver/") || e.name == "driver/" || e.name.startsWith(keep) || e.name.count { it == '/' } == 1 && !e.isDirectory }
                    .forEach { e ->
                        zos.putNextEntry(ZipEntry(e.name).apply { time = e.time })
                        if (!e.isDirectory) zip.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }
    }
}

/** Folder name of the Playwright driver for the system being built on. */
val playwrightPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.startsWith("windows") -> "win32_x64"
        os.contains("mac") -> if (arm) "mac-arm64" else "mac"
        else -> if (arm) "linux-arm64" else "linux"
    }
}

dependencies {
    attributesSchema { attribute(playwrightStripped) }
    artifactTypes.getByName("jar") { attributes.attribute(playwrightStripped, false) }
    registerTransform(StripPlaywrightDrivers::class) {
        from.attribute(playwrightStripped, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(playwrightStripped, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        parameters { keep.set(playwrightPlatform) }
    }
}

configurations.named("runtimeClasspath") { attributes.attribute(playwrightStripped, true) }

/** Version from `-PappVersion=1.2.3` (CI: Git tag), otherwise development state. MSI requires MAJOR.MINOR.PATCH. */
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v")?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) } ?: "0.1.0"

compose.desktop {
    application {
        mainClass = "de.bookmarksplus.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "BookmarksPlus"
            packageVersion = appVersion
            description = "Lokaler Lesezeichen-Manager mit Vorschau, KI und Ende-zu-Ende-verschlüsseltem Sync"
            vendor = "TobseF"
            copyright = "© TobseF"
            rootProject.file("LICENSE").takeIf { it.isFile }?.let { licenseFile.set(it) }
            // determined via suggestRuntimeModules (JDBC, local API, HTTP client, Playwright)
            modules("java.instrument", "java.naming", "java.net.http", "java.sql", "jdk.httpserver", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("icons/app.ico"))
                menuGroup = "Bookmarks Plus"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // keep unchanged so that new versions replace the old one
                upgradeUuid = "6d0f5c7e-3b0a-4b8e-9a51-2f4c8a7e1b42"
            }
            linux {
                iconFile.set(project.file("icons/app.png"))
                packageName = "bookmarks-plus"
                menuGroup = "Network;WebBrowser"
                shortcut = true
                debMaintainer = "TobseF <noreply@github.com>"
            }
            macOS {
                bundleID = "de.bookmarksplus"
            }
        }
    }
}

// Full indexing without UI: gradlew :desktop:index --args="--key-file nano-gpt.txt --enrich --all"
tasks.register<JavaExec>("index") {
    group = "application"
    description = "Reichert alle Lesezeichen an und wertet sie per KI aus (DE/EN)"
    mainClass.set("de.bookmarksplus.desktop.cli.IndexCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

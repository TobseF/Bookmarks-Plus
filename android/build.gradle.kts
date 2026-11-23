import java.io.File
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/** Version from `-PappVersion=1.2.3` (CI: Git tag); versionCode from `-PversionCode` (CI: pipeline number). */
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

/** Release signing from environment variables (CI); if missing, it is signed with the debug key (local). */
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.let(::File)?.takeIf { it.isFile }

android {
    namespace = "de.bookmarksplus.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.bookmarksplus"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // without R8: sqlite-jdbc, BouncyCastle and commons-net use reflection/JNI
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/versions/**/OSGI-INF/**",
                // sqlite-jdbc ships native libraries for all systems – on Android "Linux-Android" is enough
                "org/sqlite/native/Windows/**", "org/sqlite/native/Mac/**", "org/sqlite/native/FreeBSD/**",
                "org/sqlite/native/Linux/**", "org/sqlite/native/Linux-Musl/**",
            )
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// ---------------------------------------------------------------------------------------------------------------------
// sqlite-jdbc delivers its native Android libraries as Java resources (org/sqlite/native/Linux-Android/<arch>).
// AGP does not package .so files from JARs, so provide them as jniLibs per ABI; sqlite-jdbc then loads them
// via System.loadLibrary("sqlitejdbc").

val sqliteNative: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

abstract class ExtractSqliteNative : DefaultTask() {
    @get:InputFiles
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun extract() {
        val abis = mapOf("aarch64" to "arm64-v8a", "arm" to "armeabi-v7a", "x86" to "x86", "x86_64" to "x86_64")
        val out = output.get().asFile
        out.deleteRecursively()
        var n = 0
        jars.files.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().forEach { e ->
                    val m = Regex("org/sqlite/native/Linux-Android/([^/]+)/(lib[^/]+[.]so)").matchEntire(e.name) ?: return@forEach
                    val abi = abis[m.groupValues[1]] ?: return@forEach
                    val target = File(out, "$abi/${m.groupValues[2]}")
                    target.parentFile.mkdirs()
                    zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                    n++
                }
            }
        }
        check(n > 0) { "Keine nativen Android-Bibliotheken in sqlite-jdbc gefunden" }
    }
}

val extractSqliteNative = tasks.register<ExtractSqliteNative>("extractSqliteNative") {
    jars.from(sqliteNative)
    output.set(layout.buildDirectory.dir("generated/sqliteJniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(extractSqliteNative, ExtractSqliteNative::output)
    }
}

dependencies {
    implementation(project(":shared"))
    // Windows DPAPI (JNA) does not exist on Android; Secrets uses AES there with a key file in the app folder
    implementation(project(":sync")) { exclude(group = "net.java.dev.jna") }
    // SQLite via JDBC as on the desktop (incl. FTS5). Newer sqlite-jdbc versions contain no Android libraries
    // anymore, hence pinned here to the last suitable version
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc.android) { version { strictly(libs.versions.sqliteJdbcAndroid.get()) } }
    sqliteNative(libs.sqlite.jdbc.android) { isTransitive = false }
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work)
}

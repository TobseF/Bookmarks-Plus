plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(22)
}

dependencies {
    api(project(":shared"))
    implementation(libs.jsoup)
    implementation(libs.playwright)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.sqldelight.sqlite.driver)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // Network tests only on request: ./gradlew :enrich:test -Dnet=true
    systemProperty("net", System.getProperty("net") ?: "false")
    // Trial run of app data on a DB copy (AppsRealDbRun)
    listOf("appsdb", "keyfile", "n", "readmes").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    if (System.getProperty("appsdb") != null) { outputs.upToDateWhen { false }; testLogging.showStandardStreams = true }
    systemProperty("out.dir", rootProject.layout.buildDirectory.dir("enrich-test").get().asFile.absolutePath)
    System.getenv("GITHUB_TOKEN")?.let { environment("GITHUB_TOKEN", it) }
    maxHeapSize = "1g"
}

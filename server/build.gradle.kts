plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(22)
    // Runs on any server with Java 17+
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("de.bookmarksplus.server.ServerKt")
    applicationName = "bookmarks-plus-server"
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback)
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

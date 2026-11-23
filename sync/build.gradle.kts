plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(22)
    // Bytecode 17: also used by the Android app
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":shared"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.commons.net)
    implementation(libs.bouncycastle)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
    testImplementation(libs.ftpserver)
    testImplementation(project(":server"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.netty)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(22)
    // Bytecode 17: the Android app uses the same JVM classes (D8/ART)
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

sqldelight {
    databases {
        create("BookmarksDb") {
            packageName.set("de.bookmarksplus.db")
        }
    }
}

tasks.withType<Test>().configureEach {
    // Path to the real export file for the roundtrip test
    systemProperty("bookmarks.sample", rootProject.file("bookmarks_20.11.26.html").absolutePath)
    maxHeapSize = "2g"
}

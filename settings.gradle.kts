rootProject.name = "bookmarks-plus"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    // automatically downloads missing JDKs for the toolchain (JDK 22) – important for CI and fresh machines
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":enrich")
include(":desktop")
include(":sync")
include(":server")
include(":android")

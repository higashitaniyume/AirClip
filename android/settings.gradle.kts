pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Shizuku (dev.rikka.shizuku:*) publishes to Maven Central, so no extra repository is needed.
        mavenCentral()
    }
}

rootProject.name = "AirClip"

include(":app")

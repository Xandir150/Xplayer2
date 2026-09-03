pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "XPlayer2"
include(":app")

// Media3 is NOT built from source here. The published androidx.media3:* artifacts come from Google
// Maven; the one module Google never publishes — the FFmpeg audio decoder — is built once from the
// external/media3 checkout with media3's OWN Gradle wrapper/AGP and checked in as an AAR under
// external/prebuilt/ (see BUILDING.md). Media3's build system (its own AGP/Kotlin/Gradle pins) is
// not compatible with being configured inside this build, and it doesn't need to be.

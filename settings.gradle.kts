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
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
    }
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
// Configure minimal local Media3 modules required by decoder_ffmpeg and exoplayer
gradle.extra["androidxMediaSettingsDir"] = file("external/media3").canonicalPath
gradle.extra["androidxMediaModulePrefix"] = "media3-"

val media3Root = file("external/media3")
fun media3(path: String) = File(media3Root, path)

include(":media3-lib-common")
project(":media3-lib-common").projectDir = media3("libraries/common")

include(":media3-lib-container")
project(":media3-lib-container").projectDir = media3("libraries/container")

include(":media3-lib-datasource")
project(":media3-lib-datasource").projectDir = media3("libraries/datasource")

include(":media3-lib-decoder")
project(":media3-lib-decoder").projectDir = media3("libraries/decoder")

include(":media3-lib-extractor")
project(":media3-lib-extractor").projectDir = media3("libraries/extractor")

include(":media3-lib-database")
project(":media3-lib-database").projectDir = media3("libraries/database")

include(":media3-lib-exoplayer")
project(":media3-lib-exoplayer").projectDir = media3("libraries/exoplayer")

include(":media3-lib-decoder-ffmpeg")
project(":media3-lib-decoder-ffmpeg").projectDir = media3("libraries/decoder_ffmpeg")

// Media3 test utility modules required by dependencies in library build.gradle files
include(":media3-test-utils")
project(":media3-test-utils").projectDir = media3("libraries/test_utils")

include(":media3-test-utils-robolectric")
project(":media3-test-utils-robolectric").projectDir = media3("libraries/test_utils_robolectric")

include(":media3-test-data")
project(":media3-test-data").projectDir = media3("libraries/test_data")

// Additional modules required transitively (by transformer and others used in tests)
include(":media3-lib-effect")
project(":media3-lib-effect").projectDir = media3("libraries/effect")

include(":media3-lib-muxer")
project(":media3-lib-muxer").projectDir = media3("libraries/muxer")

include(":media3-lib-exoplayer-dash")
project(":media3-lib-exoplayer-dash").projectDir = media3("libraries/exoplayer_dash")

include(":media3-lib-transformer")
project(":media3-lib-transformer").projectDir = media3("libraries/transformer")
 
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
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Robolectric's android-all-instrumented jars (~170 MB each) come from Google's mirror of
        // Maven Central: Maven Central answers GitHub's runners with 429 Too Many Requests for them
        // (2026-11-24). Only that one module is routed here; everything else stays on mavenCentral().
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2") {
                    name = "MavenCentralGoogleMirror"
                }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        mavenCentral()
    }
}

rootProject.name = "sashimi-android"
include(":app")
include(":core")

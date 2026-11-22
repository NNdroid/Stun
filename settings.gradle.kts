pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Keep plugin resolution on the official repositories. Adding a mirror ahead of
        // mavenCentral() here made KSP's plugin marker unresolvable on GitHub runners.
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's CDN mirror of Maven Central, ahead of the default endpoint:
        // GitHub runner shared IPs get HTTP 429 from repo.maven.apache.org.
        // Kept separate from pluginManagement so plugin markers keep using the
        // official plugin repositories (a mirror there broke KSP resolution).
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Stun"
include(":core")
include(":app")
include(":dbwebui")
include(":tv")
include(":car")
include(":wear")
include(":xr")

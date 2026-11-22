pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Maven Central rate-limits GitHub runner shared IPs with HTTP 429 (seen on
        // org.robolectric:nativeruntime-dist-compat). Try a mirror first, keep the
        // original repository as a fallback for artifacts the mirror has not synced.
        maven("https://maven.aliyun.com/repository/public")
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
        // Same mirror-first order as pluginManagement: Gradle's own dependency
        // resolution is a separate path from Robolectric's runtime downloads.
        maven("https://maven.aliyun.com/repository/public")
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

// Top-level build file where you can add configuration options common to all sub-projects/modules.
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    id("com.android.legacy-kapt") version "9.4.1" apply false
}

// Robolectric downloads android-all-instrumented jars itself with HttpURLConnection at test
// runtime — Gradle's dependency cache, repositories and proxy settings do not apply, and there
// is no retry. On a fresh CI runner those 100-200 MB jars from repo1.maven.org intermittently
// fail with a bare IOException, which kills every Robolectric test class at classMethod.
// Point the test JVM at a Maven Central mirror instead; the jars are byte-identical and
// Robolectric still validates them with SHA-512 before use.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty(
            "robolectric.dependency.repo.url",
            "https://maven.aliyun.com/repository/public",
        )
    }
}
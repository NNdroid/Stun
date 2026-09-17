plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.fjj.stun.dbwebui"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    // ktor server (mirrors :core / StunMcpServer stack) for the standalone admin service.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.gson)

    // Reuse Room's single open connection (WAL-consistent) via SupportSQLiteDatabase /
    // SimpleSQLiteQuery. room-runtime transitively provides androidx.sqlite.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
}

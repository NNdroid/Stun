import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

val baseVersionName = "1.11"
val baseVersionCode = 20012

android {
    namespace = "app.fjj.stun.car"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fjj.stun"
        minSdk = 28
        targetSdk = 37
        versionCode = baseVersionCode
        versionName = baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }

    buildTypes {
        release {
            versionNameSuffix = "-release+$gitHash"
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isProfileable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            versionNameSuffix = "-debug+$gitHash"
            packaging {
                jniLibs {
                    keepDebugSymbols.add("**/*.so")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    
    // Include local AARs from :core as they are compileOnly there
    implementation(fileTree("../core/libs") {
        include("*.aar", "*.jar")
        exclude("*.debug.aar", "*.release.aar", "*.debug-sources.jar", "*.release-sources.jar")
    })
    debugImplementation(fileTree("../core/libs") {
        include("*.debug-sources.jar", "*.debug.aar")
    })
    releaseImplementation(fileTree("../core/libs") {
        include("*.release-sources.jar", "*.release.aar")
    })

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.splashscreen)
    
    // Android Auto / Car App Library
    implementation(libs.androidx.car.app)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.gson)
    implementation(libs.zxing.android.embedded)
    
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.debugoverlay)
    debugImplementation(libs.debugoverlay.okhttp)
    debugImplementation(libs.debugoverlay.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

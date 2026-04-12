plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.fjj.stun"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fjj.stun"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            // Specify only 64-bit ABIs to include in the package
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("jni/Android.mk")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    // IMPORTANT: Point sourceSets to your JNI folder if you want to include extra libs
    // Although externalNativeBuild handles this automatically for built artifacts.
    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.gson)
    implementation(libs.core.ktx)
    implementation(libs.zxing.android.embedded)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt("androidx.room:room-compiler:${libs.versions.roomVersion.get()}")
    // Use annotationProcessor for Java or kapt/ksp for Kotlin
    // Since I don't know if kapt is enabled, I'll check plugins or assume kapt for now.
    // Actually, I should check plugins first.

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

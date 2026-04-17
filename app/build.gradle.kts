import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
}

kotlin {
    jvmToolchain(17)
}

// Automate moving the TProxy executable to assets
val copyTProxyBinaries = tasks.register("copyTProxyBinaries") {
    val buildDirectory = project.layout.buildDirectory
    val projectDirectory = project.layout.projectDirectory

    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val buildDir = buildDirectory.get().asFile
        val cxxDir = File(buildDir, "intermediates/cxx")

        if (!cxxDir.exists()) {
            println("CXX intermediates directory not found: ${cxxDir.path}")
            return@doLast
        }

        abis.forEach { abi ->
            var found = false
            cxxDir.walkBottomUp().forEach { file ->
                if (file.isFile && file.name == "hev-socks5-tproxy" && file.parentFile.name == abi) {
                    val destDir = projectDirectory.dir("src/main/assets/bin/$abi").asFile
                    destDir.mkdirs()
                    file.copyTo(File(destDir, "hev-socks5-tproxy"), overwrite = true)
                    println("Copied $abi binary to assets from: ${file.path}")
                    found = true
                }
            }
            if (!found) {
                println("Could not find hev-socks5-tproxy for ABI: $abi")
            }
        }
    }
}

// Ensure assets are copied before merging
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(copyTProxyBinaries)
    }
    if (name.contains("externalNativeBuild", ignoreCase = true)) {
        finalizedBy(copyTProxyBinaries)
    }
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
            isShrinkResources = true
            isDebuggable = false
            isProfileable = false
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
    implementation(libs.tink.android)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation("io.github.rosaleskevin:katch:1.0.0")
    kapt("androidx.room:room-compiler:${libs.versions.roomVersion.get()}")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

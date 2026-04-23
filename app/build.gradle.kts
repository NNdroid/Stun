import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

fun fetchGitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD").start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) {
        "unknown"
    }
}

val gitHash = fetchGitHash()
val baseVersionName = "1.5"

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

// ========================================================
// Task to automatically patch JNI submodules (Config Cache Safe)
// ========================================================
val applyJniPatches = tasks.register("applyJniPatches") {
    val jniDirectory = project.layout.projectDirectory.dir("jni")

    doLast {
        val jniDir = jniDirectory.asFile
        val patchesDir = File(jniDir, "patches")

        if (!patchesDir.exists()) {
            return@doLast
        }

        println("=== Starting JNI Submodule Patching ===")

        patchesDir.listFiles { _, name -> name.endsWith(".patch") }?.forEach { patchFile ->
            val submoduleName = patchFile.name.replace(".patch", "")
            val submoduleDir = File(jniDir, submoduleName)

            if (submoduleDir.exists()) {
                println("📦 Processing: $submoduleName")
                try {
                    val process = ProcessBuilder("git", "apply", "--ignore-whitespace", "--reject", patchFile.absolutePath)
                        .directory(submoduleDir)
                        .start()

                    process.waitFor()

                    println("✅ $submoduleName patch applied or already present")
                } catch (e: Exception) {
                    println("⚠️ Skipping $submoduleName: ${e.message}")
                }
            } else {
                println("❌ Submodule directory not found: ${submoduleDir.absolutePath}")
            }
        }
        println("=== JNI Patching Complete ===")
    }
}

android {
    namespace = "app.fjj.stun"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fjj.stun"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
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

    externalNativeBuild {
        ndkBuild {
            path = file("jni/Android.mk")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

kotlin {
    jvmToolchain(17)
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

// Ensure patches are applied before any build starts
tasks.named("preBuild") {
    dependsOn(applyJniPatches)
}

dependencies {
    // 通用依赖：加载所有既不含 .debug 也不含 .release 的 aar/jar
    implementation(fileTree("libs") {
        include("*.aar", "*.jar")
        exclude("*.debug.aar", "*.release.aar", "*.debug-sources.jar", "*.release-sources.jar")
    })
    // Debug 特有依赖
    debugImplementation(fileTree("libs") {
        include("*.debug-sources.jar", "*.debug.aar")
    })
    // Release 特有依赖
    releaseImplementation(fileTree("libs") {
        include("*.release-sources.jar", "*.release.aar")
    })

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)

    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.gson)
    implementation(libs.zxing.android.embedded)
    implementation(libs.tink.android)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

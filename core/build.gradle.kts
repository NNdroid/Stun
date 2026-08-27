import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

// ========================================================
// Task to automatically patch JNI submodules (Config Cache Safe)
// ========================================================
val applyJniPatches = tasks.register("applyJniPatches") {
    description = ""
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

// Automate moving the TProxy executable to assets (Now in :core)
val copyTProxyBinaries = tasks.register("copyTProxyBinaries") {
    description = "Copies hev-socks5-tproxy from core build intermediates to assets"
    val projectDirectory = project.layout.projectDirectory
    val buildDirectory = project.layout.buildDirectory

    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val coreBuildDir = buildDirectory.dir("intermediates/cxx").get().asFile

        if (!coreBuildDir.exists()) {
            println("CXX intermediates directory not found: ${coreBuildDir.path}")
            return@doLast
        }

        abis.forEach { abi ->
            var found = false
            coreBuildDir.walkBottomUp().forEach { file ->
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

// Ensure rules are downloaded before build (Now in :core)
val downloadRulesDat = tasks.register("downloadRulesDat") {
    description = "Downloads geoip.dat and geosite.dat"
    val outputDir = project.layout.projectDirectory.dir("src/main/assets/rules-dat").asFile
    val filesToDownload = mapOf(
        "geoip.dat" to "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat",
        "geosite.dat" to "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat"
    )

    doLast {
        if (!outputDir.exists()) outputDir.mkdirs()
        filesToDownload.forEach { (name, url) ->
            val outputFile = File(outputDir, name)
            if (!outputFile.exists()) {
                println("Downloading $name from $url...")
                try {
                    URI(url).toURL().openStream().use { input ->
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    println("Successfully downloaded $name")
                } catch (e: Exception) {
                    println("Failed to download $name: ${e.message}")
                }
            } else {
                println("$name already exists, skipping download.")
            }
        }
    }
}

android {
    namespace = "app.fjj.stun.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        aidl = true
        viewBinding = true
        dataBinding = true
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

tasks.named("preBuild") {
    dependsOn(applyJniPatches)
    dependsOn(downloadRulesDat)
}

// Ensure assets are copied before merging
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(copyTProxyBinaries)
    }
}

dependencies {
    // For local AARs in library module, use compileOnly
    // The consumer app MUST also include these AARs
    compileOnly(fileTree("libs") {
        include("*.aar", "*.jar")
        exclude("*.debug-sources.jar", "*.release-sources.jar")
    })

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.gson)
    implementation(libs.tink.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.zxing.android.embedded)

    // Ktor for remote control
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
}

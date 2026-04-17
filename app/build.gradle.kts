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

// ========================================================
// Task to automatically patch JNI submodules (Config Cache Safe)
// ========================================================
val applyJniPatches = tasks.register("applyJniPatches") {
    // 在配置阶段（doLast 外部）提前获取并锁定目录对象
    // 使用 project.layout API 是 Configuration Cache 推荐的最佳实践
    val jniDirectory = project.layout.projectDirectory.dir("jni")

    doLast {
        // 在执行阶段（doLast 内部）完全脱离 project 对象
        // 只操作前面捕获的纯净 File 对象
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
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fjj.stun"
        minSdk = 28
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            // Specify ABIs to include in the package
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
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

// Ensure patches are applied before any build starts
tasks.named("preBuild") {
    dependsOn(applyJniPatches)
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

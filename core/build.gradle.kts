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
    description = "Applies checked JNI submodule patches and fails on patch drift"
    val jniDirectory = project.layout.projectDirectory.dir("jni")

    doLast {
        val jniDir = jniDirectory.asFile
        val patchesDir = File(jniDir, "patches")

        if (!patchesDir.exists()) {
            return@doLast
        }

        fun gitApply(submoduleDir: File, vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder("git", "apply", *args)
                .directory(submoduleDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output
        }

        println("=== Starting JNI Submodule Patching ===")

        patchesDir.listFiles { _, name -> name.endsWith(".patch") }
            ?.sortedBy { it.name }
            ?.forEach { patchFile ->
                val submoduleName = patchFile.name.removeSuffix(".patch")
                val submoduleDir = File(jniDir, submoduleName)

                if (!submoduleDir.isDirectory) {
                    throw GradleException("JNI submodule directory not found: ${submoduleDir.absolutePath}")
                }

                println("📦 Processing: $submoduleName")
                val patchPath = patchFile.absolutePath
                val (checkCode, checkOutput) = gitApply(
                    submoduleDir,
                    "--ignore-whitespace",
                    "--check",
                    patchPath,
                )

                if (checkCode == 0) {
                    val (applyCode, applyOutput) = gitApply(
                        submoduleDir,
                        "--ignore-whitespace",
                        patchPath,
                    )
                    if (applyCode != 0) {
                        throw GradleException(
                            "Failed to apply JNI patch for $submoduleName:\n$applyOutput"
                        )
                    }
                    println("✅ $submoduleName patch applied")
                    return@forEach
                }

                // An incremental Gradle invocation may see the patch already applied in
                // the working tree. Reverse-check is the reliable/idempotent test for it.
                val (reverseCode, reverseOutput) = gitApply(
                    submoduleDir,
                    "--ignore-whitespace",
                    "--reverse",
                    "--check",
                    patchPath,
                )
                if (reverseCode == 0) {
                    println("✅ $submoduleName patch already applied")
                    return@forEach
                }

                throw GradleException(
                    buildString {
                        appendLine("JNI patch drift detected for $submoduleName.")
                        appendLine("Forward check:")
                        appendLine(checkOutput.ifBlank { "(no output)" })
                        appendLine("Reverse check:")
                        append(reverseOutput.ifBlank { "(no output)" })
                    }
                )
            }
        println("=== JNI Submodule Patching Complete ===")
    }
}

// 本仓自编译、需要在设备上执行的原生可执行文件。
//  - hev-socks5-tproxy：TPROXY 转发核心（submodule，NDK 构建）
//  - sockmark：root 侧 SO_MARK 代理（core/jni/sockmark，NDK 构建）。
//    App 进程没有 CAP_NET_ADMIN，setsockopt(SO_MARK) 会 EPERM，隧道 socket 打不了 mark，
//    只能由这个 root 二进制代设。缺了它 tproxy 模式下 myssh 的 SSH socket 会被 TPROXY
//    抓回本地 socks5 形成死循环。
// 两者都走 NDK 产物 → assets/bin/<abi> → AppBootstrap 部署到 cacheDir 这同一条链。
//
// ⚠️ 列表必须声明在 doLast **内部**（或在任务注册时 val 进局部变量再传进去）——
// 顶层 script 属性被 doLast 闭包捕获时，配置缓存会拒绝反序列化
// （"cannot deserialize Gradle script object references"），任务直接失败。
private val nativeExecutables = listOf("hev-socks5-tproxy", "sockmark")

// Automate moving the native executables to assets (Now in :core)
val copyTProxyBinaries = tasks.register("copyTProxyBinaries") {
    description = "Copies hev-socks5-tproxy and sockmark from core build intermediates to assets"
    val projectDirectory = project.layout.projectDirectory
    val buildDirectory = project.layout.buildDirectory
    val executables = nativeExecutables

    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val coreBuildDir = buildDirectory.dir("intermediates/cxx").get().asFile

        if (!coreBuildDir.exists()) {
            println("CXX intermediates directory not found: ${coreBuildDir.path}")
            return@doLast
        }

        abis.forEach { abi ->
            executables.forEach { exeName ->
                var found = false
                coreBuildDir.walkBottomUp().forEach { file ->
                    if (file.isFile && file.name == exeName && file.parentFile.name == abi) {
                        val destDir = projectDirectory.dir("src/main/assets/bin/$abi").asFile
                        destDir.mkdirs()
                        file.copyTo(File(destDir, exeName), overwrite = true)
                        println("Copied $abi/$exeName to assets from: ${file.path}")
                        found = true
                    }
                }
                if (!found) {
                    println("Could not find $exeName for ABI: $abi")
                }
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

    testOptions {
        // ExitIpProbe 等单测走 org.json / SystemClock，需要 Robolectric 载入真实框架实现
        unitTests.isIncludeAndroidResources = true
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

// Room schema 导出：每次版本变更时 KSP 会把实体 schema 快照写入 core/schemas/<版本>.json，
// 供 AutoMigration 差分与 MigrationTestHelper 测试使用。历史版本 JSON 一经生成必须提交入库。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    // myssh AAR classes for compilation; the app module provides the actual
    // AAR with native .so at runtime. Use extracted classes.jar to avoid
    // AGP's "local .aar dependency not supported in library module" error.
    compileOnly(files("libs/myssh-classes.jar"))

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    api(libs.androidx.splashscreen)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.tink.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.zxing.android.embedded)
    // 压住 core 版本，理由见 gradle/libs.versions.toml 的 zxing-core
    implementation(libs.zxing.core)

    // Ktor for remote control
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.network.tls.certificates)

    // Debug 构建的网络追踪：WebDavClient 在 src/debug 源集注册
    // DebugOverlayNetworkInterceptor（src/release 为空实现，零开销）。
    debugImplementation(libs.debugoverlay)
    debugImplementation(libs.debugoverlay.okhttp)

    // 纯 JVM 单测（备份编解码器不依赖 Android，因此无需 Robolectric）
    testImplementation(libs.junit)
    // ExitIpProbe 依赖 org.json / SystemClock，必须跑在真实框架实现上
    testImplementation(libs.robolectric)
}

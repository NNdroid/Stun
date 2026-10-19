import java.io.File
import java.net.URI
import java.security.MessageDigest

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
    // Captured during configuration on purpose: touching Project (e.g. `projectDir`)
    // inside doLast is exactly what breaks configuration-cache reuse. `displayPath`
    // only ever formats a path for an error message, so a Directory handle is enough.
    val projectDirectory = project.layout.projectDirectory

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

        /** Arbitrary git invocation — must not go through [gitApply], which hardcodes `apply`. */
        fun git(submoduleDir: File, vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder("git", *args)
                .directory(submoduleDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output
        }

        fun gitCommit(submoduleDir: File): String =
            git(submoduleDir, "rev-parse", "--short", "HEAD").second.trim()
                .ifEmpty { "(unknown)" }

        /**
         * Porcelain status of the submodule, excluding nested-submodule noise.
         *
         * Uses `-z` so paths containing spaces stay unambiguous, and splits on NUL
         * rather than newline. Nested submodules (everything under `third-part/`, plus
         * `src/core`) commonly show as modified purely from a commit-pointer difference
         * that predates this task; they are not produced by any patch, so listing them
         * would bury the actual culprits.
         *
         * ⚠️ **Never write a glob containing a slash-star in a comment.** The two
         * characters open a *nested* block comment inside this KDoc and swallow
         * everything up to the next closing marker, silently deleting the following
         * declarations. Gradle then reports a bare `Expecting '}'` on an unrelated
         * line — hours of misdirected debugging. Write it as "everything under
         * `third-part`" instead.
         */
        fun statusExcludingNestedSubmodules(submoduleDir: File): List<String> {
            val (_, raw) = git(submoduleDir, "status", "--porcelain", "-z")
            return raw.split('\u0000')
                .filter { it.isNotBlank() }
                .map { entry ->
                    // Porcelain v1: "<2-char status><space><path>"
                    val path = if (entry.length > 3) entry.substring(3) else entry
                    path to entry
                }
                .filterNot { (path, _) ->
                    path.startsWith("third-part/") || path.startsWith("src/core")
                }
                .map { (_, entry) -> entry }
        }

        /**
         * Path to paste into a shell command in an error message.
         *
         * Prefers a repo-relative path, but must not throw when the submodule sits
         * outside the project directory — an exception here would replace a helpful
         * drift message with a confusing "Failed to determine relative path".
         */
        fun displayPath(target: File): String =
            runCatching { target.relativeTo(projectDirectory.asFile).path }
                .getOrElse { target.absolutePath }

        println("=== Starting JNI Submodule Patching ===")

        /**
         * 把 patch 文件名映射成它所针对的 submodule 目录。
         *
         * 命名规则：文件名去掉 `.patch` 后，`--` 之前是顶层 submodule 名
         * （下划线转斜杠），`--` 之后是该 submodule 内部的相对路径
         * （同样下划线转斜杠）。例如
         *   `hev-socks5-tproxy.patch`             -> `hev-socks5-tproxy`
         *   `hev-socks5-tproxy--src-core.patch`   -> `hev-socks5-tproxy/src/core`
         *
         * 为什么要支持第二层：`hev-socks5-client.c` / `hev-socks5-misc.c` 这些
         * **连接与解析的核心逻辑住在 `src/core`**（它本身是个嵌套 submodule，
         * 父仓库里是模式 160000 的 gitlink）。改它们（例如 connect 失败后按
         * 另一个 IP 版本重试）无法靠改顶层文件绕过，只能直接改 core。
         *
         * 用 `--` 分隔、嵌套段内用 `-` 表示斜杠：顶层名里本来就有大量 `-`
         * （`hev-socks5-tproxy`），不能再让它承担路径语义，所以嵌套段的
         * 分隔符必须与顶层区分开。`a--b-c` 表达「顶层 a 下的 b/c」，
         * 不会出现歧义。
         */
        fun submoduleDirFor(patchName: String): File {
            val stem = patchName.removeSuffix(".patch")
            val topLevel = stem.substringBefore("--").replace('_', '/')
            val nested = stem.substringAfter("--", "")
            return if (nested.isEmpty()) {
                File(jniDir, topLevel)
            } else {
                File(jniDir, "$topLevel/${nested.replace('-', '/')}")
            }
        }

        patchesDir.listFiles { _, name -> name.endsWith(".patch") }
            ?.sortedBy { it.name }
            ?.forEach { patchFile ->
                val submoduleName = patchFile.name.removeSuffix(".patch")
                val submoduleDir = submoduleDirFor(patchFile.name)

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

                // Both directions failed. There are two very different causes and the
                // original message conflated them into one scary "drift" blob:
                //
                //  (a) The working tree is a *stale materialisation* of an OLDER patch —
                //      e.g. patches/*.patch was updated while the submodule still shows the
                //      previous revision's edits. Fix is purely mechanical (reset the
                //      submodule, let this task re-materialise from the patch).
                //  (b) The patch genuinely no longer matches the pinned submodule commit —
                //      upstream moved and the hunks need regenerating. No build-only fix.
                //
                // Distinguish them by asking whether the *tracked* files are clean. If they
                // are, the submodule is exactly at the pinned commit, so (b) is impossible
                // and the tree can only be stale materialisation — say so, and give the
                // exact command instead of leaving the user to guess.
                val dirty = statusExcludingNestedSubmodules(submoduleDir)

                if (dirty.isEmpty()) {
                    val sub = displayPath(submoduleDir)
                    throw GradleException(
                        buildString {
                            appendLine(
                                "JNI patch for $submoduleName does not match the pinned " +
                                    "submodule commit, and the working tree is clean " +
                                    "(no stale edits to reset)."
                            )
                            appendLine("Submodule commit: ${gitCommit(submoduleDir)}")
                            appendLine("This is real drift — upstream moved and the hunks need regenerating.")
                            appendLine("Reset to the pinned commit before re-deriving the patch:")
                            appendLine("  git -C $sub reset --hard && git -C $sub clean -fd")
                            appendLine("Forward check:")
                            appendLine(checkOutput.ifBlank { "(no output)" })
                            appendLine("Reverse check:")
                            append(reverseOutput.ifBlank { "(no output)" })
                        }
                    )
                }

                val sub = displayPath(submoduleDir)
                throw GradleException(
                    buildString {
                        appendLine(
                            "JNI working tree for $submoduleName is a STALE materialisation " +
                                "of an older patch (patches/${patchFile.name} has since been updated)."
                        )
                        appendLine("Modified/untracked files that no current patch produces:")
                        dirty.take(12).forEach { appendLine("  $it") }
                        if (dirty.size > 12) appendLine("  ... and ${dirty.size - 12} more")
                        appendLine()
                        appendLine("This is expected after editing a patch by hand. Reset and rebuild:")
                        appendLine("  git -C $sub reset --hard && git -C $sub clean -fd")
                        appendLine("The submodule pointer must stay untouched — edits live only in the patch.")
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
    description = "Downloads geoip.dat and geosite.dat, keeping whatever copy is already on disk when the download fails"
    val outputDir = project.layout.projectDirectory.dir("src/main/assets/rules-dat").asFile
    val filesToDownload = mapOf(
        "geoip.dat" to "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat",
        "geosite.dat" to "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat"
    )
    // 离线/CI 的逃生口：-PrefreshRulesDat=false。在配置期就读出来 —— doLast 里直接引用
    // `providers` 会解析到 Project 的隐式接收者，执行期那是 null，直接炸
    // "Cannot read field \"$$implicitReceiver_Project\""。
    val refreshRulesDat = project.providers.gradleProperty("refreshRulesDat").getOrElse("true").toBoolean()

    /** 取 url 对应 .sha256sum 里公布的摘要，取不到返回空串（不视为失败）。 */
    val sidecarSha256: (String) -> String = { url ->
        try {
            val connection = URI("$url.sha256sum").toURL().openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            text.lineSequence().map { it.trim().split(Regex("\\s+")).firstOrNull() ?: "" }
                .firstOrNull { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
                ?.lowercase()
                ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 小写十六进制 SHA-256。 */
    val sha256Of: (File) -> String = { file ->
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 下载 url 到 [tempFile] 并做两层校验，返回写入字节数；任何不通过都删掉 [tempFile] 并抛。
     *
     * 校验只作用在临时文件上：最终名在 replace() 成功前一直是唯一可用副本，失败路径碰不到它。
     */
    val fetchVerified: (String, String, File) -> Long = { url, expected, tempFile ->
        try {
            val connection = URI(url).toURL().openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.useCaches = false
            val declared = connection.contentLengthLong
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            val actual = tempFile.length()
            // 检查一：服务端声明了 Content-Length 就必须在字节数上对得上。jsDelivr 这类
            // CDN 会正常返回 200 然后中途停发，copyTo 照常收尾时看不出来。
            if (declared > 0L && actual != declared) {
                throw Exception("received $actual of $declared bytes from $url (truncated)")
            }
            // 检查二：与随文件一起发布的 .sha256sum 比对。长度对得上也可能内容整份错了。
            if (expected.isNotBlank()) {
                val actualSha = sha256Of(tempFile)
                if (!actualSha.equals(expected, ignoreCase = true)) {
                    throw Exception("sha256 mismatch for $url: got $actualSha, want $expected")
                }
            }
            actual
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /** 用 [source] 替换 [dest]。Windows 的 rename 不会覆盖已存在的目标，得先删再改一次。 */
    val replace: (File, File) -> Boolean = { source, dest ->
        source.renameTo(dest) || (dest.exists() && dest.delete() && source.renameTo(dest))
    }

    doLast {
        // 离线或 CI 不该为这两份文件付 27 MB 传输 + 15 秒超时。
        if (!refreshRulesDat) {
            println("downloadRulesDat skipped by -PrefreshRulesDat=false")
            return@doLast
        }

        if (!outputDir.exists()) outputDir.mkdirs()
        filesToDownload.forEach { (name, url) ->
            val outputFile = File(outputDir, name)
            val tempFile = File(outputDir, "$name.tmp")

            // 每次都花一次 ~90 字节的摘要请求问 CDN「有没有新版本」，只有摘要变了才真的下
            // 16 MB 载荷。这比"文件存在就永远跳过"新鲜——后者会让工作区那份一直停在旧版，
            // 直到有人手动删文件；也比"每次构建都全量下载"便宜。摘要相等就说明本地那份
            // 就是发布源上的最新版，没必要再下一次。
            val published = sidecarSha256(url)
            if (published.isBlank()) {
                if (outputFile.exists() && outputFile.length() > 0) {
                    println("$name: sha256 sidecar 取不到，沿用本地版本（${outputFile.length()} bytes）")
                    return@forEach
                }
                // 本地没有、摘要也取不到：继续构建等于把缺这条 asset 的 APK 静默发出去，
                // Go 侧所有直连分流会失效而且不留任何提示。这种情况必须炸出来。
                throw GradleException(
                    "$name is missing and its sha256 sidecar is unreachable: ${url}.sha256sum"
                )
            }

            if (outputFile.exists() && outputFile.length() > 0 && sha256Of(outputFile) == published) {
                println("$name: 已是最新版本（${outputFile.length()} bytes）")
                return@forEach
            }

            var lastError: Exception? = null
            try {
                // 最多两次。唯一值得重试的情况是 CDN 正在滚动发布：sidecar 与载荷是两个独立
                // 对象，跨发布时刻的请求会合法地配上「新摘要 + 旧字节」。重取两边一次就跨
                // 过去了；其它失败（断流、截断、404）重试只会得到同一个答案。
                for (attempt in 1..2) {
                    val expected = if (attempt == 1) published else sidecarSha256(url)
                    if (expected.isBlank()) {
                        lastError = Exception("sha256 sidecar unreachable on retry")
                        break
                    }
                    try {
                        val written = fetchVerified(url, expected, tempFile)
                        if (!replace(tempFile, outputFile)) {
                            throw Exception("failed to replace $outputFile from ${tempFile.absolutePath}")
                        }
                        println("Successfully downloaded $name (${written} bytes)")
                        lastError = null
                        break
                    } catch (e: Exception) {
                        lastError = e
                        if (attempt < 2) {
                            println("  ${e.message}; re-fetching digest and payload in case the CDN is mid-rollover")
                        }
                    }
                }
            } finally {
                // 校验失败时 fetchVerified 已经删过一次；rename 成功之后它已经不存在，
                // 这里 delete() 返回 false 是正常收尾，两次都不会留下半截 .tmp。
                tempFile.delete()
            }

            if (lastError != null) {
                if (outputFile.exists() && outputFile.length() > 0) {
                    // 最新版拉不到，但本地还有一份能用的：沿用旧的，不阻塞构建。
                    println("Failed to refresh $name: ${lastError.message}")
                    println("  Keeping the existing local copy (${outputFile.length()} bytes).")
                } else {
                    throw GradleException(
                        "$name is missing and could not be downloaded: ${lastError.message}", lastError
                    )
                }
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
    // 局域网同步客户端用 OkHttp 引擎：只有它能注入 socketFactory（见 RemoteSyncManager
    // 的 Wi-Fi 网络绑定），CIO 引擎不支持按网络建连。
    implementation(libs.ktor.client.okhttp)
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

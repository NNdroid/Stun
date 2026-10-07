import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

val baseVersionName = "1.12"
val baseVersionCode = 13

// 万位段：五个模块共用 applicationId（app.fjj.stun），Play 要求同包内 versionCode 唯一，
// 所以每个模块独占一个 10000 段。段与基数从上面的 baseVersionCode 拆出 —— 改版本只改那一行。
val versionSegment = baseVersionCode / 10_000 * 10_000
val versionCodeBase = baseVersionCode - versionSegment

// CI_RUN_NUMBER 为正整数 = CI 自动版本模式（release.yml 注入 github.run_number）。
// 本地不设该变量：versionCode / versionName / versionNameSuffix 与手工值逐字节一致，
// 也不额外调用 git。versionName 可被 CI_VERSION_NAME（release.yml 的「版本号」输入）覆盖。
val ciRunNumber = providers.environmentVariable("CI_RUN_NUMBER")
    .map { it.trim().toIntOrNull() ?: 0 }
    .getOrElse(0)
val ciBuild = ciRunNumber > 0

// 只数 v* release 标签：未来若加 CI 标签，不应把 versionCode 顶高。
// 已知边界：删 tag 会让计数回落、versionCode 变小，Play 会拒收 —— tag 计数方案的固有性质。
val autoVersionCode = if (ciBuild) {
    val releaseTagCount = providers.exec {
        commandLine("git", "tag", "--list", "v*")
        isIgnoreExitValue = true
    }.standardOutput.asText.getOrElse("").trim().lineSequence().count { it.isNotEmpty() }
    versionSegment + versionCodeBase + (releaseTagCount + 1)
} else baseVersionCode

// 自定义版本号：release.yml 的「版本号」输入经 CI_VERSION_NAME 注入，原样写入 versionName；
// 空串 = 用下面的自动值。只在 CI 模式下读取 —— 本地不设该变量，这个覆盖永远不可能
// 影响本地构建。注意 versionCode 不受它影响，仍按 v* tag 数递增
// （Play 的单调性只靠那一条保证，手填版本号不会破坏它）。
val ciVersionNameOverride = if (ciBuild) {
    providers.environmentVariable("CI_VERSION_NAME").map { it.trim() }.getOrElse("")
} else ""

// v1.0.<UTC 日期>.<提交总数>-<提交短哈希>，与 myssh 库的 v1.0.YYYYMMDD-<hash> 约定对齐；
// 日期取 UTC，与 release.yml 里 myssh 的 date -u +%Y%m%d 一致。
val autoVersionName = if (ciBuild) {
    ciVersionNameOverride.ifEmpty {
        val commitCount = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.getOrElse("0").trim()
        val buildDate = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
        "v1.0.$buildDate.$commitCount-$gitHash"
    }
} else baseVersionName

if (ciBuild) {
    println(":${project.name} versionName=$autoVersionName versionCode=$autoVersionCode (CI run #$ciRunNumber)")
}

// CI 签名密钥：release.yml 经 -Pandroid.injected.signing.* 注入。这些参数本身不会被 AGP 读取，
// 必须由下面的 signingConfigs 块接上；本地不传 → null → 整段空操作，
// debug 仍用调试密钥、release 仍产 unsigned 包，本地构建完全不受影响。
val ciSignStoreFile = providers.gradleProperty("android.injected.signing.store.file").orNull

android {
    signingConfigs {
        if (ciSignStoreFile != null) {
            create("ciRelease") {
                storeFile = file(ciSignStoreFile)
                storePassword = providers.gradleProperty("android.injected.signing.store.password").orNull
                keyAlias = providers.gradleProperty("android.injected.signing.key.alias").orNull
                keyPassword = providers.gradleProperty("android.injected.signing.key.password").orNull
            }
        }
    }
    namespace = "app.fjj.stun"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fjj.stun"
        minSdk = 28
        targetSdk = 37
        versionCode = autoVersionCode
        versionName = autoVersionName

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
            versionNameSuffix = if (ciBuild) "" else "-release+$gitHash"
            signingConfig = ciSignStoreFile?.let { signingConfigs.getByName("ciRelease") }
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
            versionNameSuffix = if (ciBuild) "" else "-debug+$gitHash"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation(project(":dbwebui"))

    // 清单里声明的 rikka.shizuku.ShizukuProvider 类来自这个包。core 以 implementation
    // 引入不对外可见 → app 必须自己依赖，否则 lint MissingClass（Fatal 级误报之外的真缺类）。
    implementation(libs.rikka.shizuku.provider)

    // myssh classes.jar for compilation; slim AAR (empty classes.jar, native .so only)
    // for runtime. The extracted classes.jar avoids AGP's duplicate-class error.
    implementation(files("../core/libs/myssh-classes.jar"))
    debugImplementation(files("../core/libs/myssh.debug-slim.aar"))
    releaseImplementation(files("../core/libs/myssh.release-slim.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.gson)
    implementation(libs.zxing.android.embedded)
    // 压住 core 版本，理由见 gradle/libs.versions.toml 的 zxing-core
    implementation(libs.zxing.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.debugoverlay)
    debugImplementation(libs.debugoverlay.okhttp)
    debugImplementation(libs.debugoverlay.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

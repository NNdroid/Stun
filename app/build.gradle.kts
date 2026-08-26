import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

val baseVersionName = "1.10"
val baseVersionCode = 11

// Automate moving the TProxy executable to assets
val copyTProxyBinaries = tasks.register("copyTProxyBinaries") {
    description = "Copies hev-socks5-tproxy from core build intermediates to app assets"
    val appProjDir = project.projectDir
    val rootBaseDir = project.rootDir

    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        // Note: CXX intermediates now come from :core
        val coreBuildDir = File(rootBaseDir, "core/build/intermediates/cxx")
        // ... (rest same)

        if (!coreBuildDir.exists()) {
            println("CXX intermediates directory not found: ${coreBuildDir.path}")
            return@doLast
        }

        abis.forEach { abi ->
            var found = false
            coreBuildDir.walkBottomUp().forEach { file ->
                if (file.isFile && file.name == "hev-socks5-tproxy" && file.parentFile.name == abi) {
                    val destDir = File(appProjDir, "src/main/assets/bin/$abi")
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

android {
    namespace = "app.fjj.stun"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fjj.stun"
        minSdk = 28
        targetSdk = 35
        versionCode = baseVersionCode
        versionName = baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// Ensure assets are copied before merging
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(copyTProxyBinaries)
    }
}

// Ensure rules are downloaded before build
val downloadRulesDat = tasks.register("downloadRulesDat") {
    description = ""
    val outputDir = file("src/main/assets/rules-dat")
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

tasks.named("preBuild") {
    dependsOn(downloadRulesDat)
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

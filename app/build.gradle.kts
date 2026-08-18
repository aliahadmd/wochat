plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // The runtime library was already a dependency, but without this plugin
    // @Serializable generates nothing and serializer lookup fails at run time --
    // in release only, since the failure is a missing generated class rather than
    // anything a unit test exercises.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.baselineprofile)
}

val releaseKeystore = rootProject.file("release-signing/AIchat-release.jks")
val releaseKeyAlias = "aichat-release"
val releaseKeychainService = "com.aliahad.aichat.release-signing"
val isMacOs = providers.systemProperty("os.name")
    .map { it.startsWith("Mac", ignoreCase = true) }
    .getOrElse(false)

val keychainReleasePassword = if (isMacOs) {
    providers.exec {
        isIgnoreExitValue = true
        commandLine(
            "security",
            "find-generic-password",
            "-w",
            "-a",
            releaseKeyAlias,
            "-s",
            releaseKeychainService,
        )
    }.standardOutput.asText.map { it.trim() }
} else {
    providers.provider { "" }
}

val releaseStorePasswordProvider = providers.environmentVariable("AICHAT_RELEASE_STORE_PASSWORD")
    .orElse(keychainReleasePassword)
val releaseStorePassword = releaseStorePasswordProvider.orNull?.takeIf(String::isNotBlank)
val releaseKeyPassword = providers.environmentVariable("AICHAT_RELEASE_KEY_PASSWORD")
    .orElse(releaseStorePasswordProvider)
    .orNull
    ?.takeIf(String::isNotBlank)

android {
    namespace = "com.aliahad.aichat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.aliahad.aichat"
        minSdk = 33
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.0"
        buildConfigField(
            "String",
            "LLAMA_RUNTIME_REVISION",
            "\"ad857250ff2f75bcb7ea94d17c67a609c2ec103b-aichat-context-v1\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_KLEIDIAI=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=ON",
                    "-DGGML_VULKAN=ON"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = releaseStorePassword.orEmpty()
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword.orEmpty()
        }
    }

    // Unmocked android.util.* calls throw by default, which makes a catch handler

    // that logs able to defeat itself: RecoveringInferenceEngine.persistSession

    // swallows save failures deliberately, and its Log.w turned that swallow back

    // into a thrown RuntimeException under test while behaving correctly on the

    // device. Returning defaults lets unit tests exercise code that logs, which is

    // most of the error paths worth testing.

    testOptions {

        unitTests.isReturnDefaultValues = true

    }


    buildTypes {
        debug {
            isMinifyEnabled = false
            // Lets the debug build sit alongside the signed release build.
            // Without this, running instrumented tests means uninstalling the
            // real app — which deletes noBackupFilesDir/models and forces a
            // 4.8 GB model re-download. That happened twice before this existed.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    lint {
        abortOnError = false
        warningsAsErrors = false
        checkDependencies = true
        xmlReport = true
        htmlReport = true
    }
}

detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    ignoreFailures = false
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(files("libs/sqlcipher-android-4.16.0.aar"))
    // sherpa-onnx: VAD + streaming ASR + Piper TTS for offline voice call mode (plan 036).
    // One runtime for all three, so this adds ONNX Runtime once rather than three engines.
    //
    // Vendored rather than resolved: k2-fsa publishes no official artifact on Maven
    // Central (only third-party repackagings, which are not trustworthy for an
    // offline app). This is the unmodified official release asset, kept whole so its
    // provenance stays checkable:
    //   https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar
    //   sha256 6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b
    //   Apache-2.0. Ships four ABIs; `abiFilters` keeps only arm64-v8a in the APK.
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.datastore.preferences)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    "baselineProfile"(project(":baselineprofile"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

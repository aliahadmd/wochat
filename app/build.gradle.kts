plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 6
        versionName = "1.1.0"
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
    implementation(libs.androidx.health.connect.client)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(files("libs/sqlcipher-android-4.16.0.aar"))
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

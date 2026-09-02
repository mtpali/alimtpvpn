plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    // A final distribution build can request one installable APK containing only the
    // requested ARM ABIs, instead of producing a separate APK for every architecture.
    val fatApkAbiList = (properties["FAT_APK_ABIS"] as? String)
        ?.split(';')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }

    // Hardened release is opt-in so normal development/diagnostic builds remain easy to
    // inspect. The final distribution workflow enables this explicitly after injecting the
    // signing-certificate fingerprint that the runtime integrity guard must verify.
    val hardenedReleaseBuild = (properties["FINAL_HARDENED_BUILD"] as? String)
        ?.equals("true", ignoreCase = true) == true
    val expectedReleaseCertSha256 = (properties["MOBILETINA_EXPECTED_CERT_SHA256"] as? String)
        ?.trim()
        ?.lowercase()
        .orEmpty()
    val hardenedKeystorePath = (properties["FINAL_HARDENED_KEYSTORE"] as? String).orEmpty()
    val hardenedStorePassword = (properties["FINAL_HARDENED_STORE_PASSWORD"] as? String).orEmpty()
    val hardenedKeyAlias = (properties["FINAL_HARDENED_KEY_ALIAS"] as? String).orEmpty()
    val hardenedKeyPassword = (properties["FINAL_HARDENED_KEY_PASSWORD"] as? String).orEmpty()

    if (hardenedReleaseBuild) {
        if (!expectedReleaseCertSha256.matches(Regex("[0-9a-f]{64}"))) {
            throw GradleException(
                "FINAL_HARDENED_BUILD requires MOBILETINA_EXPECTED_CERT_SHA256 (64 hex chars)"
            )
        }
        if (hardenedKeystorePath.isBlank() ||
            hardenedStorePassword.isBlank() ||
            hardenedKeyAlias.isBlank() ||
            hardenedKeyPassword.isBlank()
        ) {
            throw GradleException(
                "FINAL_HARDENED_BUILD requires an explicit hardened keystore, passwords and alias"
            )
        }
    }

    signingConfigs {
        if (hardenedReleaseBuild) {
            create("mobileTinaHardened") {
                storeFile = file(hardenedKeystorePath)
                storePassword = hardenedStorePassword
                keyAlias = hardenedKeyAlias
                keyPassword = hardenedKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                // v4 is a sidecar optimized for incremental installation and is not part of
                // the standalone APK artifact distributed here.
                enableV4Signing = false
            }
        }
    }

    defaultConfig {
        applicationId = "com.v2ray.mobiletina"
        minSdk = 24
        targetSdk = 37
        versionCode = 744
        versionName = "2.2.6"
        multiDexEnabled = true
        // Keep only the app's supported Persian locale plus Android's mandatory
        // unqualified/default resources. This also strips dependency translations.
        resourceConfigurations.add("fa")

        buildConfigField("boolean", "MOBILETINA_HARDENED_BUILD", hardenedReleaseBuild.toString())
        buildConfigField(
            "String",
            "MOBILETINA_EXPECTED_CERT_SHA256",
            "\"$expectedReleaseCertSha256\""
        )

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        if (!fatApkAbiList.isNullOrEmpty()) {
            ndk {
                abiFilters.addAll(fatApkAbiList)
            }
        }
        splits {
            abi {
                isEnable = fatApkAbiList.isNullOrEmpty()
                reset()
                if (!fatApkAbiList.isNullOrEmpty()) {
                    // ABI filtering is handled by defaultConfig.ndk so AGP emits one APK.
                } else if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            if (hardenedReleaseBuild) {
                // AGP 9.2 / R8 full optimization path: shrinking, optimization, obfuscation,
                // repackaging and optimized resource shrinking are enabled for the final build.
                isMinifyEnabled = true
                isShrinkResources = true
                signingConfig = signingConfigs.getByName("mobileTinaHardened")
            } else {
                // Keep the stable diagnostic release available for regression comparison.
                isMinifyEnabled = false
                isShrinkResources = false
                if ((properties["FINAL_BUILD_SIGNING"] as? String) == "debug") {
                    signingConfig = signingConfigs.getByName("debug")
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3,
                    "universal" to 0, "armv7-armv8" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (!fatApkAbiList.isNullOrEmpty()) "armv7-armv8"
                    else output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "MobileTina_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4,
                    "universal" to 4, "armv7-armv8" to 4
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (!fatApkAbiList.isNullOrEmpty())
                        "armv7-armv8"
                    else if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "MobileTina_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

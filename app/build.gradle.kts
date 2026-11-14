import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String): String? =
    System.getenv(key) ?: localProperties.getProperty(key)

val keystorePath = secret("IBRIDGE_KEYSTORE")
val defaultKeystore = rootProject.file("app/keystore/ibridge-release.jks")
val hasDefaultKeystore = defaultKeystore.exists()
val releaseSigningName = when {
    keystorePath != null -> "release"
    hasDefaultKeystore -> "defaultRelease"
    else -> null
}

android {
    namespace = "com.ibridge.otg"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.ibridge.otg"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = secret("IBRIDGE_KEYSTORE_PASSWORD")
                keyAlias = secret("IBRIDGE_KEY_ALIAS")
                keyPassword = secret("IBRIDGE_KEY_PASSWORD")
                enableV3Signing = true
                enableV4Signing = true
            }
        } else if (hasDefaultKeystore) {
            // Stable sideload key shipped with the repo so consecutive CI releases can be
            // installed as updates. Override it with the IBRIDGE_KEYSTORE_* secrets above.
            create("defaultRelease") {
                storeFile = defaultKeystore
                storePassword = "ibridge123"
                keyAlias = "ibridge"
                keyPassword = "ibridge123"
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseSigningName != null) {
                signingConfigs.getByName(releaseSigningName)
            } else {
                // Last-resort fallback, keeps `assembleRelease` usable without any keystore.
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.kotlinx.coroutines.android)
}

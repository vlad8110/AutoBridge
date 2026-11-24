plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.autobridge.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.autobridge.phone"
        minSdk = 29
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }

    // Same env-var signing as the car app; no secret lives in the repo.
    signingConfigs {
        create("release") {
            System.getenv("AB_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("AB_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("AB_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("AB_KEY_PASSWORD") ?: System.getenv("AB_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("AB_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

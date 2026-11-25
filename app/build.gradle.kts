plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.autobridge"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    useLibrary("android.car")

    defaultConfig {
        applicationId = "dev.autobridge.car"
        minSdk = 29
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }

    // Release signing comes from env vars so no secret lives in the repo.
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

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Cluster turn-by-turn through Google's Templates Host (ClusterNav); the versions OCBM/carlink proved on GM.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-automotive:1.7.0")
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.zet.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.zet.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Kişisel kullanım için sabit anahtar: her derleme aynı imzayı taşır,
    // böylece yeni APK eskisinin üzerine (veriler silinmeden) kurulur.
    signingConfigs {
        create("zet") {
            storeFile = file("zet-release.jks")
            storePassword = "zetbrowser"
            keyAlias = "zet"
            keyPassword = "zetbrowser"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zet")
        }
        debug {
            signingConfig = signingConfigs.getByName("zet")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.car.screenguard"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.car.screenguard"
        minSdk = 24
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 側載安裝用，直接沿用 debug 簽章，release APK 才裝得進車機
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // 這支 App 是側載到車機（Android 10）用的，不會上 Google Play，
        // 刻意維持 targetSdk 30，所以忽略上架用的 targetSdk 檢查。
        disable.add("ExpiredTargetSdkVersion")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
}

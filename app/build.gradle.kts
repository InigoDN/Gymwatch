plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.diamantizador"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.diamantizador"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0-WATCH"

        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.wear:wear:1.3.0")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
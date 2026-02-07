plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "pl.kejlo.mzutv2.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.kejlo.mzutv2"
        minSdk = 26
        targetSdk = 35
        versionCode = 138
        versionName = "1.51"
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
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.watchface)
    implementation(libs.wear.watchface.complications.data)
    implementation(libs.wear.watchface.complications.data.source)
    implementation(libs.play.services.wearable)
    implementation(libs.concurrent.futures)
}

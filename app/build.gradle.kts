plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "pl.kejlo.mzutv2"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.kejlo.mzutv2"
        minSdk = 26
        targetSdk = 36
        versionCode = 153
        versionName = "1.62"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.wearable)
    implementation(libs.remote.interactions)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.play.review)
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "pl.kejlo.mzutv2"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.kejlo.mzutv2"
        minSdk = 26
        targetSdk = 35
        versionCode = 144
        versionName = "1.6"

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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.wearable)
    implementation(libs.remote.interactions)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("com.google.android.play:review:2.0.1")
}

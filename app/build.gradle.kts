import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { stream -> localProperties.load(stream) }
}

android {
    namespace = "pl.kejlo.zutnik"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.kejlo.zutnik"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "USOS_CONSUMER_KEY",    "\"${localProperties["usos.consumer_key"]    ?: ""}\"")
        buildConfigField("String", "USOS_CONSUMER_SECRET", "\"${localProperties["usos.consumer_secret"] ?: ""}\"")
        buildConfigField("String", "USOS_BASE_URL",        "\"https://usosapi.zut.edu.pl/\"")
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

    bundle {
        language {
            enableSplit = false
        }
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.play.review)
    implementation(libs.play.app.update)
}

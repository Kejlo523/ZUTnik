plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "pl.kejlo.mzutv2.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.kejlo.mzutv2"
        minSdk = 26
        targetSdk = 36
        versionCode = 145
        versionName = "1.6"
    }

    sourceSets {
        getByName("main") {
            val appRes = rootProject.layout.projectDirectory.dir("app/src/main/res")
            @Suppress("DEPRECATION")
            res.setSrcDirs(
                listOf(
                    file("src/main/res"),
                    appRes.dir("mipmap-anydpi-v26").asFile,
                    appRes.dir("mipmap-mdpi").asFile,
                    appRes.dir("mipmap-hdpi").asFile,
                    appRes.dir("mipmap-xhdpi").asFile,
                    appRes.dir("mipmap-xxhdpi").asFile,
                    appRes.dir("mipmap-xxxhdpi").asFile,
                ),
            )
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

    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.watchface.complications.data)
    implementation(libs.wear.watchface.complications.data.source)
    implementation(libs.play.services.wearable)
    implementation(libs.concurrent.futures)
    implementation(libs.recyclerview)
}

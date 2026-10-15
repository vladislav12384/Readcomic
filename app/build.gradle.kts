plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI passes its run number so every published build is an update of the previous one.
val buildNumber = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// A preview build (-Ppreview=true) to try new things in: an app of its own, "Readcomic β", installed
// next to the main one instead of over it.
val preview = (findProperty("preview") as String?) == "true"

android {
    namespace = "com.readcomic"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.readcomic"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
        if (preview) {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            manifestPlaceholders["appLabel"] = "Readcomic β"
        } else {
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }
    }

    signingConfigs {
        // A development key kept in the repository, so builds from any machine or from CI
        // install over each other. Use a private key for a store release.
        create("shared") {
            storeFile = rootProject.file("keystore/readcomic.jks")
            storePassword = "readcomic"
            keyAlias = "readcomic"
            keyPassword = "readcomic"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libarchive)
    implementation(libs.androidx.work.runtime)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

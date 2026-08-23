plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lagdaemon.creationremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lagdaemon.creationremote"
        minSdk = 26
        targetSdk = 35
        // Overridden by the release workflow via -PversionName=X.Y.Z
        // -PversionCode=N (parsed from the creation-remote-android-vX.Y.Z
        // release tag, same pattern as the suite's other apps' CI-supplied
        // -D<APP>_VERSION). Defaults here are for local dev builds only.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = project.findProperty("versionName") as String? ?: "0.0.1"
    }

    // Populated from ANDROID_KEYSTORE_FILE/_PASSWORD/_KEY_ALIAS env vars,
    // which only exist in the release workflow (decoded from the
    // ANDROID_KEYSTORE_BASE64 repo secret there) -- local/debug builds
    // fall back to no explicit release signing config at all, same as
    // running `./gradlew assembleDebug` always has.
    val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Capture
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)

    // Background upload / offline queue
    implementation(libs.androidx.work.runtime.ktx)

    // Pairing: QR scan, HTTP client, browser-based OAuth login, local token storage
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)

    debugImplementation(libs.androidx.ui.tooling)
}

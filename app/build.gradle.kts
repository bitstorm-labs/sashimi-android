plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "dev.bitstorm.sashimi"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bitstorm.sashimi"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Upload signing, read from env vars (CI) or gradle properties (local). When
    // absent — e.g. a contributor build or an un-secreted CI run — the release
    // build falls back to unsigned so `assembleRelease` still succeeds.
    // NOTE: blank counts as absent — CI passes `${{ steps.keystore.outputs.path }}`
    // which is an EMPTY STRING (not unset) when the secret isn't configured;
    // `file("")` throws "Cannot convert '' to File" (broke the v0.5.0 tag run).
    fun signingValue(name: String): String? = (System.getenv(name) ?: (findProperty(name) as String?))?.takeIf { it.isNotBlank() }
    val uploadStoreFile = signingValue("SASHIMI_UPLOAD_STORE_FILE")
    val uploadStorePassword = signingValue("SASHIMI_UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = signingValue("SASHIMI_UPLOAD_KEY_ALIAS") ?: "sashimi"
    val uploadKeyPassword = signingValue("SASHIMI_UPLOAD_KEY_PASSWORD") ?: uploadStorePassword
    val hasUploadSigning =
        uploadStoreFile != null && uploadStorePassword != null && file(uploadStoreFile).exists()

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasUploadSigning) signingConfigs.getByName("upload") else null
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
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // Room DB is constructed in ServiceLocator; entities/DAO live in :core.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // WorkManager: downloads run in :core, but the app needs the runtime on its
    // direct classpath so the foreground-service manifest entry merges in.
    implementation(libs.androidx.work.runtime)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

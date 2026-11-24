plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.bitstorm.sashimi.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric: the download-database migration test opens a real Room
    // database on the JVM (there is no emulator in CI).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// TV-readiness discipline (see spec Architecture): :core must never depend on
// Compose or any UI toolkit so a future :tv module can reuse it wholesale.
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    // Downloads engine: Room (metadata) + WorkManager (background downloads).
    // These are UI-free, so they respect the :core no-Compose discipline.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Robolectric downloads its Android framework jar at test time with plain HttpURLConnection, and
// on GitHub's runners that download started failing (IOException, 2026-11-23) though the jar is on
// Maven Central. Gradle resolves the same jar instead (cached, retried), and Robolectric runs offline
// from that copy. Keep the version in step with the robolectric library (4.17: SDK 34 = ...-i7) and
// with the tests' @Config(sdk = [34]).
val robolectricJars: Configuration by configurations.creating
dependencies {
    robolectricJars("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
}
val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")
val robolectricDeps =
    tasks.register<Copy>("robolectricDeps") {
        from(robolectricJars)
        into(robolectricDepsDir)
    }
tasks.withType<Test>().configureEach {
    dependsOn(robolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDepsDir.get().asFile.absolutePath)
}

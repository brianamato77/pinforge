plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The toolchain runtime is published as a release asset of the same repo the APK is built from.
val repo: String = System.getenv("GITHUB_REPOSITORY") ?: "brianamato77/pinforge"
val run: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.pinforge"
    compileSdk = 35

    defaultConfig {
        // Keep this id SHORT: the glibc loader lives at /data/data/<id>/l and that path
        // must fit inside the 27-byte PT_INTERP slot of downloaded Linux toolchains.
        applicationId = "io.pinforge"
        minSdk = 26
        // targetSdk 28 is deliberate: Android only lets apps targeting <=28 execute
        // binaries they downloaded into their own data dir (same approach as Termux).
        // This APK is sideloaded, so the Play Store target rule does not apply.
        targetSdk = 28
        versionCode = run
        versionName = "0.1.$run"
        buildConfigField("String", "RUNTIME_URL",
            "\"https://github.com/$repo/releases/download/runtime/runtime-aarch64.tar.gz\"")
    }

    signingConfigs {
        create("shared") {
            // Fixed key so each new build installs over the previous one.
            storeFile = file("pinforge.keystore")
            storePassword = "pinforge"
            keyAlias = "pinforge"
            keyPassword = "pinforge"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing and version, given by the release workflow (.github/workflows/release.yml) through
// environment variables. Without them (eg building on your own machine) the release build is signed with
// the debug key and keeps the version below, as before.
val releaseKeystore: String? = System.getenv("LEDGEDROID_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.ledgedroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ledgedroid"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("LEDGEDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("LEDGEDROID_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LEDGEDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LEDGEDROID_KEY_ALIAS")
                keyPassword = System.getenv("LEDGEDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

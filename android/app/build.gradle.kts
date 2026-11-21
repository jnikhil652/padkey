plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.buildverse.padkey"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.buildverse.padkey"
        minSdk = 26
        targetSdk = 34
        // Release workflow passes -PversionName=1.2.3 -PversionCode=N (from the git tag); local builds use the defaults.
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 2
        versionName = (findProperty("versionName") as String?) ?: "2.0.0"
    }

    // Release signing comes from the environment (or -P properties): PADKEY_KEYSTORE (path to the .jks),
    // PADKEY_KEYSTORE_PASSWORD, PADKEY_KEY_ALIAS (default "padkey"), PADKEY_KEY_PASSWORD (default = store password).
    // Nothing secret lives in the repo. Without a keystore the release build falls back to the debug signing key.
    fun cfg(name: String): String? = System.getenv(name) ?: (findProperty(name) as String?)
    val releaseKeystore = cfg("PADKEY_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = cfg("PADKEY_KEYSTORE_PASSWORD")
                keyAlias = cfg("PADKEY_KEY_ALIAS") ?: "padkey"
                keyPassword = cfg("PADKEY_KEY_PASSWORD") ?: cfg("PADKEY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}

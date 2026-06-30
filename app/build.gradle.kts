import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.obscura.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.obscura.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

// Replaces the deprecated `android { kotlinOptions { jvmTarget = "17" } }`
// block. Kotlin 2.x prefers the project-level kotlin DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ObscuraKit library
    implementation(project(":lib"))

    // Android SQLDelight driver
    implementation(libs.sqldelight.android)
    implementation(libs.sqldelight.coroutines)

    // libsignal Android native (replaces JVM variant from lib module)
    implementation(libs.libsignal.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)

    // Coroutines
    implementation(libs.coroutines.core)

    // Serialization (typed ORM models)
    implementation(libs.serialization.json)

    // Lifecycle (foreground reconnect)
    implementation(libs.lifecycle.process)

    // Secure storage
    implementation(libs.security.crypto)

    // SQLCipher — encrypted SQLite (same as Signal Android)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)

    // Desugaring (required by libsignal-android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

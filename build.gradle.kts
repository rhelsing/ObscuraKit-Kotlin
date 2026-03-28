plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.app) apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.phonebot"
    compileSdk = 35      // lokal installiert ist android-35/36 (nicht 34)

    defaultConfig {
        applicationId = "de.phonebot"
        minSdk = 24          // Camera2 manuell/RAW + USB-Host
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"

        // OpenCV bringt native Libs pro CPU-Architektur. Nur arm64 -> kleines APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Bildverarbeitung – offizielles OpenCV-Android-AAR (native Libs gebündelt,
    // Laden per OpenCVLoader.initLocal(), keine separate Manager-App nötig).
    implementation("org.opencv:opencv:4.11.0")

    // USB-Serial (CDC-ACM) für die OTG-Verbindung zum ESP32 (optional zur Laufzeit)
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // Reiner JVM-Test für den Interpreter (keine Android-Abhängigkeiten nötig).
    testImplementation("junit:junit:4.13.2")
}

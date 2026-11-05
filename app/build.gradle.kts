plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.phonebot"
    compileSdk = 35      // lokal installiert ist android-35/36 (nicht 34)

    defaultConfig {
        applicationId = "de.phonebot"
        minSdk = 24          // USB-Host + CameraX
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // CameraX – Kamerazugriff und Bildanalyse
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // USB-Serial (CDC-ACM) für die OTG-Verbindung zum ESP32
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cardroid.lib"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":mfi-bridge"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Pure-Java X25519 (Android API 26 has no native X25519 JCA until API 33).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}

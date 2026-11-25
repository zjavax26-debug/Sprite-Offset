plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "com.spriteoffset"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.spriteoffset"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

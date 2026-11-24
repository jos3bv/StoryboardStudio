plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.josebv.storyboardstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.josebv.storyboardstudio"
        minSdk = 23
        targetSdk = 28
        versionCode = 30
        versionName = "0.9.19"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

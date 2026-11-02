plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.customframe.lenovo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.customframe.lenovo"
        minSdk = 24
        targetSdk = 28
        versionCode = 3
        versionName = "3.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

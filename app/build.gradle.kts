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
        // Each GitHub build gets a higher number so Android accepts it as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "3").toInt() + 100
        versionName = "3.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Permanent signing key (provided by GitHub secrets). Without it, builds use a
    // temporary debug key and cannot be installed over a previous build.
    signingConfigs {
        create("shared") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
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

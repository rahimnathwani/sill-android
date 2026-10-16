plugins {
    id("com.android.application")
}

android {
    namespace = "sill.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "sill.android"
        minSdk = 29          // TLS 1.3 and ALPN on SSLSocket
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs as is; see README for your own key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":protocol"))
}

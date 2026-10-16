plugins {
    id("com.android.application")
}

// The version comes from the release tag (scripts/release.sh passes -PversionName=1.2.3);
// versionCode is derived from it: 1.2.3 -> 10203.
val appVersion = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Release signing from the environment (scripts/release.sh reads the password from the macOS
// keychain). Without it a release build is unsigned, as on CI.
val keystorePath: String? = System.getenv("SILL_KEYSTORE")
val keystorePassword: String? = System.getenv("SILL_KEYSTORE_PASSWORD")

android {
    namespace = "sill.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.rahimnathwani.sill"
        minSdk = 30          // TLS 1.3 and ALPN on SSLSocket; WindowInsets.Type and insetsController
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = System.getenv("SILL_KEY_ALIAS") ?: "upload"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":protocol"))
}

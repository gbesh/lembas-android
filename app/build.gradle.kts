plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.middleearth.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.middleearth.launcher"
        minSdk = 26
        targetSdk = 35
        // CI passes the build number, so every release is newer than the last.
        val build = (System.getenv("LEMBAS_BUILD") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
    }

    // One permanent key, so each new build installs as an update over the last one.
    // The key and its passwords live only in GitHub Actions secrets, never in this repo.
    val keystore = System.getenv("LEMBAS_KEYSTORE")?.let { file(it) }
    signingConfigs {
        if (keystore != null && keystore.exists()) create("lembas") {
            storeFile = keystore
            storePassword = System.getenv("LEMBAS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LEMBAS_KEY_ALIAS") ?: "lembas"
            keyPassword = System.getenv("LEMBAS_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local builds without the key fall back to the debug key (they won't update the phone's copy).
            signingConfig = signingConfigs.findByName("lembas") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Don't let style warnings stop the cloud build.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// The launcher itself is just the Android framework plus Kotlin. The only libraries are for the
// one-time setup over Wireless debugging (an adb client, TLS 1.3, and certificate generation).
dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

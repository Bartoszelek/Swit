plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "pl.swit"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.swit"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$runNumber"
    }

    signingConfigs {
        create("main") {
            storeFile = file("swit.keystore")
            storePassword = "swit1234"
            keyAlias = "swit"
            keyPassword = "swit1234"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("main") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("main")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

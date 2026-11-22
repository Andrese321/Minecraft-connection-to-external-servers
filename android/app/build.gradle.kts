plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.bedrocklanproxy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bedrocklanproxy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
    }
}

dependencies {
    implementation(project(":core"))
}

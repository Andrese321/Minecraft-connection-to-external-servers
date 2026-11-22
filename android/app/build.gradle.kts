plugins {
    id("com.android.application")
    kotlin("android")
}

// En GitHub Actions cada compilación lleva el número de ejecución, para saber qué versión tienes.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.bedrocklanproxy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bedrocklanproxy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Clave de depuración fija (no es secreta): así cada APK nueva se instala encima de la
        // anterior sin desinstalar. Para publicar en una tienda habría que usar una clave privada.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

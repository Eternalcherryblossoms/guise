import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.guise.xposed"
    compileSdk = 36
    // AGP 8.13 defaults to build-tools 35.0.0, which is not installed here (34/36/36.1/37 are).
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    // The hook runs inside other apps; there is nothing to shrink and aggressive
    // optimisation only risks breaking the entry class or the reflection helpers.
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            // META-INF/xposed/** must survive into the APK untouched.
            merges += listOf()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    // Provided by the framework at runtime; must not be packaged.
    compileOnly(libs.libxposed.api)
}

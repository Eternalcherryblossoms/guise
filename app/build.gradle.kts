import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release-signing credentials, from `keystore.properties` or the environment.
 *
 * Both routes on purpose: a workstation keeps them in a gitignored file, CI gets them from
 * repository secrets, and neither needs the key or its password to be part of the repository.
 * Absent credentials are **not** an error -- a machine without a signing key must still be able
 * to run `:core:test` and build the debug variant. `assembleRelease` then produces an unsigned
 * APK, which is obvious on inspection rather than silently mis-signed.
 *
 * Format: the JVM keystore. `keytool` since JDK 9 defaults to **PKCS12**; `-storetype JKS`
 * gives the older Java KeyStore. Both are read through the same JVM API and both work. What
 * does *not* work is BKS: that is BouncyCastle's runtime trust-store format, and AGP opens the
 * file with the platform `KeyStore` without registering that provider.
 */
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    signingProps.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseKeystore: File? = signingValue("storeFile", "GUISE_KEYSTORE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

val hasReleaseKey = releaseKeystore != null &&
    signingValue("storePassword", "GUISE_KEYSTORE_PASSWORD") != null &&
    signingValue("keyAlias", "GUISE_KEY_ALIAS") != null &&
    signingValue("keyPassword", "GUISE_KEY_PASSWORD") != null

android {
    namespace = "io.guise.app"
    compileSdk = 36
    // AGP 8.13 defaults to build-tools 35.0.0, which is not installed here (34/36/36.1/37 are).
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.guise"
        minSdk = 28
        targetSdk = 36
        versionCode = 19
        versionName = "5.9.0"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        // An explicit debug keystore committed under keystore/, rather than letting AGP
        // generate one in ~/.android. That keeps the build self-contained and reproducible
        // on a machine where the home directory is not writable.
        //
        // Note that this key is public by design, which is fine for debug builds and is
        // exactly why releases must not use it: anyone holding this repository could sign an
        // APK that installs over a debug-signed one.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("storePassword", "GUISE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GUISE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GUISE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Keep this off until the entry class and reflection helpers are proven to
            // survive shrinking; the module is loaded by name, so a stripped entry class
            // fails silently at runtime rather than at build time.
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "Guise-${name}-${versionName}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":xposed"))

    // The management app talks to the framework to persist config and to grow its scope.
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

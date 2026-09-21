import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// Compiled by JDK 21 but emitted as Java 17 bytecode: D8 has to ingest this jar on the
// way into the APK, and Java 21 class files are not a safe target there.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }

    // The catalog lives in :xposed's assets and TestCatalog finds it by searching sibling
    // module directories, so Gradle has no idea the suite depends on it. Declaring it as an
    // input is what stops a catalog change from leaving the tests "up-to-date" -- which would
    // mean a malformed catalog ships behind a green test report. Observed, not theoretical:
    // regenerating catalog.json left :core:test UP-TO-DATE in this very session.
    inputs.file(rootProject.file("xposed/src/main/assets/catalog.json"))
        .withPropertyName("bundledCatalog")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

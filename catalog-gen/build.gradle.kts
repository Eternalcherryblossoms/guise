import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    // Depends on :core so the generator emits exactly what the app parses, and so the
    // coherence validator it gates on is the same one the test suite runs. A generator that
    // re-implemented the checks could pass its own work.
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.guise.cataloggen.MainKt")
}

// Run from the repository root so the relative seed/asset paths resolve the same way in CI
// and on a workstation.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

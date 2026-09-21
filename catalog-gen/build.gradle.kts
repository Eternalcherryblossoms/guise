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

/**
 * The only part of the catalog pipeline that touches the network.
 *
 * Kept a separate, manually-run task rather than a build step for three reasons: the generator
 * must stay deterministic and offline so `--check` is meaningful; a build that phones Google on
 * every invocation is exactly the behaviour this project argues against; and the fetched result is
 * committed as a snapshot, so a catalog can be reproduced without network access at all.
 *
 *     ./gradlew :catalog-gen:fetchPixel -Pproxy=127.0.0.1:7890
 *     ./gradlew :catalog-gen:fetchPixel -Pargs="--limit 5 --only akita"
 */
tasks.register<JavaExec>("fetchPixel") {
    group = "catalog"
    description = "Fetch Google's OTA index and read each build's metadata into catalog/raw/"
    mainClass.set("io.guise.cataloggen.FetchPixelKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir

    // The development environment reaches the internet through a local HTTP proxy; CI does not.
    // Passing it as a project property keeps the proxy out of the committed build script.
    (project.findProperty("proxy") as String?)?.let { proxy ->
        val parts = proxy.split(":")
        if (parts.size == 2) {
            systemProperty("https.proxyHost", parts[0])
            systemProperty("https.proxyPort", parts[1])
            systemProperty("http.proxyHost", parts[0])
            systemProperty("http.proxyPort", parts[1])
        }
    }
    (project.findProperty("args") as String?)?.let { extra ->
        args = extra.split(" ").filter(String::isNotBlank)
    }
}

/**
 * Reduces a corpus of firmware `build.prop` dumps to the fields the catalog can use.
 *
 * Like [fetchPixel], this is the network-and-bulk half of the pipeline, kept out of the generator
 * so that `--check` stays deterministic and offline. The corpus itself is not part of this
 * repository; the reduced snapshot is, and it is what the generator reads.
 *
 *     ./gradlew :catalog-gen:extractBuildProps -Pargs="--dir ../tadiphone \
 *         --override kona=sm8250,taro=sm8450"
 */
tasks.register<JavaExec>("extractBuildProps") {
    group = "catalog"
    description = "Reduce a firmware build.prop corpus to catalog/raw/buildprops.json"
    mainClass.set("io.guise.cataloggen.ExtractBuildPropsKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    (project.findProperty("args") as String?)?.let { extra ->
        args = extra.split(" ").filter(String::isNotBlank)
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

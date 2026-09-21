pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS, not FAIL_ON_PROJECT_REPOS: this Gradle install carries a global
    // init script (E:\gradle-8.14.3\init.d\init.gradle) that injects Aliyun/mavenCentral
    // into every project. It does *not* add google(), and AGP plus all of AndroidX live
    // there, so the settings-level repositories must win. FAIL_ON_PROJECT_REPOS would
    // hard-fail on that init script instead.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Fallback mirror; the host reaches the internet through FlClash.
        maven("https://maven.aliyun.com/repository/public/")
    }
}

rootProject.name = "Guise"

// Guise is layered, not monolithic. Each layer is a separate insertion point into the
// "what the system tells the app" pipeline, and they trade reach against detectability:
//
// :core   - pure JVM. Device profile model + config codec. Unit-testable off-device.
//           Shared by every layer so they cannot disagree about what a profile means.
// :xposed - LSPosed layer. Per-app Java-visible channels. Runs inside the target process,
//           so it covers per-app granularity but leaves in-process traces.
// :app    - Compose management UI, root bridge, and the Magisk payload installer.
// :probe  - the diagnostic harness. Claims no permissions and reads only what any ordinary
//           app can read, because its value depends on seeing exactly what an observer sees.
//
// :catalog-gen - build-time only, never shipped. Turns the hand-maintained seed corpus into
//           xposed/src/main/assets/catalog.json and fails the build on drift or on a coverage
//           hole. It depends on :core so that a generated catalog is checked by the same
//           validator the test suite uses -- a generator with its own copy of the rules would
//           validate its own mistakes.
include(":core")
include(":xposed")
include(":app")
include(":probe")
include(":catalog-gen")

# Build Instructions — code-editor-lib v1.0.3

This project is a standard Android multi-module Gradle build.

## Prerequisites

- **JDK 17** (Temurin 17.0.13+11 was used for the v1.0.3 release).
  AGP 8.2 requires JDK 17 minimum. JDK 21 also works.
- **Android SDK** with:
  - `cmdline-tools;latest`
  - `platform-tools`
  - `platforms;android-34`
  - `build-tools;34.0.0`
- **Gradle 8.5** (the wrapper is bundled, so an explicit install is optional).

## One-time setup

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

Create `local.properties` at the project root with:

```
sdk.dir=/path/to/android-sdk
```

(AGP reads this to find `android.jar`. The repository ships a
`local.properties` pointing at the build environment used for v1.0.3 —
overwrite it with your own path.)

## Build commands

```bash
# Library AAR (library/build/outputs/aar/library-debug.aar)
./gradlew :library:assembleDebug

# Demo APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleDebug

# Unit tests (266 tests, JUnit 5, JVM host)
./gradlew :library:testDebugUnitTest

# Everything
./gradlew assembleDebug testDebugUnitTest
```

## Project layout

```
code-editor-lib/
├── library/                # :com.android.library  → library-debug.aar
│   ├── build.gradle.kts
│   └── src/main/java/com/codeeditor/...
├── app/                    # :com.android.application  → app-debug.apk
│   ├── build.gradle.kts
│   └── src/main/java/jo/codeeditor/demo/...
├── build.gradle.kts        # plugins { id("com.android.*") version "8.2.0" apply false }
└── settings.gradle.kts     # include(":library", ":app")
```

## Verified toolchain (v1.0.3 release)

| Tool    | Version                                  |
|---------|------------------------------------------|
| JDK     | Temurin 17.0.13+11                       |
| Gradle  | 8.5                                      |
| AGP     | 8.2.0                                    |
| Android | platform 34, build-tools 34.0.0          |
| minSdk  | 24                                       |
| targetSdk | 34                                     |

## Release artifacts

After `./gradlew :app:assembleDebug`:

- `library/build/outputs/aar/library-debug.aar`  (~172 KB)
- `app/build/outputs/apk/debug/app-debug.apk`    (~139 KB)

The release ZIP bundles the **source tree** (without `.gradle/` and
`build/` directories) **and** a copy of the freshly built `app-debug.apk`
at the project root, so consumers can install the demo without rebuilding.

# Contributing to code-editor-lib

Thanks for your interest in contributing! This guide covers the dev setup,
code conventions, and the pull-request process.

## Dev setup

### Prerequisites

- **JDK 17** (Temurin recommended — AGP 8.2 requires JDK 17 minimum).
- **Android SDK** with `platform-tools`, `platforms;android-34`, and
  `build-tools;34.0.0`.
- **Gradle 8.5** (the wrapper `./gradlew` will download it automatically on
  first run, or install it manually to avoid the per-build download).

### Install the toolchain (Linux x86_64)

```bash
mkdir -p $TOOLS_DIR
cd $TOOLS_DIR

# JDK 17 Temurin
wget -q "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz" -O temurin17.tar.gz
tar -xzf temurin17.tar.gz

# Android cmdline-tools
wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O cmdline-tools.zip
mkdir -p android-sdk/cmdline-tools
cd android-sdk/cmdline-tools
unzip -q $TOOLS_DIR/cmdline-tools.zip
mv cmdline-tools latest

export JAVA_HOME=$TOOLS_DIR/jdk-17.0.13+11
export ANDROID_HOME=$TOOLS_DIR/android-sdk
export ANDROID_SDK_ROOT=$TOOLS_DIR/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Gradle 8.5 (optional — the wrapper will download it if you skip this)
wget -q "https://services.gradle.org/distributions/gradle-8.5-bin.zip" -O gradle-8.5.zip
unzip -q gradle-8.5.zip
```

### Create `local.properties`

At the project root:

```
sdk.dir=/path/to/your/android-sdk
```

### Build & test

```bash
# Run the full test suite (464 tests in v1.0.8).
./gradlew :library:testDebugUnitTest

# Build the demo APK + library AAR.
./gradlew :library:assembleDebug :app:assembleDebug

# Clean build + tests + APK (the canonical "does everything pass" check).
./gradlew clean :library:assembleDebug :app:assembleDebug :library:testDebugUnitTest
```

## Code conventions

- **Java 11 source/target** (`sourceCompatibility = JavaVersion.VERSION_11`).
- **4-space indentation**, no tabs.
- **Imports**: `java.*` first, then `javax.*`, then `jo.codeeditor.*`, then
  `android.*`. No wildcard imports.
- **No `new Paint()` in the draw path** — reuse `Paint` fields to avoid
  GC pressure during `onDraw`. See `EditorView`'s `bgPaint`, `textPaint`,
  `selPaint`, etc.
- **All offsets clamped** to `[0, doc.length()]` in the draw path — a
  one-frame stale offset must never crash the view. Use the `clamp(int, lo, hi)`
  helper.
- **JUnit 5** for tests. Pure-JVM tests only — no instrumented `androidTest`.
  If a behavior can't be tested without Android, add a manual test note in
  `FIX_NOTES.md`.
- **No emojis in source code** (except in user-facing strings if already
  present).

## Adding a new feature

1. **Write the test first** (or alongside). Every new behavior must have a
   test in `library/src/test/java/com/codeeditor/...`. If it's a bug fix,
   add a regression test in `V108RegressionTest.java` (or a new
   `V109RegressionTest.java` for the next version).
2. **Follow the layered architecture**:
   - Pure-Java engine logic → `session/`, `document/`, `rope/`, `edit/`,
     `find/`, `shift/`, `highlight/`, `fold/`, `wrap/`, `snippet/`,
     `cache/`, `completion/`, `doc/`, `actions/`, `navigation/`, `blocks/`.
   - Android View rendering → `view/`.
   - The engine must NOT depend on `android.*` — only the View layer does.
3. **Add a `@since v1.0.x` Javadoc tag** on any new public class or method.
4. **Update `FIX_NOTES.md`** with a section describing the change, the
   rationale, and any bug it fixes.
5. **Update `CHANGELOG.md`** under the `[Unreleased]` section.
6. **Run the full test suite** — `./gradlew clean :library:testDebugUnitTest`
   must pass with 0 failures.

## Pull-request process

1. Fork the repo and create a feature branch:
   `git checkout -b feature/my-feature`.
2. Commit your changes with a clear message:
   `feat(completion): add fuzzy matching` or
   `fix(ime): clear composing region on completion accept`.
3. Push and open a PR. In the description:
   - Link to any issue the PR addresses.
   - List the tests you added.
   - Note any breaking changes (none should sneak in without a major
     version bump).
4. CI will run `./gradlew clean :library:testDebugUnitTest :app:assembleDebug`.
   All tests must pass.
5. A maintainer will review within a few days.

## Reporting bugs

Open an issue with:

- **code-editor-lib version** (from `app/build.gradle.kts` `versionName`).
- **Android version + device** (or "host JVM" for pure-Java bugs).
- **Steps to reproduce** — the smallest code snippet that triggers the bug.
- **Expected vs. actual behavior**.
- **Logcat output** if the bug is a crash.

## License

By contributing, you agree that your contributions are licensed under the
MIT license (see `LICENSE`).

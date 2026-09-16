plugins {
    id("com.android.library")
    // v3.34.0: publish the AAR for JitPack / Maven consumers.
    id("maven-publish")
}

android {
    namespace = "jo.codeeditor.ui"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }

    // v3.34.0: publish the release variant (required for maven-publish's
    // components["release"] to exist) with a sources jar for IDE navigation.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // v3.33.7: Robolectric tests need this to avoid "SDK not found" errors.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // UI depends on core (engine) and lsp-api (Language SPI)
    api(project(":cel-core"))
    api(project(":cel-lsp-api"))

    // v3.33.7: Tests Robolectric pour le lifecycle de EditorView.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// v3.33.7: Use JUnit 4 for Robolectric (Robolectric doesn't support JUnit 5 yet).
tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// ── v3.34.0 — Maven publication (JitPack / local `publishToMavenLocal`) ──
// JitPack overrides groupId (com.github.<user>) and version (git tag) at
// build time; these values are the standalone/local-publish defaults.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "cel-ui"
                version = project.version.toString()
            }
        }
    }
}

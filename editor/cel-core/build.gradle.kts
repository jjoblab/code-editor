plugins {
    id("com.android.library")
    // v3.34.0: publish the AAR for JitPack / Maven consumers.
    id("maven-publish")
}

android {
    namespace = "jo.codeeditor.core"
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
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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
                artifactId = "cel-core"
                version = project.version.toString()
            }
        }
    }
}

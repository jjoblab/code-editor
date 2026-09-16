plugins {
    id("com.android.library")
    // v3.34.0: publish the AAR for JitPack / Maven consumers.
    id("maven-publish")
}

android {
    namespace = "jo.codeeditor.lsp"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
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

    // v3.5.0: Default values for android.* stubs in unit tests. Without
    // this, android.util.Log.i/w/e throw RuntimeException ("not mocked")
    // whenever DefaultLanguageClient logs a server message — which broke
    // the LspModuleTest.defaultLanguageClient_noOpImplementations test.
    // With isReturnDefaultValues = true, Log.* return 0/void and the test
    // can exercise the no-op client methods without a real Android runtime.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // LSP4J needs kotlinx-coroutines only if we use suspend fns — we keep
    // the API Java-only (CompletableFuture) to avoid the coroutines dep.
    // LSP4J itself is ~700 KB dex.
    packaging {
        resources {
            excludes += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    // compileOnly the core library — consumers must bring their own :library.
    compileOnly(project(":cel-lsp-api"))
    compileOnly(project(":cel-core"))
    compileOnly(project(":cel-ui"))

    // LSP4J — the canonical Java binding for the Language Server Protocol.
    // EPL-2.0 license (compatible with MIT for combined works).
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.22.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.22.0")
    // v2.37 : Gson (déjà transitif de LSP4J, déclaré EXPLICITEMENT) —
    // désérialisation des réponses des requêtes LSP personnalisées
    // (textDocument/superDefinition) envoyées via RemoteEndpoint.
    implementation("com.google.code.gson:gson:2.10.1")

    // JUnit 5 for pure-JVM tests.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":cel-lsp-api"))
    testImplementation(project(":cel-core"))
    testImplementation(project(":cel-ui"))
    // v3.33.7: Robolectric for InProcessStreamConnectionProvider tests.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// v3.33.7: Use JUnit 4 for Robolectric tests in :cel-lsp.
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
                artifactId = "cel-lsp"
                version = project.version.toString()
            }
        }
    }
}

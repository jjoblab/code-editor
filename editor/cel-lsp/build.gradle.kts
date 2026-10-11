plugins {
    id("com.android.library")
    // Publication de l'AAR pour les consommateurs JitPack / Maven.
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

    // Publie la variante release (requis pour que components["release"] de
    // maven-publish existe) avec un jar de sources pour la navigation IDE.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Valeurs par défaut des stubs android.* en tests unitaires. Sans
    // cela, android.util.Log.i/w/e lève RuntimeException (« not mocked »)
    // dès que DefaultLanguageClient journalise un message serveur — ce qui
    // cassait LspModuleTest.defaultLanguageClient_noOpImplementations.
    // Avec isReturnDefaultValues = true, Log.* renvoie 0/void et le test
    // peut exercer les méthodes no-op du client sans runtime Android réel.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // LSP4J n'a besoin de kotlinx-coroutines que pour les fonctions
    // suspend — l'API est gardée Java pur (CompletableFuture) pour éviter
    // cette dépendance. LSP4J pèse ~700 Ko dex.
    packaging {
        resources {
            excludes += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    // compileOnly sur les bibliothèques — les consommateurs doivent
    // apporter leur propre version (évite la duplication d'artefacts).
    compileOnly(project(":cel-lsp-api"))
    compileOnly(project(":cel-core"))
    compileOnly(project(":cel-ui"))

    // LSP4J — la liaison Java canonique du Language Server Protocol.
    // Licence EPL-2.0 (compatible MIT pour les œuvres combinées).
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.22.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.22.0")
    // Gson (déjà transitif de LSP4J, déclaré EXPLICITEMENT) —
    // désérialisation des réponses des requêtes LSP personnalisées
    // (textDocument/superDefinition) envoyées via RemoteEndpoint.
    implementation("com.google.code.gson:gson:2.10.1")

    // JUnit 5 pour les tests JVM purs.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":cel-lsp-api"))
    testImplementation(project(":cel-core"))
    testImplementation(project(":cel-ui"))
    // Robolectric pour les tests d'InProcessStreamConnectionProvider
    // (Robolectric 4.13 ne supporte pas JUnit 5 — ces tests restent JUnit 4,
    // exécutés sur la plateforme JUnit 5 via le moteur vintage).
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    // Moteur vintage : exécute les tests JUnit 4 (Robolectric, @Rule
    // TemporaryFolder) sur la même plateforme JUnit 5 que les tests Jupiter
    // (LspModuleTest) — les deux générations cohabitent.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Plateforme JUnit 5 : les tests Jupiter (LspModuleTest) et les tests
// JUnit 4 (Robolectric via moteur vintage) tournent sur le même lanceur.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// ── Publication Maven (JitPack / `publishToMavenLocal` local) ──
// JitPack surcharge le groupId (com.github.<user>) et la version (tag git)
// au build ; ces valeurs sont les défauts standalone/publication locale.
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
        // Dépôt GitHub Packages — activé par les propriétés gpr.user /
        // gpr.key (~/.gradle/gradle.properties en local, -P… dans la CI) ;
        // sans identifiants seule publishToMavenLocal reste utilisable.
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/jjoblab/code-editor")
                credentials {
                    username = (findProperty("gpr.user") as String?) ?: System.getenv("GPR_USERNAME")
                    password = (findProperty("gpr.key") as String?) ?: System.getenv("GPR_TOKEN")
                }
            }
        }
    }
}

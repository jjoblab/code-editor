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
            // A-06 (audit 3.69.0) : couverture en mode RAPPORT — les
            // rapports Jacoco des tests unitaires sont générés pour la CI
            // (téléversés, jamais bloquants : aucun seuil).
            enableUnitTestCoverage = true
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
        // Bloquant : une nouvelle violation (erreur OU avertissement non
        // couvert par la baseline) échoue le build. La baseline
        // lint-baseline.xml fige l'existant connu — elle ne doit jamais
        // être régénérée pour « faire passer » un nouveau problème.
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = false
        baseline = file("lint-baseline.xml")
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

    // LSP4J — la liaison Java canonique du Language Server Protocol.
    // Licence EPL-2.0 (compatible MIT pour les œuvres combinées).
    implementation(libs.lsp4j)
    implementation(libs.lsp4j.jsonrpc)
    // Gson (déjà transitif de LSP4J, déclaré EXPLICITEMENT) —
    // désérialisation des réponses des requêtes LSP personnalisées
    // (textDocument/superDefinition) envoyées via RemoteEndpoint.
    implementation(libs.gson)

    // JUnit 5 pour les tests JVM purs.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":cel-lsp-api"))
    testImplementation(project(":cel-core"))
    testImplementation(project(":cel-ui"))
    // Robolectric pour les tests d'InProcessStreamConnectionProvider
    // (Robolectric 4.13 ne supporte pas JUnit 5 — ces tests restent JUnit 4,
    // exécutés sur la plateforme JUnit 5 via le moteur vintage).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    // Moteur vintage : exécute les tests JUnit 4 (Robolectric, @Rule
    // TemporaryFolder) sur la même plateforme JUnit 5 que les tests Jupiter
    // (LspModuleTest) — les deux générations cohabitent.
    testRuntimeOnly(libs.junit.vintage.engine)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Tous les avertissements javac (unchecked, deprecation…) visibles ;
    // -Werror sera envisagé quand le bruit historique sera éliminé.
    options.compilerArgs.add("-Xlint:all")
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
                // Licence du projet (migration Apache 2.0) : consignée dans le
                // POM pour que les consommateurs la voient d'un coup d'œil
                // (affichage Gradle, scans de conformité).
                pom {
                    licenses {
                        license {
                            name = "Apache-2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                }
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

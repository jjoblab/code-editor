plugins {
    id("com.android.library")
    // Publication de l'AAR pour les consommateurs JitPack / Maven
    // (même discipline que cel-core : le module est optionnel côté
    // consommateur — ne pas le dépendre laisse la voie intégrée active).
    id("maven-publish")
}

android {
    namespace = "jo.codeeditor.textmate"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug {
            isMinifyEnabled = false
            enableUnitTestCoverage = true
        }
    }

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
        // Même discipline que les autres modules : bloquant dès le
        // premier jour — ce module naît SANS baseline (aucune dette
        // lint), et aucune baseline ne sera jamais régénérée ici pour
        // « faire passer » un problème.
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = false
        disable += "AndroidGradlePluginVersion"
        // compileSdk est aligné sur les autres modules du dépôt (34) ;
        // le check « une version plus récente existe » n'est pas un
        // défaut de code — les modules historiques filtrent cette même
        // entrée via leur baseline, ici le désactiveur est explicite.
        disable += "GradleDependency"
    }
}

dependencies {
    // ADR-002 (D1-a) : le module de grammaires dépend de :cel-core
    // SEUL — il implémente jo.codeeditor.highlight.TextMateTokenizer
    // et ne connaît ni cel-ui ni cel-lsp.
    api(project(":cel-core"))

    // Plan 10 (D3, refonte 4.0) : moteur d'expressions rationnelles du
    // dialecte des grammaires TextMate — dépendance unique du module,
    // consommée exclusivement par la couture jo.codeeditor.textmate.regex
    // (règle d'or 1 du plan 10 : confinement vérifié par test).
    implementation(libs.joni)
    implementation(libs.jcodings)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

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
                artifactId = "cel-textmate"
                version = project.version.toString()
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

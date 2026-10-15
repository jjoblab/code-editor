plugins {
    id("com.android.library")
    // Publication de l'AAR pour les consommateurs JitPack / Maven.
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

    // Les tests Robolectric en ont besoin pour éviter les erreurs « SDK not found ».
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

    // Tests Robolectric pour le cycle de vie d'EditorView.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    // Marquage @RestrictTo des scells internes partagés avec les
    // sous-packages de view/ (compileOnly : annotation absente du POM publié).
    compileOnly(libs.androidx.annotation)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Tous les avertissements javac (unchecked, deprecation…) visibles ;
    // -Werror sera envisagé quand le bruit historique sera éliminé.
    options.compilerArgs.add("-Xlint:all")
}

// JUnit 4 pour Robolectric (Robolectric ne supporte pas encore JUnit 5).
tasks.withType<Test>().configureEach {
    useJUnit()
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
                artifactId = "cel-ui"
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

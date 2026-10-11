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
        debug { isMinifyEnabled = false }
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
        abortOnError = false
        checkReleaseBuilds = false
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
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    // Marquage @RestrictTo des scells internes partagés avec les
    // sous-packages de view/ (compileOnly : annotation absente du POM publié).
    compileOnly("androidx.annotation:annotation:1.8.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
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

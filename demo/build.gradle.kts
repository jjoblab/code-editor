plugins {
    id("com.android.application")
}

android {
    namespace = "jo.codeeditor.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "jo.codeeditor.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        // Alignée sur la version de la bibliothèque (rootProject) pour que
        // l'application installée affiche la même version que le code testé.
        versionName = rootProject.version.toString()
    }

    buildTypes {
        release {
            // Démo : pas de minification, signature debug suffisante.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric a besoin des ressources pour lancer l'activité.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Bloquant : une nouvelle violation (erreur OU avertissement non
        // couvert par la baseline) échoue le build. La baseline
        // lint-baseline.xml fige l'existant connu — elle ne doit jamais
        // être régénérée pour « faire passer » un nouveau problème.
        abortOnError = true
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // La bibliothèque locale — cel-ui embarque transitivement cel-core
    // (moteur) et cel-lsp-api (SPI langage).
    implementation(project(":cel-ui"))
    // ADR-002 (C3) : voie grammaires — l'app de démo croche le module
    // optionnel :cel-textmate au démarrage (C# « grammaire-only »,
    // grammaire textmate/csharp.tmg.json embarquée). Un hôte qui
    // n'embarque PAS ce module garde la coloration intégrée.
    implementation(project(":cel-textmate"))
    // 3.73.0 : la démo vit désormais le module :cel-lsp de bout en bout
    // — le mini serveur LSP Java (diagnostics) traverse le cadre LSP
    // (poignée de main, didOpen/didChange, publishDiagnostics) via un
    // transport in-process. La bascule « Moteur LSP » du menu Java
    // oppose cette voie au moteur maison (analyseur synthétique).
    implementation(project(":cel-lsp"))
    // Un hôte qui implémente un serveur de langage (le mini serveur
    // Java de la démo) programme contre LSP4J directement : la
    // dépendance est donc propre au demo, pas transitive de :cel-lsp
    // (qui la déclare `implementation`).
    implementation(libs.lsp4j)

    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Test de fumée Robolectric : lancement réel de MainActivity.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Tous les avertissements javac visibles ; -Werror sera envisagé
    // quand le bruit historique sera éliminé.
    options.compilerArgs.add("-Xlint:all")
}

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
        versionCode = 1
        versionName = "3.38.0"
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
        abortOnError = false
    }
}

dependencies {
    // La bibliothèque locale — cel-ui embarque transitivement cel-core
    // (moteur) et cel-lsp-api (SPI langage).
    implementation(project(":cel-ui"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Test de fumée Robolectric : lancement réel de MainActivity.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}

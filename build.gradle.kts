// Fichier de build racine de la bibliothèque code-editor.
//
// NOTE : les sous-modules :code-editor:core, :code-editor:lsp-api,
// :code-editor:ui peuvent aussi être inclus directement dans le projet
// principal CodeIDE (settings.gradle.kts de sa racine). Ce fichier n'est
// utilisé QUE quand on construit la bibliothèque en standalone (depuis le
// répertoire code-editor/ directement).
//
// Quand le projet principal inclut les modules via `include(":code-editor:core")`,
// c'est le build.gradle.kts du ROOT (CodeIDE/) qui s'applique, pas celui-ci.
plugins {
    // AGP 9.0.0, aligné sur le wrapper Gradle 9.5.1.
    id("com.android.library") version "9.0.0" apply false
}

group = "jo.codeeditor"
version = "3.37.0"

// Propage group/version à chaque module pour que les publications
// `maven-publish` (déclarées dans le build.gradle.kts de chaque module)
// partagent des coordonnées Maven cohérentes sans dupliquer la version
// quatre fois.
subprojects {
    group = rootProject.group
    version = rootProject.version
}

// Top-level build file pour code-editor library.
//
// NOTE: Depuis la v0.1.0.1, les sous-modules :code-editor:core, :code-editor:lsp-api,
// :code-editor:ui sont inclus directement dans le projet principal CodeIDE
// (settings.gradle.kts du root). Ce fichier n'est utilisé QUE quand on build
// la librairie en standalone (depuis le répertoire code-editor/ directement).
//
// Quand le projet principal inclut les modules via `include(":code-editor:core")`,
// c'est le build.gradle.kts du ROOT (CodeIDE/) qui s'applique, pas celui-ci.
plugins {
    // v3.36.0 (roadmap item 10): AGP 8.5.2 → 9.0.0, aligned with the
    // Gradle 9.5.1 wrapper. CodeAssist v3.20 runs the same generation.
    id("com.android.library") version "9.0.0" apply false
}

group = "jo.codeeditor"
// v3.36.0 (2026-09-17) — minor bump : diagnostics groupés par ligne
// (sheet + badge), keymap data-driven rebindable, registre de langages
// contribuables, sweep diagnostics des onglets ouverts, SPI plugins
// décorations (EditorPainterHost), migration Gradle 9.5.1 / AGP 9,
// démantèlement EditorView (popup-anchors + zoom extraits) — sans
// breaking change d'API publique (nouveautés additives).
version = "3.36.0"

// v3.34.0: propagate group/version to every module so `maven-publish`
// publications (added in each module's build.gradle.kts) get consistent
// Maven coordinates without duplicating the version four times.
subprojects {
    group = rootProject.group
    version = rootProject.version
}

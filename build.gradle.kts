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
// v3.37.0 (2026-09-17) — minor bump : chords keymap (Outcome.Pending,
// commandes TOGGLE_LINE/BLOCK_COMMENT), retrait de l'état statique
// SyntaxHighlighter.textMateEnabled (B13 — gate allowTextMate par appel),
// CI GitHub Actions (roadmap item 12). API : additions additives ;
// retrait interne des mutateurs statiques setTextMateEnabled/
// isTextMateEnabled (sans effet depuis le retrait de tm4e v2.55).
version = "3.37.0"

// v3.34.0: propagate group/version to every module so `maven-publish`
// publications (added in each module's build.gradle.kts) get consistent
// Maven coordinates without duplicating the version four times.
subprojects {
    group = rootProject.group
    version = rootProject.version
}

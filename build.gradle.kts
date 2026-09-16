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
    id("com.android.library") version "8.5.2" apply false
}

group = "jo.codeeditor"
// v3.35.0 (2026-09-16) — minor bump : commentaires language-driven
// (CommentSyntax), cache de layout contenu-adressé (64 entrées),
// fold prefix-sum O(log folds), maxH mémoïsé — sans breaking change
// d'API publique (nouveautés additives : getCommentSyntax/
// setCommentSyntax/getFoldRevision/getFontRevision).
version = "3.35.0"

// v3.34.0: propagate group/version to every module so `maven-publish`
// publications (added in each module's build.gradle.kts) get consistent
// Maven coordinates without duplicating the version four times.
subprojects {
    group = rootProject.group
    version = rootProject.version
}

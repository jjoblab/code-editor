pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "code-editor"

// ─── Editor modules (editor/) ───────────────────────────────────
include(":cel-core")
project(":cel-core").projectDir = file("editor/cel-core")

include(":cel-lsp-api")
project(":cel-lsp-api").projectDir = file("editor/cel-lsp-api")

include(":cel-ui")
project(":cel-ui").projectDir = file("editor/cel-ui")

// ADR-002 (C3) : module optionnel de grammaires — dépend de :cel-core
// seul, implémente jo.codeeditor.highlight.TextMateTokenizer.
include(":cel-textmate")
project(":cel-textmate").projectDir = file("editor/cel-textmate")

include(":cel-lsp")
project(":cel-lsp").projectDir = file("editor/cel-lsp")

// ─── Application de démonstration (terrain de jeu) ───────────────
include(":demo")
project(":demo").projectDir = file("demo")

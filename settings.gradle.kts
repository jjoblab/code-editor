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


include(":cel-lsp")
project(":cel-lsp").projectDir = file("editor/cel-lsp")
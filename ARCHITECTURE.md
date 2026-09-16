# Architecture v3.31.0 — Éditeur et Preview XML découplés

## Vision

À partir de v3.31.0, la librairie éditeur (`:ui`) et les modules de preview XML
sont **deux entités distinctes**. L'éditeur ne dépend plus des modules de
preview. L'intégration se fait via une interface (`EditorPreviewHost`)
implémentée par l'application hôte.

## Structure du projet

```
code-editor-lib/
├── settings.gradle.kts              — inclut tous les modules
├── build.gradle.kts                 — version 3.31.0
├── gradle/wrapper/                  — Gradle 8.5
├── gradle.properties
│
├── editor/                          ← ENTITÉ 1 : librairie éditeur
│   ├── core/                          — moteur pur Java (Rope, EditorSession…)
│   ├── lsp-api/                       — Language SPI (interfaces)
│   ├── ui/                            — Android View layer (EditorView…)
│   ├── cel-lsp/                       — LSP4J client
│   ├── cel-lsp-java/                  — support Java
│   ├── cel-lsp-kotlin/                — support Kotlin
│   ├── cel-lsp-lua/                   — support Lua
│   └── app/                           — app demo editor
│
├── preview/                         ← ENTITÉ 2 : modules preview XML
│   ├── preview-api/                   — contrats (RenderNode, RCanvas…)
│   ├── preview-android/               — NativeXmlPreviewRenderer
│   └── preview-panels/                — palette, inspector, tree, blueprint
│
├── layout-editor-app/               ← APP AUTONOME LayoutEditor
│   └── (dépend seulement de :preview-*)
│
├── apk/                             — APKs debug générés
│   ├── app-debug.apk                  — app demo editor
│   └── layout-editor-app-debug.apk    — app LayoutEditor
│
├── docs/
│   ├── ARCHITECTURE.md                — ce fichier
│   └── LAYOUT_EDITOR.md               — guide LayoutEditor
├── CHANGELOG.md
├── LAYOUT_EDITOR_PLAN.md
├── worklog.md
└── README.md, BUILD.md, USAGE.md, etc.
```

## Dépendances entre modules

```
┌─────────────────────────────────────────────────────────────┐
│  editor/app (demo)                                           │
│    │                                                         │
│    ├── implementation(:ui)                                   │
│    ├── implementation(:cel-lsp-java, :cel-lsp, …)            │
│    ├── implementation(:preview-api)                          │
│    ├── implementation(:preview-android)                      │
│    └── implementation(:preview-panels)                       │
│                                                              │
│  Au runtime :                                                │
│    editorView.setPreviewHost(new XmlPreviewHost(this, …))    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  layout-editor-app (autonome)                                │
│    │                                                         │
│    ├── implementation(:preview-api)                          │
│    ├── implementation(:preview-android)                      │
│    └── implementation(:preview-panels)                       │
│                                                              │
│  PAS de dépendance vers :ui                                  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  :ui (librairie éditeur)                                    │
│    │                                                         │
│    ├── api(:core)                                            │
│    └── api(:lsp-api)                                         │
│                                                              │
│  PAS de dépendance vers :preview-*                           │
└─────────────────────────────────────────────────────────────┘
```

## Interface EditorPreviewHost

Le contrat entre l'éditeur et l'hôte de preview :

```java
package jo.codeeditor.view;

public interface EditorPreviewHost {
    /** L'hôte peut-il previewer ce fichier ? (.xml, .md, .html) */
    boolean canPreview(String fileName);

    /** Le mode preview a changé (NONE/SPLIT/FULL). */
    void onPreviewModeChanged(EditorView.PreviewMode mode,
                              int previewLeft, int previewWidth);

    /** Le texte de l'éditeur a changé (debounced 200ms). */
    void onPreviewContentChanged(CharSequence text);

    /** Dessine la preview sur le Canvas (appelé par EditorRenderer). */
    void drawPreview(Canvas canvas, float offsetX, float offsetY);

    /** La preview a-t-elle du contenu prêt à dessiner ? */
    boolean hasPreviewContent();

    /** Hit-test un tap dans la preview (sélection de vue). */
    boolean hitTestPreview(float x, float y);
}
```

## Workflow

### Dans l'app demo (editor/app)

```java
// 1. Au onCreate :
EditorView editorView = findViewById(R.id.editor_view);
editorView.setPreviewHost(new XmlPreviewHost(this, editorView));

// 2. Quand l'utilisateur change de fichier :
editorView.setFileName("layout.xml");
// → EditorView appelle previewHost.canPreview("layout.xml")
// → Si true, dessine les icônes preview dans le coin top-right

// 3. L'utilisateur tap une icône preview :
// → EditorInputHandler détecte le tap
// → EditorView.setPreviewMode(SPLIT ou FULL)
// → EditorView appelle previewHost.onPreviewModeChanged(mode, left, width)
// → EditorView appelle previewHost.onPreviewContentChanged(text)
// → XmlPreviewHost appelle nativeRenderer.updatePreviewAsync(xml, w, h, callback)
// → Le callback appelle editorView.invalidate() → redraw

// 4. L'utilisateur édite le XML :
// → EditorView.notifyTextChanged()
// → Schedule previewUpdateTask (debounce 200ms)
// → previewHost.onPreviewContentChanged(text)
// → Re-inflation → invalidate → redraw

// 5. À chaque frame :
// → EditorRenderer.onDraw()
// → Si isXmlPreviewActive() : drawXmlPreview(canvas)
// → host.drawPreview(canvas, previewLeft, 0)
// → XmlPreviewHost dessine via nativeRenderer.draw(canvas, offsetX, 0)
```

### Dans l'app LayoutEditor

```java
// Pas d'EditorView — on utilise un EditText + PreviewSurfaceView custom.
// Le flux est direct : EditText → TextWatcher → debounce →
// nativeRenderer.updatePreviewAsync(xml, w, h, () -> surface.invalidate())
```

## Bug fix critique v3.31.0

**Problème** : la preview XML ne s'affichait pas (écran vide).

**Cause racine** : `NativeXmlPreviewRenderer.updatePreviewAsync(xml, w, h, null)`
était appelée avec un callback `null`. L'inflation se faisait en arrière-plan
mais aucun `invalidate()` n'était déclenché à la fin.

**Fix** :
1. `XmlPreviewHost.onPreviewContentChanged()` passe un callback non-null :
   ```java
   nativeRenderer.updatePreviewAsync(xml, w, h, () -> editorView.invalidate());
   ```
2. Dans `LayoutEditorApp.MainActivity.updatePreview()` :
   ```java
   renderer.updatePreviewAsync(xml, w, h, () -> previewSurface.invalidate());
   ```

## Avantages du découplage

1. **Modularité** — la librairie éditeur peut être utilisée sans preview
2. **Évolutivité** — les modules preview peuvent évoluer sans casser l'éditeur
3. **Testabilité** — l'éditeur se teste sans dépendances Android preview
4. **Réutilisabilité** — l'app LayoutEditor réutilise les modules preview
   sans embarquer la librairie éditeur complète
5. **Taille** — une app qui n'a besoin que de preview (LayoutEditor) ne paie
   pas le coût de l'éditeur (5.5 Mo vs 8.5 Mo)

## Configuration de build

### JDK
- **JDK 17** (Temurin 17.0.13+11) — obligatoire pour AGP 8.2.0 + JdkImageTransform
- Chemin : `/home/z/my-project/tools/jdk-17`
- `JAVA_HOME=/home/z/my-project/tools/jdk-17`

### Android SDK
- **API 34** (Android 14)
- **Build-tools 34.0.0**
- **Platform-tools 37.0.1**
- Chemin : `/home/z/my-project/tools/android-sdk`
- `ANDROID_HOME=/home/z/my-project/tools/android-sdk`

### Gradle
- **Gradle 8.5** (via wrapper)
- `org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g`
- `org.gradle.daemon=false` (évite les fuites mémoire en CI)

### Build command

```bash
cd /home/z/my-project/code-editor-lib/code-editor-lib
export JAVA_HOME=/home/z/my-project/tools/jdk-17
export ANDROID_HOME=/home/z/my-project/tools/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Build des 2 APKs
./gradlew :app:assembleDebug :layout-editor-app:assembleDebug --no-daemon

# Tests
./gradlew test --no-daemon
```

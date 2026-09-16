# Usage Guide

A complete guide to integrating `code-editor-lib` into your Android project.

## Installation

### Gradle

```kotlin
// settings.gradle.kts
include(":codeeditor-lib")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":codeeditor-lib"))
}
```

Or, once published to a Maven repository:

```kotlin
dependencies {
    implementation("jo.codeeditor:code-editor-lib:1.0.8")
}
```

### Requirements

- **minSdk 24** (Android 7.0)
- **compileSdk 34**
- **JDK 17** for the build toolchain (AGP 8.2 requirement)

## Quick start

### 1. Add the EditorView to your layout

```java
import jo.codeeditor.view.EditorView;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.document.EditorDocument;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EditorView editorView = new EditorView(this);
        setContentView(editorView);

        // Create a session with initial text and plug it into the view.
        EditorSession session = new EditorSession(EditorDocument.of("Hello, world!"));
        editorView.setSession(session);
    }
}
```

That's it — the editor is now fully functional: tap to focus, type with the
IME, scroll, pinch-zoom, undo/redo (Ctrl+Z / Ctrl+Y), etc.

### 2. Set the language

The syntax highlighter recognizes `java`, `kotlin`, `xml`, and `markdown`:

```java
session.setLanguage("java");
```

### 3. Read the text

```java
String text = session.getText();
```

### 4. Listen for selection changes

```java
editorView.setOnSelectionChangedListener((line, col, isCursor) -> {
    Log.d("Editor", "Caret at line " + line + ", col " + col);
});
```

## Configuration

### Themes

```java
import jo.codeeditor.view.EditorTheme;

// Built-in dark theme (VS Code Dark+)
editorView.setTheme(EditorTheme.dark());

// Built-in light theme
editorView.setTheme(EditorTheme.light());

// Custom theme
EditorTheme custom = new EditorTheme(
    /* editorBg = */ 0xFF1E1E1E,
    /* gutterBg = */ 0xFF1E1E1E,
    /* gutterText = */ 0xFF808080,
    /* gutterBorder = */ 0xFF323232,
    /* caret = */ 0xFFDCDCDC,
    /* selection = */ 0xFF264F78,
    /* currentLine = */ 0xFF262626,
    /* error = */ 0xFFFF0000,
    /* warning = */ 0xFFFFCC00,
    /* info = */ 0xFF0078D7,
    /* keyword = */ 0xFF569CD6,
    /* string = */ 0xFFCE9178,
    /* comment = */ 0xFF6A9955,
    /* number = */ 0xFFB5CEA8,
    /* annotation = */ 0xFFD7BA7D,
    /* func = */ 0xFFDCDCAA,
    /* type = */ 0xFF4EC9B0,
    /* punct = */ 0xFFD4D4D4,
    /* findMatch = */ 0xFF6133154,
    /* findCurrent = */ 0xFF6B6B2A,
    /* occurrence = */ 0xFF57572C,
    /* indentGuide = */ 0xFF404040,
    /* composing = */ 0xFF555555,
    /* textColor = */ 0xFFD4D4D4
);
editorView.setTheme(custom);
```

### Font size / zoom

```java
// Set the font scale directly (1.0 = 14sp default, clamped to [0.6, 2.6]).
editorView.setFontScale(1.5f);

// Or use Ctrl+Plus / Ctrl+Minus / Ctrl+0 (built-in keyboard shortcuts).
```

### Word wrap

```java
editorView.setWordWrap(true);  // enable
editorView.setWordWrap(false); // disable (default)
```

### Diagnostics (errors / warnings)

```java
import jo.codeeditor.shift.DiagnosticShift;
import java.util.Arrays;
import java.util.List;

List<DiagnosticShift.Diagnostic> diags = Arrays.asList(
    new DiagnosticShift.Diagnostic(5, 10, 3, "Syntax error", "E001"),  // severity 3 = error
    new DiagnosticShift.Diagnostic(20, 25, 2, "Unused variable", "W001") // severity 2 = warning
);
session.setDiagnostics(diags);
editorView.invalidate(); // trigger a redraw to show the squiggles
```

### Fold regions

```java
List<DiagnosticShift.FoldRegion> folds = Arrays.asList(
    new DiagnosticShift.FoldRegion(10, 50, "{...}", "region", false) // start, end, placeholder, kind, collapsed
);
session.setFoldRegions(folds);
editorView.invalidate();
```

### Inlay hints

```java
List<DiagnosticShift.InlayHint> hints = Arrays.asList(
    new DiagnosticShift.InlayHint(15, ": String") // offset, text
);
session.setInlayHints(hints);
editorView.invalidate();
```

### Semantic tokens (LSP-style)

```java
List<DiagnosticShift.SemanticToken> tokens = Arrays.asList(
    new DiagnosticShift.SemanticToken(5, 10, 9) // start, length, type (9 = method)
);
session.setSemanticTokens(tokens);
editorView.invalidate();
```

Semantic token types: 0=namespace, 1=type, 2=class, 3=enum, 4=interface,
5=struct, 6=parameter, 7=variable, 8=property, 9=method, 10=function,
11=keyword, 12=number, 13=string, 14=comment.

## Resolvers (plug in your own language server)

### Completion

```java
editorView.setCompletionProvider((text, caret, tokenStart, prefix) -> {
    // Query your language server here.
    List<CompletionSession.Item> items = myLanguageServer.getCompletions(text, caret);
    return items;
});
```

### Signature help (Ctrl+P)

```java
editorView.setSignatureHelpResolver((text, caret) -> {
    // Return signature help at the caret, or null.
    return myLanguageServer.getSignatureHelp(text, caret);
});
```

Without a resolver, the popup falls back to a synthetic
`"functionName(…) param N"` hint derived from local call-context scanning.

### Quick doc (F1 / hover)

```java
editorView.setQuickDocResolver((text, offset) -> {
    // Return the raw Javadoc/KDoc text for the symbol at offset, or null.
    return myLanguageServer.getDocComment(text, offset);
});
```

### Code actions (Ctrl+.)

```java
editorView.setCodeActionsResolver((text, line) -> {
    List<EditorView.CodeAction> actions = new ArrayList<>();
    actions.add(new EditorView.CodeAction(
        "Add missing import", "quickfix",
        () -> session.commitText("import foo.Bar;\n")
    ));
    return actions;
});
```

### Go-to-symbol (Ctrl+Shift+O)

```java
editorView.setSymbolResolver((text) -> {
    // Return ALL symbols in the document — the view filters on every keystroke.
    return myLanguageServer.getDocumentSymbols(text);
});
```

## Keyboard shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+Z` / `Ctrl+Y` | Undo / Redo |
| `Ctrl+A` | Select all |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` | Copy / Cut / Paste |
| `Ctrl+D` | Duplicate selection |
| `Ctrl+F` | Open find bar (host must implement `OnFindRequestedListener`) |
| `Ctrl+S` | Save (host must implement `OnSaveRequestedListener`) |
| `Ctrl+Space` | Trigger completion |
| `Ctrl+P` | Signature help |
| `Ctrl+.` | Code actions at caret |
| `Ctrl+Shift+O` | Go-to-symbol |
| `Ctrl+G` | Go-to-line |
| `Ctrl+Plus` / `Ctrl+Minus` / `Ctrl+0` | Zoom in / out / reset |
| `F1` | Quick doc |
| `F2` | Rename |
| `Esc` | Dismiss popup |

## ProGuard / R8 rules

The library is consumed in debug builds without R8 minification. If you
enable R8 in release builds, add these rules to your `proguard-rules.pro`:

```proguard
# code-editor-lib — keep the public API surface.
-keep public class jo.codeeditor.view.EditorView { *; }
-keep public class jo.codeeditor.view.EditorTheme { *; }
-keep public class jo.codeeditor.view.EditorMetrics { *; }
-keep public class jo.codeeditor.session.EditorSession { *; }
-keep public class jo.codeeditor.document.EditorDocument { *; }
-keep public class jo.codeeditor.document.Selection { *; }
-keep public class jo.codeeditor.shift.DiagnosticShift$* { *; }
-keep public class jo.codeeditor.completion.CompletionSession$Item { *; }
-keep public class jo.codeeditor.navigation.NavigationMenu$Symbol { *; }
-keep public class jo.codeeditor.cache.LineRenderCache$* { *; }

# The library uses reflection-free Canvas rendering — no additional
# keep rules are needed for the View layer.
```

## Architecture

See [`README.md`](README.md) for the layered architecture diagram and the
list of modules. The key principle: **the engine is pure Java** (no
`android.*` dependency), only the View layer depends on Android.

## Troubleshooting

### The keyboard doesn't appear when I tap

The keyboard only appears after an **explicit tap** in the text area (not on
focus alone). This is by design — opening a file or switching tabs must not
pop the IME. If you need to show the keyboard programmatically:

```java
editorView.showSoftKeyboard();
```

### The caret disappears after undo

Fixed in v1.0.8 — `EditorSession.undo()`/`redo()` now notify the
`ImeListener`, which restarts the caret blink. If you're calling
`session.undo()` from custom code, make sure to also call
`editorView.onTextChanged()` (or use the `Ctrl+Z` shortcut which does it
for you).

### Tapping a completion suggestion does nothing

Fixed in v1.0.8 — the completion popup now has a hit-test that routes taps
to `completionAccept()`. Make sure you're on v1.0.8 or later.

### The fold chevron overlaps the line number

Fixed in v1.0.8 — the gutter width now includes a dedicated fold-strip
column. Make sure you're on v1.0.8 or later.

## Migration

### v1.0.7 → v1.0.8

No breaking changes. Just bump the version and rebuild.

### v1.0.6 → v1.0.7

New optional resolvers: `setSignatureHelpResolver`,
`setQuickDocResolver`, `setCodeActionsResolver`, `setSymbolResolver`. All
are opt-in — if you don't set them, the editor falls back to built-in
behavior (synthetic signature hint, keyword completion, no quick doc, no
code actions, no go-to-symbol).

### v1.0.5 → v1.0.6

New `setWordWrap(boolean)` API. The wrap model is built lazily on first
enable — no action needed.

### v1.0.4 → v1.0.5

New completion popup + find highlights + inlay hints + semantic tokens.
All opt-in via the existing `setCompletionProvider`,
`setFindHighlights`, `setInlayHints`, `setSemanticTokens` APIs.

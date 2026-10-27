# Guide d'utilisation

Guide complet d'intégration de la bibliothèque dans une application Android.

## Installation

### Via GitHub Packages (méthode principale)

```properties
# ~/.gradle/gradle.properties
gpr.user=<votre-utilisateur-github>
gpr.key=<votre-jeton-d-acces>   # droit read:packages
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jjoblab/code-editor")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    // Le module UI embarque transitivement cel-core et cel-lsp-api.
    implementation("jo.codeeditor:cel-ui:4.9.0")

    // Optionnel — intégration Language Server Protocol (LSP4J).
    implementation("jo.codeeditor:cel-lsp:4.9.0")

    // Optionnel — voie grammaires (ADR-002 C3) : coloration par
    // grammaires TextMate-like + grammaires embarquées (C#).
    implementation("jo.codeeditor:cel-textmate:4.9.0")
}
```

### Via JitPack (alternative de secours)

```kotlin
// settings.gradle.kts — dépôt supplémentaire
maven { url = uri("https://jitpack.io") }

// app/build.gradle.kts
dependencies {
    implementation("com.github.jjoblab.code-editor:cel-ui:v4.9.0")
}
```

### Via les modules locaux

```kotlin
// settings.gradle.kts
include(":cel-core", ":cel-lsp-api", ":cel-lsp", ":cel-ui")
project(":cel-core").projectDir = file("code-editor/editor/cel-core")
project(":cel-lsp-api").projectDir = file("code-editor/editor/cel-lsp-api")
project(":cel-lsp").projectDir = file("code-editor/editor/cel-lsp")
project(":cel-ui").projectDir = file("code-editor/editor/cel-ui")

// app/build.gradle.kts
dependencies {
    implementation(project(":cel-ui"))
}
```

## Prérequis

- **minSdk 24** (Android 7.0)
- **compileSdk 34**
- **JDK 17 minimum** pour la toolchain de build (AGP 9)

## Démarrage rapide

### 1. Ajouter l'EditorView

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

        // Créer une session avec le texte initial et la brancher sur la vue.
        EditorSession session = new EditorSession(EditorDocument.of("Hello, world!"));
        editorView.setSession(session);
    }
}
```

C'est tout — l'éditeur est fonctionnel : tap pour le focus, frappe IME,
scroll, pinch-zoom, undo/redo (Ctrl+Z / Ctrl+Y), etc.

### 2. Définir le langage

Le colorateur syntaxique reconnaît 29 langages intégrés (java, kotlin,
javascript, typescript, c, cpp, go, rust, swift, dart, php, ruby, scala,
groovy, csharp (grammaire-only — coloré par la voie grammaires, repli
C_LIKE sans `cel-textmate`), python, lua, xml/html, css, json, yaml,
sql, shell, properties, toml, smali, log, markdown, text) :

```java
session.setLanguage("java");
```

### 3. Lire le texte

```java
String text = session.getText();
```

### 4. Écouter les changements de sélection

```java
editorView.setOnSelectionChangedListener((line, col, isCursor) -> {
    Log.d("Editor", "Caret ligne " + line + ", col " + col);
});
```

### 5. Libérer la session

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    session.dispose(); // libère le thread de restyle asynchrone
}
```

## Configuration

### Thèmes

```java
import jo.codeeditor.view.chrome.EditorTheme;

// Thème sombre intégré (palette « Dark+ »)
editorView.setTheme(EditorTheme.dark());

// Thème clair intégré
editorView.setTheme(EditorTheme.light());

// Thème personnalisé (31 couleurs ; les couleurs « verre » des popups et
// le vert de log SUCCESS sont dérivées automatiquement du fond)
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
    /* operator = */ 0xFFD4D4D4,
    /* escape = */ 0xFFCE9178,
    /* label = */ 0xFFC8C8C8,
    /* property = */ 0xFF9CDCFE,
    /* variable = */ 0xFF9CDCFE,
    /* constant = */ 0xFF4FC1FF,
    /* regexp = */ 0xFFD16969,
    /* findMatch = */ 0xFF613315,
    /* findCurrent = */ 0xFF6B6B2A,
    /* occurrence = */ 0xFF57572C,
    /* indentGuide = */ 0xFF404040,
    /* composing = */ 0xFF555555,
    /* textColor = */ 0xFFD4D4D4
);
editorView.setTheme(custom);
```

### Taille de police / zoom

```java
// Définir l'échelle de police (1.0 = 14sp par défaut, bornée à [0.6, 2.6]).
editorView.setFontScale(1.5f);

// Ou Ctrl+Plus / Ctrl+Minus / Ctrl+0 (raccourcis intégrés).
```

### Retour à la ligne, minimap et autres bascules

```java
editorView.setWordWrap(true);              // retour à la ligne (défaut : false)
editorView.setMinimapEnabled(true);        // bande minimap latérale
editorView.setShowNonPrintable(true);      // caractères non imprimables
editorView.setFontLigatures(true);         // ligatures de police
editorView.setTouchHoverEnabled(true);     // hover par appui long (500 ms)
editorView.setDiagnosticChipsEnabled(true);// chips de diagnostics dans le gutter
```

### Guides d'indentation (Lot B)

Les guides d'indentation sont les fins traits verticaux tracés dans
les blocs indentés, comme dans les éditeurs de code de bureau. Trois styles sont
disponibles via `IndentGuideStyle` :

```java
import jo.codeeditor.view.IndentGuideStyle;

// Défaut : guides + portée active mise en évidence (bloc contenant
// le caret en couleur d'accent + épaisseur légèrement supérieure).
editorView.setIndentGuideStyle(IndentGuideStyle.INDENT_WITH_SCOPE);

// Guides d'indentation seuls, sans mise en évidence de la portée
// active (tous les guides ont la même couleur de base).
editorView.setIndentGuideStyle(IndentGuideStyle.INDENT);

// Aucun guide (désactivé).
editorView.setIndentGuideStyle(IndentGuideStyle.OFF);

// Lecture du style courant.
IndentGuideStyle current = editorView.getIndentGuideStyle();
```

L'unité d'indentation (2/4/8 espaces ou tabulation) est détectée
automatiquement à partir du contenu du document via
`EditOps.detectIndentUnit`. La portée active est calculée à partir
de la profondeur d'indentation du caret.

## Diagnostics et décorations

### Diagnostics (erreurs / avertissements)

```java
import jo.codeeditor.shift.DiagnosticShift;
import java.util.Arrays;
import java.util.List;

List<DiagnosticShift.Diagnostic> diags = Arrays.asList(
    new DiagnosticShift.Diagnostic(5, 10, 3, "Syntax error"),   // sévérité 3 = erreur
    new DiagnosticShift.Diagnostic(20, 25, 2, "Unused variable") // sévérité 2 = warning
);
session.setDiagnostics(diags);
editorView.invalidate(); // déclenche un redraw pour afficher les squiggles
```

### Régions de pli

```java
List<DiagnosticShift.FoldRegion> folds = Arrays.asList(
    // start, end, placeholder, kind, collapsed
    new DiagnosticShift.FoldRegion(10, 50, "{...}", "block", false)
);
session.setFoldRegions(folds);
editorView.invalidate();
```

### Inlay hints

```java
List<DiagnosticShift.InlayHint> hints = Arrays.asList(
    // offset, texte, avant le caret ?
    new DiagnosticShift.InlayHint(15, ": String", false)
);
session.setInlayHints(hints);
editorView.invalidate();
```

### Jetons sémantiques (façon LSP)

```java
List<DiagnosticShift.SemanticToken> tokens = Arrays.asList(
    // start, longueur, type (9 = method), modificateurs (4.4.0, optionnel)
    new DiagnosticShift.SemanticToken(5, 10, 9),
    // déprécié (barré), statique (gras), abstrait (italique) — cumulables :
    new DiagnosticShift.SemanticToken(20, 4, 7,
            DiagnosticShift.SemanticToken.MOD_DEPRECATED)
);
session.setSemanticTokens(tokens);
editorView.invalidate();
```

Types de jetons : 0=namespace, 1=type, 2=class, 3=enum, 4=interface,
5=struct, 6=parameter, 7=variable, 8=property, 9=method, 10=function,
11=keyword, 12=number, 13=string, 14=comment.

Modificateurs (4.4.0) : `MOD_DEPRECATED` (barré), `MOD_STATIC` (gras),
`MOD_ABSTRACT` (italique) — cumulables par OU binaire. La teinte de
chaque surcouche est réglable dans le scheme de couleurs
(`semantic.modifier.deprecated` / `.static` / `.abstract` — sans
opinion, la couleur du jeton est conservée). Côté client LSP, les
modificateurs arrivent du 5ᵉ élément du quintuplet, résolus PAR NOM
via la légende `tokenModifiers` annoncée par le serveur.

### Occurrences du mot sous le caret (4.4.0)

```java
// Défaut : activé — toutes les occurrences ENTIÈRES du mot touché par
// le caret s'allument au repos de frappe (~300 ms) dès qu'il y en a
// deux, l'occurrence du caret en teinte forte.
editorView.setWordOccurrencesEnabled(false); // couper
boolean on = editorView.isWordOccurrencesEnabled();
```

La session de recherche OUVERTE garde toujours la priorité sur cette
surbrillance (elle se ré-allume à sa fermeture au prochain déplacement
du caret ou à la prochaine édition), de même que les surlignages posés
manuellement par l'hôte via `setFindHighlights`. Les très gros
documents (`isLarge`) et les sélections en plage coupent le calcul ;
les littéraux numériques et la poncturbation ne s'allument jamais.

### Apprentissage du classement de complétion (4.5.0)

```java
// Défaut : activé — chaque candidat accepté est mémorisé par
// (langage, identifiant de tête) et remonte à PALIER ET GROUPE ÉGAUX
// aux prochains classements (fréquence puis fraîcheur) — jamais
// au-delà des barrières, l'ordre du fournisseur est sinon conservé.
editorView.setCompletionRankingLearningEnabled(false); // couper
boolean on = editorView.isCompletionRankingLearningEnabled();
editorView.clearCompletionRankingHistory(); // purge (réglage hôte)
```

L'historique vit en mémoire (200 identifiants par langage, éviction du
score le plus faible, compartiments par langage étanches). La purge
ramène immédiatement le classement à celui du fournisseur.

### Persistance de l'apprentissage (4.6.0)

L'historique survit aux morts de processus : photographiez-le quand
l'activité quitte le premier plan, restaurez-le au démarrage.

```java
// Dans onStop() (pas onDestroy — non garanti) :
CompletionAcceptanceHistory.Snapshot photo =
        editorView.completionHistory.snapshot();
// Structure PLATE, sérialisable en JSON tel quel :
// {langage → {identifiant → [fréquence, séquence]}}
sauvegarder(photo.entries());          // JSON, DataStore, fichier…

// Au démarrage suivant :
Map<String, Map<String, long[]>> data = relire();
editorView.completionHistory.restore(
        new CompletionAcceptanceHistory.Snapshot(
                data, CompletionAcceptanceHistory.Snapshot.FORMAT_VERSION));
```

Garde-fous : le format est versionné (`FORMAT_VERSION = 1` — un
snapshot d'une version inconnue est refusé silencieusement), les
entrées malformées sont ignorées une à une, l'invariant capacitaire
est rétabli dès le restore, et le compteur de fraîcheur est REBASSÉ —
une acceptation toute fraîche ne peut jamais paraître plus vieille
qu'une entrée restaurée. La bascule ci-dessus n'interdit ni
photographie ni restore : elle coupe la collecte et le boost, pas le
droit de sauvegarder des données.

### Télémétrie de l'apprentissage (4.6.0)

```java
long acceptées  = editorView.completionHistory.recordedAcceptanceCount();
long boostées   = editorView.completionHistory.boostedAcceptanceCount();
// rendement = boostées / acceptées — les candidats que le boost a
// fait remonter et qui ont été acceptés. Mesuré AVANT d'enrichir
// l'apprentissage (fuzzy récent, boost par type de symbole…).
```

Compteurs cumulatifs de session : la purge TOTALE les remet à zéro,
la purge PAR LANGAGE les épargne ; ils ne voyagent pas dans le
snapshot (une instance restaurée repart à zéro côté mesure).

### Résolution à la demande de la documentation (4.7.0)

Automatique : quand le serveur LSP annonce `resolveProvider` et sert
un candidat SANS documentation, le popup en fait la demande
(`completionItem/resolve`) après un séjour de 200 ms de la sélection —
jamais pendant le mouvement, jamais au rendu. La documentation arrive
dans le panneau sans bouger l'ancre ; un cache client re-sert les
lots suivants sans nouvelle requête.

Un hôte avec son PROPRE moteur de langage implémente le SPI pour la
même expérience :

```java
view.setLanguage(new Language() {
    // ...
    @Override
    public CompletionProvider getCompletionProvider() {
        return new CompletionProvider() {
            // complete(...) sert la liste SANS documentation…
            @Override
            public void resolveDocumentation(String label,
                    Consumer<String> callback) {
                // …et elle ne vient qu'ici, à la demande. Peut répondre
                // depuis n'importe quel thread ; null = rien à résoudre.
                callback.accept(documentationDe(label));
            }
        };
    }
});
```

### Plancher local de complétion fusionné (4.8.0)

Brancher un fournisseur — hôte ou LSP — ne RETIRE plus les mots-clés
et snippets du profil de langage : une réponse NON VIDE est fusionnée
avec la base intégrée, dédupliquée par libellé (le candidat serveur,
plus riche — documentation, détail, container — gagne le conflit),
ordonnée par le classement commun. Un serveur pauvre ne fait donc plus
régresser la complétion par rapport au mode sans serveur. Une réponse
VIDE est respectée : pas de mots-clés hors contexte après un point,
une arobase ou un import.

```java
// Défaut : fusion ACTIVE. Pour servir une liste strictement
// sienne (comportement historique exclusif) :
view.setMergeBuiltinCompletionsEnabled(false);
```

### Aide de signature redessinée (4.8.0)

La bannière au-dessus du caret gagne une largeur adaptative (contenu
mesuré, borné [200, 320] dp), un paramètre actif garanti visible (les
libellés longs sont ellipsés AUTOUR de lui) et un badge de surcharges
« n/m » — la seule surface tactile du popup : le tap qui le vise fait
défiler les surcharges (l'équivalent tactile des flèches Haut/Bas du
clavier), tout autre tap referme la bannière comme avant. La bande de
documentation ouvre sur le paramètre ACTIF (libellé en couleur de
fonction), puis la javadoc de la signature.

Sans résolveur, le repli synthétique affiche l'appel VIVANT du buffer
(vos arguments, l'actif en surbrillance) et le numéro du paramètre —
chaîne `cel_signature_active_param`, surchargeable dans vos ressources
pour la localiser. Ce repli est borné aux langages à syntaxe d'appel :
le drapeau `callSyntax` du profil (défaut calculé depuis la famille —
vrai pour C_LIKE, PYTHON, LUA, SQL ; faux ailleurs) le coupe sur le
texte brut, le XML ou la prose, et borne aussi l'éligibilité postfix.

```java
// Profil maison : déclarer explicitement une syntaxe d'appel
LanguageProfile custom = LanguageProfile.builder("mylang")
        .family(SyntaxFamily.C_LIKE)
        .callSyntax(true)   // défaut déjà vrai pour C_LIKE — exemple
        .build();
```

### Gabarits : variables de choix et macros (4.5.0)

Le format snippet reconnaît deux familles nouvelles — les candidats
`isSnippet` au moment de l'acceptation les expansent :

- « `${1|alpha,beta,gamma|}` » : champ initialisé à la première
  option, carte glass des options au-dessus de la ligne de frappe
  (toucher une option remplace le champ, avance au suivant) ;
- « `$FILE$` », « `$DATE$` », « `$USER$` » : résolues à l'expansion
  depuis le contexte posé par l'hôte — sans contexte, la macro reste
  LITTÉRALE (jamais effacée silencieusement) :

```java
import jo.codeeditor.snippet.SnippetMacroContext;

// L'hôte connaît le fichier et l'auteur ; la date est résolue à
// CHAQUE expansion (fournisseur injectable pour les tests).
editorView.getSession().snippets.setMacroContext(
        SnippetMacroContext.of("Main.kt", "Jeanne"));
```

Les gabarits arrivent par trois voies : les candidats LSP
(`insertTextFormat=Snippet`), la liste additive du profil
(`LanguageProfile.Builder.snippetTemplates("libellé|corps")` — le
premier « | » coupe, la liste historique `snippets` reste littérale)
et le SPI des gabarits utilisateur :

```java
import jo.codeeditor.lang.model.SnippetTemplate;
import jo.codeeditor.lang.provider.SnippetProvider;

public class MesGabarits implements SnippetProvider {
    @Override
    public List<SnippetTemplate> snippets(String language) {
        // Lu sur le fil UI à chaque complétion : lire un CACHE,
        // pas un stockage lent (préférences/DB → cache mémoire).
        return List.of(new SnippetTemplate("log",
                "System.out.println(${1|msg,err|});", "gabarit utilisateur"));
    }
}
// branché par Language#getSnippetProvider() (défaut null) ; fusionné
// aux candidats du serveur COMME à la complétion intégrée.
```

### Table postfix par profil (4.5.0)

```java
LanguageProfile.builder("java")
        .family(SyntaxFamily.C_LIKE)
        // Complète et surcharge les défauts de la famille ; un
        // gabarit VIDE retire le nom ; un langage hors famille qui
        // déclare une table active les postfixes.
        .postfixTemplates(
                "sout=System.out.println(${expr});",   // .sout intégré à java
                "syserr=System.err.println(${expr});") // extension hôte
        .build();
```

Le jeton `${expr}` reçoit l'expression extraite avant le point ; les
lignes de continuation sont ré-indentées de l'indentation courante ;
le caret se pose juste après la première ligne. Détection au PLUS LONG
postfixe.

### Renommage lié des balises XML (4.5.0)

Aucune configuration obligatoire : la frappe et le backspace DANS le
nom d'une balise ouvrante (famille XML du profil — XML, HTML, SVG via
alias) se reflètent automatiquement sur la fermante appariée, en
miroir aligné, les deux éditions en UNE étape d'annulation.
L'appariement empile par nom (homonymes imbriqués respectés), ignore
les auto-fermantes et saute les commentaires/CDATA/PI. Éditer la
fermante ne touche pas l'ouvrante.

```java
// 4.6.0 : le point d'arrêt d'un hôte qui poserait son PROPRE miroir
// de renommage (sinon les deux miroirs doubleraient chaque édition).
editorView.setLinkedTagEditsEnabled(false); // éditions ordinaires
boolean lié = editorView.isLinkedTagEditsEnabled(); // défaut : vrai
```

### Mode gros fichiers explicite (4.5.0)

```java
// Tri-état : AUTO (défaut — seuils 2,5 M caractères / 50 000 lignes),
// ON (force les coupures), OFF (les ferme même au-delà des seuils).
editorView.setLargeFileMode(EditorView.LargeFileMode.ON);
EditorView.LargeFileMode mode = editorView.getLargeFileMode();
boolean actif = editorView.isLargeFileModeActive();
```

En mode actif : complétion auto-déclenchée coupée (la voie explicite
`setCompletionItems` / `refreshCompletionFromHost` reste ouverte),
occurrences purgées, inlays et tir sémantique arrêtés. Coloration
(régime progressif), frappe et undo/redo continuent toujours.

### Barre de touches de code (4.5.0)

```java
import jo.codeeditor.view.chrome.EditorTouchBar;

EditorTouchBar touchBar = new EditorTouchBar(context);
touchBar.bind(editorView); // Tab, commentaire, ↑, ↓, duplication
// Vos actions (le modèle de la pill de sélection) :
touchBar.setActions(List.of(
        new SelectionAction("mon.action", "⚡", () -> faireQuelqueChose()),
        BuiltinSelectionActions.duplicate(editorView)));
// Ajoutez la barre à votre layout, juste au-dessus du clavier
// (l'adjustResize de l'activité la remonte avec l'IME).
```

La barre ne prend jamais le focus (l'IME reste ouvert), suit le thème
de l'éditeur et cohabite avec la barre de symboles. Les états activés
(Commenter selon le langage, ↑/↓ selon la position) se rafraîchissent
au déplacement du caret et par `refreshEnabled()`.

### Surlignages de recherche

```java
import jo.codeeditor.find.FindReplace;
import jo.codeeditor.find.FindOptions;
import jo.codeeditor.find.Match;

List<Match> matches = FindReplace.findMatches(
        session.getText(), "query",
        new FindOptions(/* caseSensitive = */ true, /* wholeWord = */ false, /* regex = */ false));
editorView.setFindHighlights(matches, 0); // 0 = index du match courant
```

## Résolveurs — brancher son propre moteur de langage

Tous les résolveurs sont optionnels : sans eux, l'éditeur retombe sur son
comportement intégré (complétion par mots-clés, hint de signature synthétique,
pas de quick doc…).

### Complétion

```java
editorView.setCompletionProvider((text, caret, tokenStart, prefix) -> {
    // Interrogez votre language server ici.
    List<CompletionSession.Item> items = myLanguageServer.getCompletions(text, caret);
    return items;
});
```

Un fournisseur dont le backend **plafonne** ses réponses peut signaler
la troncature (4.3.0) — le popup re-requêtera à chaque édition du
préfixe (prolongement comme retour arrière, requêtes coalescées et
débouncées) au lieu de filtrer localement une liste amputée :

```java
editorView.setCompletionProvider(new EditorView.CompletionProvider() {
    @Override
    public List<CompletionSession.Item> provide(
            String text, int caret, int tokenStart, String prefix) {
        return myLanguageServer.getCompletions(text, caret);
    }

    @Override
    public CompletionResult provideResult(
            String text, int caret, int tokenStart, String prefix,
            Character triggerCharacter) {
        List<CompletionSession.Item> items =
                myLanguageServer.getCompletions(text, caret);
        return new CompletionResult(items, myLanguageServer.isLastListTruncated());
    }
});
```

Réglages associés : `setHonorIncompleteCompletion(boolean)` (défaut
`true` ; `false` restaure le filtrage local pur — une seule requête
par token) et `setCompletionDebounceMs(int)` (défaut 80 ms ; les
caractères déclencheurs comme `.` et Ctrl+Espace le court-circuitent
toujours). Les candidats peuvent aussi porter une origine
(`container`, affichée à droite) et une `documentation` — champs
optionnels du constructeur étendu de `CompletionSession.Item`.

### Aide de signature (Ctrl+P)

```java
editorView.setSignatureHelpResolver((text, caret) -> {
    // Retourne l'aide de signature au caret, ou null.
    return myLanguageServer.getSignatureHelp(text, caret);
});
```

### Quick doc (F1 / hover)

```java
editorView.setQuickDocResolver((text, offset) -> {
    // Retourne le texte Javadoc/KDoc brut du symbole à offset, ou null.
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

### Aller-au-symbole (Ctrl+Shift+O)

```java
editorView.setSymbolResolver((text) -> {
    // Retourne TOUS les symboles du document — la vue filtre à chaque frappe.
    return myLanguageServer.getDocumentSymbols(text);
});
```

### Autres résolveurs

`setDefinitionResolver`, `setTypeDefinitionResolver`,
`setImplementationsResolver`, `setSuperResolver`, `setReferencesResolver`,
`setRenameResolver`, `setFormatterResolver`,
`setDocumentHighlightResolver` — même principe : la vue appelle le résolveur
à la commande clavier correspondante et affiche le résultat dans le popup
adapté.

## Personnaliser la barre de sélection (pill)

La pill flottante (appui long / sélection) offre Cut / Copy / Paste /
Select all + les icônes Docs ℹ (quick doc) et Actions ⋯ (menu contextuel
unifié). L'hôte y injecte ses propres actions contextuelles :

```java
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.SelectionAction;
import jo.codeeditor.view.BuiltinSelectionActions;
import jo.codeeditor.view.icons.EditorIcons;

// 1. Actions intégrées prêtes à l'emploi (chacune porte son icône).
editorView.addSelectionAction(BuiltinSelectionActions.duplicate(editorView));
editorView.addSelectionAction(BuiltinSelectionActions.toggleComment(editorView));
editorView.addSelectionAction(BuiltinSelectionActions.convertCase(
        editorView, BuiltinSelectionActions.CaseMode.TOGGLE));
editorView.addSelectionAction(BuiltinSelectionActions.share(editorView));
editorView.addSelectionAction(BuiltinSelectionActions.moveLinesUp(editorView));
editorView.addSelectionAction(BuiltinSelectionActions.moveLinesDown(editorView));
editorView.addSelectionAction(BuiltinSelectionActions.blockDrag(editorView));

// 2. Action d'hôte AVEC icône (celle de sa rangée dans le menu « ⋯ »).
editorView.addSelectionAction(new SelectionAction(
        "my.breakpoint", "Point d'arrêt",
        () -> toggleBreakpoint(),
        () -> session != null,
        EditorIcons.Icon.BREAKPOINT));

// Retrait ciblé (id stable), ordre d'injection conservé.
editorView.removeSelectionAction("my.breakpoint");
```

Règles : les actions n'apparaissent que pour une vraie sélection (non
réduite au caret) ; ce qui ne tient pas dans la largeur de la pill
bascule dans le menu « ⋯ » (section « MORE » du menu contextuel unifié) ;
`icon` (B16) est l'icône vectorielle de la rangée de débordement — sans
elle, la rangée retombe sur l'ampoule générique ; un tap exécute
`perform` puis referme la pill ; `enabled` à `false` estompe l'item et
rejette le tap. Depuis B25, la largeur du menu « ⋯ » suit son contenu
(bornée 168–320 dp) : un débordement d'actions courtes n'occupe plus
tout l'écran.

## IME : personnalisation et politique du clavier (plan 09)

L'intégration IME de la bibliothèque est complète (capitales réelles,
instantané API 33 pour survivre à une mort de processus, stylet API 34,
fenêtre d'extraction honnête, écart minimal de composition). Deux hooks
donnent le dernier mot à l'application hôte.

### Personnaliser l'EditorInfo (R9)

L'écouteur est appelé à chaque construction de connexion, APRÈS les
valeurs par défaut de la bibliothèque (inputType « verrou code »,
imeOptions, sélection initiale, capitales réelles au point d'insertion) :

```java
editor.setOnEditorInfoListener(info -> {
    // Le clavier n'apprend pas de la frappe de code.
    info.imeOptions |= androidx.core.view.inputmethod.EditorInfoCompat
            .IME_FLAG_NO_PERSONALIZED_LEARNING;
    // Action d'entrée personnalisée (barre du clavier).
    info.actionLabel = "Compiler";
    info.actionId = 42;
});
```

Sans écouteur, l'`EditorInfo` reste exactement celui de la bibliothèque.

### Répondre aux commandes privées des claviers (R9)

Certaines IME propriétaires étendent le protocole via
`InputConnection.performPrivateCommand(action, data)`. Sans hook, ces
commandes tombaient dans le vide ; l'écouteur leur donne un canal :

```java
editor.setOnImePrivateCommandListener((action, data) -> {
    if ("com.clavier.LIVE_TRANSLATE".equals(action)) {
        appliquerTraduction(data);
        return true;   // traitée
    }
    return false;      // inconnue → l'IME voit « ignorée »
});
```

### Clavier logiciel : lecture seule et clavier matériel (R8)

Le clavier logiciel suit une politique unifiée (tap, appui long,
`showSoftKeyboard()` programme, refocus) :

- en **lecture seule** (`session.setReadOnly(true)`), la vue n'est plus
  un champ d'édition pour le système (`onCheckIsTextEditor` répond
  faux) : le tap place le caret — la lecture reste navigable — mais ne
  fait plus monter un clavier dont les frappes seraient silencieusement
  ignorées ;
- avec un **clavier matériel exposé** (connecté et sorti,
  `Configuration.keyboard` + `hardKeyboardHidden`), le tap ne fait pas
  surgir le clavier logiciel — l'utilisateur tape sur le clavier
  physique ; brancher un clavier en cours de frappe masque
  automatiquement le clavier logiciel.

## Sélecteur de couleur (B26)

Les codes couleur hexadécimaux reçoivent une pastille inline (fond teinté
+ bordure dans la couleur même) ; un tap AU CŒUR d'un code (`#RGB`,
`#RRGGBB`, `#AARRGGBB`) ouvre une carte de verre ancrée : champ
saturation/valeur, bande de teinte et palette de teintes courantes. Le
pick remplace le code tapé en conservant son format d'origine (3, 6 ou 8
chiffres) et son alpha d'origine — la mutation passe par le pipeline
unique, donc annulable d'un undo.

```java
// Activer/désactiver (défaut : actif).
editorView.setColorPickerEnabled(false); // le tap replace le caret

// Les pastilles inline ont leur propre bascule (défaut : actives).
editorView.setColorSwatchesEnabled(true);

// Fermeture programmatique (sans rien appliquer).
editorView.dismissColorPicker();
```

Contrat : press = saut immédiat dans le champ/la bande, drag = mise à
jour vivante de l'aperçu, release = application ; un tap sur une teinte
de la palette s'applique directement ; un tap hors carte, une édition ou
la perte de focus referment sans rien appliquer ; les bords ±1 caractère
du code restent des placements de caret (comme le popup ancré de
diagnostic).

## Coloration par grammaires — module optionnel `:cel-textmate` (ADR-002)

La voie grammaires ajoute la coloration « TextMate-like » PAR FICHIER de
grammaire déclarative, sans écrire de tokéniseur Java. Le module ne dépend
que de `:cel-core` : l'hôte qui ne l'embarque pas garde le tokéniseur intégré
(repli hors-ligne) — le chemin est strictement additif.

```java
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.textmate.TextMateTokenizerImpl;

// Une seule fois au démarrage, AVANT toute vue éditeur :
SyntaxHighlighter.setTextMateTokenizer(TextMateTokenizerImpl.create());
```

Les grammaires embarquées du dépôt se chargent paresseusement (classpath
`textmate/*.tmg.json` — C# aujourd'hui : langage « grammaire-only », aucun
tokéniseur intégré dédié). Neuf grammaires tierces de l'écosystème sont
aussi embarquées (css, html, java, javascript, kotlin, lua, markdown,
python, xml — attribution dans `NOTICE`) : inactives au démarrage, un
hôte les active par `tm.loadEmbeddedGrammar("java")` (opt-in par
langage, idempotent). Pour vos langages :

```java
TextMateTokenizerImpl tm = TextMateTokenizerImpl.create();
tm.registerGrammar("mylang", """
        {
          "scopeName": "source.mylang",
          "patterns": [
            { "match": "#[^\\n]*", "name": "comment.line.mylang" },
            { "begin": "\\"", "end": "\\"", "name": "string.quoted.double.mylang",
              "contentName": "string.quoted.double.mylang" }
          ]
        }
        """);
SyntaxHighlighter.setTextMateTokenizer(tm);
```

Format : règles `match` (portée `name` + `captures` par groupe), blocs
`begin`/`end` (multilignes, `contentName`, backreferences `\1` du `end`),
`include` (`#règle` du repository, `$self`). Les regex doivent être
tables par `java.util.regex` (le sous-ensemble du moteur, documenté dans
la javadoc de `TextMateGrammar`) — une grammaire invalide échoue à
l'ENREGISTREMENT (exception), jamais en cours de coloration.

Les portées standard se mappent sur les `TokenType` existants
(`comment.block.documentation` → `DOC_COMMENT`, `keyword.control` →
`KEYWORD_CONTROL`, `string.quoted.triple` → `RAW_STRING`… voir
`ScopeMapper`) — un thème les colore sans clé par langage. Deux portes
de garde-fou restent décidées hors module : les documents de plus de
800 lignes (`TEXTMATE_LINE_LIMIT`, `EditorSession`) et les LIGNES de
plus de 5 000 caractères (`TEXTMATE_CHAR_LIMIT`, `SyntaxHighlighter`)
retombent sur le tokéniseur intégré au coût linéaire.

## Raccourcis clavier

| Raccourci | Action |
|----------|--------|
| `Ctrl+Z` / `Ctrl+Y` | Annuler / rétablir |
| `Ctrl+A` | Tout sélectionner |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` | Copier / couper / coller |
| `Ctrl+D` | Dupliquer la sélection |
| `Ctrl+F` | Ouvrir la barre de recherche (l'hôte doit implémenter `OnFindRequestedListener`) |
| `Ctrl+S` | Enregistrer (l'hôte doit implémenter `OnSaveRequestedListener`) |
| `Ctrl+Space` | Déclencher la complétion |
| `Ctrl+P` | Aide de signature |
| `Ctrl+.` | Code actions |
| `Ctrl+Shift+O` | Aller au symbole |
| `Ctrl+Shift+I` | Formater le document |
| `Ctrl+Shift+K` | Formater la sélection (3.74.0 — refus propre si le formateur ne gère pas les plages) |
| `Ctrl+Shift+L` | Code actions au caret |
| `F1` | Quick doc |
| `F2` | Renommer |
| `F12` / `Shift+F12` | Aller à la définition / trouver les références |
| `Ctrl+G` | Aller à la ligne |
| `Ctrl+Plus` / `Ctrl+Minus` / `Ctrl+0` | Zoom avant / arrière / reset |
| `Échap` | Fermer le popup / annuler un chord en cours |

### Rebind et chords

```java
// Rebind simple :
editorView.setKeymap(EditorKeymap.defaults()
        .bind(EditorCommands.REDO, KeyEvent.KEYCODE_Z, true, true)); // Ctrl+Shift+Z

// Séquence à deux touches (chord) — pending de 2 s, Échap annule :
editorView.setKeymap(EditorKeymap.defaults()
        .bindChord(EditorCommands.TOGGLE_LINE_COMMENT,
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_K, true, false),
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_C, true, false)));
// Ctrl+K Ctrl+C = commenter (accord de touches consécutif)
```

## Aperçu — feuille, panneau, carte « goutte d'eau »

L'éditeur ne sait PAS prévisualiser : il délègue tout le rendu à
l'application hôte via `EditorPreviewHost`. Trois familles d'aperçu
partagent la même interface — le hôte choisit la sienne par fichier :

| Famille | Mode | Pour quoi | Corps fourni par le hôte |
|---|---|---|---|
| **Feuille flottante** | `SHEET_SPLIT` / `SHEET_FULL` | Markdown/HTML | une **Vue Android** (typiquement un WebView) via `onCreatePreviewView` |
| **Panneau scindé inline** | `SPLIT` / `FULL` | rendu canvas côte à côte | `drawPreview(canvas, offsetX, offsetY)` |
| **Carte « goutte d'eau »** (B27) | `DROP` | aperçu mobile non modal (layout XML, arbre simulé) | `drawDropPreview(canvas, x, y, w, h, tab)` |

```java
editorView.setPreviewHost(new XmlPreviewHost(this, editorView));
editorView.setFileName("layout.xml");
// → EditorView interroge previewHost.canPreview("layout.xml")
// → l'utilisateur tape l'œil de gouttière (ou une icône d'aperçu)
// → selon le type de fichier, la feuille, le panneau ou la carte s'ouvre
```

### La carte « goutte d'eau » (B27) — guide de connexion

La carte est l'alternative mobile au split view : l'aperçu vit dans un
popup ancré à l'**œil de gouttière** (la pill de la rangée du caret),
**non modal** — on peut continuer à taper derrière, la carte **esquive
le bloc de frappe** (caret ± 1 ligne), glisse au-dessus ou en dessous
selon la place, et se **compacte** (échelle bornée à 0,58) si l'écran
est trop petit. Elle ne recouvre **jamais** la ligne où l'utilisateur
tape — l'algorithme est prouvé par balayage de 18 positions dans les
tests.

**Cohabitation avec les popups de frappe** (complétion, doc rapide) —
**trois politiques au choix du propriétaire**, l'ORIGINALE étant le
défaut (`EditorView.PreviewDropPolicy`) :

| Politique | Rendu | Géométrie | Taps |
|---|---|---|---|
| `ORIGINAL` (défaut) | carte au premier plan (au-dessus des popups) | aveugle aux popups (bande de frappe seule — maquette d'origine) | carte d'abord : le corps engloutit, la complétion enterrée reste ouverte |
| `THREE_LAYERS` | carte au premier plan | esquive par obstacles (creux, latéral grand écran, compactage) | complétion d'abord : la surface de frappe gagne toujours |
| `VARIANT_B` | **z-order inversé : la complétion se dessine AU-DESSUS de la carte — la goutte devient FANTÔME à 10–15 % d'opacité PENDANT le chevauchement** (retour terrain n°4 : opaque tant qu'aucun popup ne la recouvre, eau à 12 % dès que la complétion passe devant, normale dès qu'elle referme — retour fluide 180 ms ; la tige/œil restent opaques — l'ancre demeure lisible) | aveugle aux popups | complétion d'abord |

En `THREE_LAYERS`, la carte traite les popups vivants comme des
**obstacles** — elle cherche un creux libre hors de la bande de frappe
ET des popups, se pose **latéralement à droite** de la complétion sur
grand écran (entière, la tige la relie à l'œil), compacte sinon. Si
vraiment rien ne tient (petit écran), elle ne cache plus : elle se
**voile d'eau** — transparence animée (alpha 0,25) tant qu'elle
recouvre une part significative du popup (≥ 10 %), opaque dès que
c'est dégagé, avec hystérésis (la complétion change de hauteur à
chaque frappe, l'état ne tremble pas) ; la tige et l'œil restent
opaques. Et la **priorité des taps appartient à la frappe** : une
rangée de complétion tapée sous la carte voilée est acceptée ; un tap
ailleurs referme la complétion puis continue sa cascade. L'aide de
signature (bannière passive, sans bornes mesurables exposées) ne
compte pas encore comme obstacle — la liste est extensible côté
bibliothèque. Le **fantôme de la variante B est son exact miroir** :
quand un popup recouvre ≥ 10 % de l'AIRE de la carte (hystérésis
identique), la goutte devient eau à 12 % d'opacité pendant le
chevauchement et redevient normale (fondu 180 ms) dès qu'il cesse.

**La fluidité des re-placements** (retour terrain n°5) : en
`THREE_LAYERS` et en `VARIANT_B`, la carte ne saute plus d'un
placement à l'autre — elle **GLISSE** (transitoire de 180 ms,
décélération quadratique, même allure que le retour du voile) vers sa
nouvelle position quand la complétion survient, filtre ou referme :
au frame du re-placement elle part exactement de son ancienne
position, un deuxième re-placement en plein vol repart de la position
dessinée courante (la frappe en rafale reste continue). Le **suivi du
scroll reste RIGIDE** — le défilement translate l'œil, la garde et la
carte ensemble, aucun retard ni inertie ne s'applique au défilement ;
seul le re-placement (esquive, latéral, compactage, garde de frappe)
glisse. Le **geste de la barre de taille reste DIRECT** (la carte
suit le doigt sans inertie pendant le drag), et l'ORIGINALE reste la
maquette exacte (jamais de glissement). Le glissement est purement
VISUEL — l'interception des gestes, le voile et le fantôme vivent sur
le placement CIBLE (le transitoire dure 180 ms) ; la tige suit la
carte glissante tout en restant accrochée à l'œil.

```java
// La politique (défaut ORIGINAL — la maquette d'origine) :
editorView.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
// La variante B : lisibilité brute, la complétion recouvre la carte —
// la goutte devient un fantôme translucide à 10–15 % PENDANT que la
// complétion la chevauche, normale dès qu'elle referme :
editorView.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);
// L'option taille — facteur d'agrandissement borné [1, 1,5] :
editorView.setPreviewDropScale(1.3f); // carte entière +30 %
```

**La barre de taille vit DANS la goutte** (retour terrain n°3) : le
pied de la carte porte une petite piste de progression à côté de la
puce « fit · NN % » — l'utilisateur choisit la taille directement
dans l'aperçu (« quelle taille au lieu du 232 dp ») : **press = saut
immédiat** du curseur, **drag vivant** (la carte se redimensionne
sous le doigt, le repère de piste est GELÉ au press — la progression
reste une fonction linéaire et stable de la position du doigt),
**relâchement → `onPreviewDropScaleChanged`** du hôte (hook de
persistance, une notification par geste) ; la puce « fit » affiche
l'échelle effective (compactage courant × facteur choisi). La voie
programmatique `setPreviewDropScale` reste (valeur initiale au
démarrage — la démo relit sa préférence persistée).

**Design de la goutte** (retour terrain) : la marge autour du
mini-téléphone est resserrée (10 → 4 dp) et **transparente** — la
bande du téléphone est une fenêtre TROUÉE dans le verre de la carte
(le code reste visible autour du bezel), seuls l'en-tête et le pied
restent verre. Le changement de politique en cours de vie de la carte
réinitialise le voile (elle repart opaque, la nouvelle politique
s'applique au frame suivant).

Le contrat côté hôte (toutes les méthodes ont un défaut raisonnable —
seules les utiles sont à surcharger) :

```java
public class MyDropHost implements EditorPreviewHost {
    // — Décision —
    @Override public boolean canPreview(String fileName) {
        return fileName != null && fileName.endsWith(".xml");
    }
    // Vrai = le tap d'aperçu ouvre la CARTE (hôtes canvas) ;
    // faux (défaut) = feuille/panneau (hôtes Vue — WebView…).
    @Override public boolean prefersDropPreview() { return true; }

    // — Carte —
    @Override public List<String> previewDropTabs() {
        return List.of("XML", "Compose");   // pilules d'en-tête (défaut : aucune)
    }
    @Override public void drawDropPreview(Canvas c, float x, float y,
            float w, float h, int tab) {
        // L'écran du mini-téléphone : le canvas est DÉJÀ translaté à la
        // carte, échelonné (compactage) et clippé à l'écran — dessiner
        // dans le rectangle (x, y, w, h), en coordonnées carte.
        // « tab » = l'onglet sélectionné (re-rendu à chaque frame).
    }
    @Override public String previewDropBadge(int tab) {
        return tab == 1 ? "MainActivity.kt · @Composable"
                        : "activity_main.xml · gonflé";   // défaut : aucun
    }
    @Override public void onPreviewDropTabSelected(int index) {
        // L'utilisateur a basculé d'onglet — l'invalidation est déjà
        // posée par la bibliothèque ; ne maintenez que votre état.
    }
    @Override public void onPreviewDropScaleChanged(float scale) {
        // Retour terrain n°3 : l'utilisateur a relâché la BARRE DE
        // TAILLE du pied (à côté de « fit ») — persistez le facteur
        // choisi [1, 1,5] (relu au démarrage via setPreviewDropScale).
    }

    // — Feuille/panneau (inutile pour un hôte carte seul) —
    @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
            int left, int width) { }
    @Override public void onPreviewContentChanged(CharSequence text) {
        // Contenu débouncé (200 ms) pendant la frappe : re-rendre ici.
    }
}
```

Les gestes, câblés par la bibliothèque (aucun code hôte) :

- tap sur l'**œil** de gouttière → ouverture (l'ancre est la rangée du
  caret) ; re-tap sur l'œil (resté **gelé** sur l'ancre) → fermeture ;
- tap sur un **onglet** → bascule + `onPreviewDropTabSelected` ;
- tap sur **✕** → fermeture ;
- **barre de taille du pied** (à côté de « fit ») → press = saut du
  curseur, drag vivant, relâchement → `onPreviewDropScaleChanged`
  (persistance) ;
- tap dans le **corps** → englouti (le caret ne bouge pas) — mais si
  une complétion vit SOUS le doigt, sa rangée gagne d'abord (surface
  de frappe prioritaire), et un tap hors du popup la referme avant la
  cascade ;
- tap **ailleurs** → cascade normale (caret posé), la carte reste
  ouverte et esquive la nouvelle zone de frappe au frame suivant ;
- fermetures de sûreté : changement de session, fichier devenu
  non-prévisualisable, détachement de la fenêtre.

L'API programme : `openPreviewDropAt(ligne)`, `closePreviewDrop()`,
`togglePreviewDrop()` ; l'état ouvert se lit sur le mode public :
`getPreviewMode() == PreviewMode.DROP`.

### La maquette de conception

La carte a été dessinée et validée sur une maquette HTML interactive
(ouvrir [`docs/maquettes/preview-drop-gouttiere.html`](
docs/maquettes/preview-drop-gouttiere.html) dans un navigateur : elle
simule la frappe et montre l'esquive en direct). La correspondance
élément par élément est tablettée dans le [README](README.md) (section
« Connecter l'application hôte »). Points de conception notables :

- **l'œil se GÈLE** sur la rangée d'ancre tant que la carte vit (il ne
  suit plus le caret — la tige relie l'œil à la goutte du popup quand
  la carte s'esquive loin) ;
- le placement est **re-résolu à chaque frame** en coordonnées écran :
  la carte suit l'œil au scroll et esquive le caret à la frappe, sans
  animation dédiée (seul le fondu d'entrée/sortie de 120 ms anime) ;
- le **compactage** est une échelle du canvas autour du coin haut-gauche
  — le hôte dessine toujours en coordonnées carte pleine taille ;
- les **rectangles d'onglets** sont mesurés une fois à l'ouverture et
  partagés entre le dessin et le hit-test (zéro divergence).

Le contrat complet de l'interface (12 méthodes, défauts compris) est
décrit dans [`ARCHITECTURE.md`](ARCHITECTURE.md).

## LSP (Language Server Protocol)

Le module `:cel-lsp` connecte l'éditeur à des serveurs LSP réels via LSP4J :

```java
import jo.codeeditor.lsp.LspProject;
import jo.codeeditor.lsp.LspEditor;

LspProject project = new LspProject("/path/to/workspace");
project.addServerDefinition(myServerDefinition); // ex. un serveur Java, un serveur Lua…
LspEditor editor = project.createEditor("file:///path/to/Foo.java");
editor.setHost(new jo.codeeditor.view.LspEditorViewHost(editorView));
editor.connect();   // CompletableFuture — didOpen envoyé à la connexion
// …
project.shutdown(); // borné à 2 s par serveur
```

Cinq providers de connexion sont disponibles (`lsp/connection/`) : flux
directs, in-process, socket local Unix, `ProcessBuilder`, socket TCP.

### Cycle de vie : hôte à vue unique, changement d'onglet (3.74.0, ADR-003)

L'application hôte qui réutilise UNE `EditorView` pour tous ses onglets
n'a RIEN à faire de spécial : l'invariant est garanti par la
bibliothèque —

```java
// Onglet .java : connexion du serveur de langage
editor.connect();          // didOpen + pose du langage LSP sur la vue
// …
// Changement d'onglet vers un fichier SANS serveur (ex. .txt) :
editor.disconnect();       // didClose + EmptyLanguage restaurée sur la vue :
                           // complétion, survol, formateur, indices, diagnostics —
                           // TOUT est fermé, aucune requête ne part
```

- `disconnect()` est **idempotent** (le rappeler deux fois ne casse rien)
  et referme toutes les capacités LSP : la vue revient à la coloration
  lexicale de son propre tokenizer (un `.txt` reste lisible, un `.xml`
  garde sa coloration lexicale intégrée). La coloration lexicale, les
  plis lexicaux, la frappe intelligente et l'IME ne dépendent PAS de LSP
  — ils survivent à la déconnexion.
- La course « changer d'onglet pendant la connexion » est neutralisée :
  la continuation de `connect()` abandonne si une déconnexion (ou une
  reconnexion) est intervenue entre-temps.
- **Ne posez PAS de `setLanguage(null)` de contournement dans votre
  coordinateur** : la bibliothèque s'en charge désormais (l'appel
  resterait inoffensif mais redondant).
- De retour sur l'onglet `.java` : un nouveau `connect()` rétablit tout
  (les débounceurs didChange/didSave renaissent à chaque connexion).

### Surlignage sémantique automatique (4.9.0)

Quand le serveur annonce `semanticTokensProvider`, la bibliothèque tire
`textDocument/semanticTokens/full` toute seule sur TROIS signaux
indépendants : à la pose du langage (connexion), après chaque didChange
envoyé (la requête part APRÈS le didChange dans la même file — le
serveur répond sur le texte frais), et à chaque publication de
diagnostics (le signal historique). Un serveur qui ne publie jamais de
diagnostics colorait donc le document avant 4.9.0 : ce n'est plus le
cas. Le throttle du puller (700 ms, un seul re-tir en fin de fenêtre)
déduplique les signaux concurrents ; un serveur sans provider annoncé
ne reçoit jamais la moindre requête, et le régime gros fichiers coupe
tout tir.

À la déconnexion, les jetons posés par le serveur sont purgés de la
session, exactement comme les diagnostics (3.74.0) : l'onglet suivant
n'hérite d'aucune couleur du fichier fermé. Une application qui pose
ses PROPRES jetons (ci-dessous) pendant une connexion LSP active doit
savoir qu'ils partiront avec elle — posez-les après `disconnect()`, ou
reposez-les à chaque changement de langage.

### Formatage : disponibilité, options, sélection (3.74.0)

```java
// Disponibilité (thread UI) — griser/masquer l'action « Formater » :
menu.findItem(R.id.action_format).setEnabled(editorView.canFormat());
menu.findItem(R.id.action_format_selection)
        .setEnabled(editorView.canFormatSelection());

// Options (avant le formatage ; sinon détection dans le document) :
editorView.setFormattingOptions(4, true);   // tabSize, insertSpaces

// Formatage de la sélection : un seul pas d'undo, caret conservé.
// Callback (thread UI) : TRUE = texte changé ; FALSE = refus propre
// (formateur sans plages, sélection vide, aucune édition) ;
// null = aucun formateur installé.
editorView.formatSelection(ok -> { /* rafraîchir l'état de l'action */ });
```

Un serveur qui n'annonce PAS le formatage de plage rend
`canFormatSelection()` faux et `formatSelection` refuse proprement
(texte inchangé, aucune requête) : le document entier n'est JAMAIS
formaté à la place de la sélection demandée. Les échecs du formateur
(délai, erreur serveur) sont journalisés via le récepteur
(`LspProject.setLogSink`) — sans jamais y écrire le contenu du document.

## ProGuard / R8

La bibliothèque est consommable en debug sans minification R8. Si vous
activez R8 en release, ajoutez ces règles à `proguard-rules.pro` :

```proguard
# code-editor — conserver la surface d'API publique.
-keep public class jo.codeeditor.view.EditorView { *; }
-keep public class jo.codeeditor.view.chrome.EditorTheme { *; }
-keep public class jo.codeeditor.view.EditorMetrics { *; }
-keep public class jo.codeeditor.view.input.EditorKeymap { *; }
-keep public class jo.codeeditor.session.EditorSession { *; }
-keep public class jo.codeeditor.document.EditorDocument { *; }
-keep public class jo.codeeditor.document.Selection { *; }
-keep public class jo.codeeditor.shift.DiagnosticShift$* { *; }
-keep public class jo.codeeditor.completion.CompletionSession$* { *; }
-keep public class jo.codeeditor.navigation.NavigationMenu$* { *; }
-keep public class jo.codeeditor.cache.LineRenderCache$* { *; }

# Le rendu Canvas n'utilise pas la réflexion — aucune règle keep
# supplémentaire n'est nécessaire pour la couche View.
```

## Dépannage

### Le clavier n'apparaît pas au tap

Le clavier n'apparaît qu'après un **tap explicite** dans la zone de texte
(pas sur le focus seul). C'est voulu — ouvrir un fichier ou changer d'onglet
ne doit pas faire popper l'IME. Pour l'afficher programmatiquement :

```java
editorView.showSoftKeyboard();
```

Depuis la passe R8 (plan 09), la politique est unifiée et le clavier
logiciel ne monte QUE si l'éditeur est éditable et qu'aucun clavier
matériel n'est exposé : en **lecture seule**
(`session.setReadOnly(true)`) le tap pose le caret sans clavier (le
système ne considère plus la vue comme un champ d'édition), et derrière
un **clavier matériel** connecté/exposé le logiciel reste couché
(l'utilisateur tape sur le physique). Vérifiez ces deux états avant de
suspecter un bug.

### Le curseur disparaît après un undo

`EditorSession.undo()`/`redo()` notifient l'`ImeListener`, qui relance le
clignotement du caret. Si vous appelez `session.undo()` depuis du code
personnalisé, appelez aussi `editorView.onTextChanged()` (ou utilisez le
raccourci Ctrl+Z qui le fait pour vous).

### Un tap sur une suggestion de complétion ne fait rien

Le hit-test du popup de complétion route les taps vers
`completionAccept()`. Vérifiez que vous n'interceptez pas les événements
tactiles avant l'EditorView (overlay plein écran, etc.).

### Le chevron de pli chevauche le numéro de ligne

La largeur du gutter inclut une colonne dédiée au strip de pli. Si vous
surchargez `EditorMetrics`, laissez cette marge intacte.

## Pour aller plus loin

- [`FEATURES.md`](FEATURES.md) — matrice complète des fonctionnalités.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — organisation des modules et des packages.
- [`CHANGELOG.md`](CHANGELOG.md) — historique des versions.

## Droite-à-gauche (RTL)

Non supporté (décision du propriétaire, consignée dans l'ADR-001 du
plan d'audit 3.69.0). L'éditeur rend toujours ses métriques, sa
gouttière et son texte de gauche à droite ; aucun comportement n'est
garanti sous `layoutDirection = rtl` (rendu superposé, gouttière à
droite non calibrée). Un plan d'implémentation complet — si le besoin
surgit — est esquissé dans `docs/plans/05-accessibilite-i18n-rtl.md`.


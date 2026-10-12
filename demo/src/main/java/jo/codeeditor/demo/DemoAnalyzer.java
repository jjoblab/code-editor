package jo.codeeditor.demo;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.completion.SignatureHelpController;
import jo.codeeditor.completion.SignatureHelpController.Parameter;
import jo.codeeditor.completion.SignatureHelpController.Signature;
import jo.codeeditor.completion.SignatureHelpController.SignatureHelp;
import jo.codeeditor.edit.CommentSyntax;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.view.EditorView;

/**
 * Analyseur factice de la démo : alimente les popups de l'éditeur
 * (complétion, aide de signature, quick doc, code actions, go-to-symbol)
 * et publie des diagnostics + inlay hints synthétiques, recalculés avec
 * débouncage à chaque édition — sans aucun serveur LSP.
 *
 * <p>Règles de diagnostics (offsets décalés automatiquement par
 * {@code DiagnosticShift} entre deux recalculs) :</p>
 * <ul>
 *   <li>marqueur {@code TODO} → info ;</li>
 *   <li>marqueur {@code FIXME} → avertissement ;</li>
 *   <li>marqueur {@code ERROR} → erreur ;</li>
 *   <li>ligne de plus de 100 caractères → avertissement en bout de ligne ;</li>
 *   <li>{@code print(} en Python → info de débogage.</li>
 * </ul>
 */
final class DemoAnalyzer {

    private static final long DEBOUNCE_MS = 500L;
    private static final int LONG_LINE = 100;
    private static final int MAX_INLAYS = 12;

    private static final Pattern DECL_TYPE =
            Pattern.compile("\\b(class|interface|object|record|struct|enum)\\s+([A-Za-z_]\\w*)");
    private static final Pattern DECL_FUNC =
            Pattern.compile("\\b(fun|function|def|fn|func|sub)\\s+([A-Za-z_]\\w*)");
    private static final Pattern INLAY_NUMBER =
            Pattern.compile("\\b([A-Za-z_]\\w*)\\s*=\\s*(\\d+(?:\\.\\d+)?)\\s*(?=#|$)");
    private static final Pattern INLAY_STRING =
            Pattern.compile("\\b([A-Za-z_]\\w*)\\s*=\\s*(\"[^\"]*\")");

    private final EditorView view;
    private final EditorSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String language;

    private Runnable pending;

    DemoAnalyzer(EditorView view, EditorSession session, String language) {
        this.view = view;
        this.session = session;
        this.language = language;
    }

    /** Branche tous les résolveurs et pousse une première passe de diagnostics. */
    void attach() {
        view.setCompletionProvider(this::completions);
        view.setSignatureHelpResolver(this::signatureHelp);
        view.setQuickDocResolver(this::quickDoc);
        view.setCodeActionsResolver(this::codeActions);
        view.setSymbolResolver(this::symbols);
        session.addOnTextEditListener((start, end, inserted) -> scheduleRefresh());
        refreshNow();
    }

    /** Interrompt les recalculs en attente et vide les annotations. */
    void detach() {
        handler.removeCallbacksAndMessages(null);
        session.setDiagnostics(Collections.emptyList());
        session.setInlayHints(Collections.emptyList());
    }

    // ── Diagnostics + inlay hints (débouncés) ─────────────────────

    private void scheduleRefresh() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = this::refreshNow;
        handler.postDelayed(pending, DEBOUNCE_MS);
    }

    private void refreshNow() {
        String text = session.getText();
        session.setDiagnostics(diagnosticsFor(text));
        session.setInlayHints(inlayHintsFor(text));
        view.invalidate();
    }

    private List<DiagnosticShift.Diagnostic> diagnosticsFor(String text) {
        List<DiagnosticShift.Diagnostic> out = new ArrayList<>();
        int lineStart = 0;
        int line = 0;
        int length = text.length();
        while (lineStart <= length) {
            int lineEnd = text.indexOf('\n', lineStart);
            int stop = lineEnd < 0 ? length : lineEnd;
            String content = text.substring(lineStart, stop);

            markWord(out, text, lineStart, content, "TODO", 1,
                    "Tâche restante — marker TODO (analyseur factice)");
            markWord(out, text, lineStart, content, "FIXME", 2,
                    "Marker FIXME à traiter (analyseur factice)");
            markWord(out, text, lineStart, content, "ERROR", 3,
                    "Erreur factice — la ligne de log signale un échec");
            if ("python".equals(language)) {
                markWord(out, text, lineStart, content, "print", 1,
                        "print() sert au débogage — envisager le module logging");
            }
            if (content.length() > LONG_LINE) {
                int from = lineStart + content.length() - 8;
                out.add(new DiagnosticShift.Diagnostic(
                        Math.max(from, lineStart + content.trim().length()),
                        lineStart + content.length(), 2,
                        String.format(Locale.FRANCE,
                                "Ligne très longue (%d caractères)", content.length())));
            }

            if (lineEnd < 0) break;
            lineStart = lineEnd + 1;
            line++;
        }
        return out;
    }

    private static void markWord(List<DiagnosticShift.Diagnostic> out, String text,
                                 int lineStart, String line, String word,
                                 int severity, String message) {
        int at = line.indexOf(word);
        if (at < 0) return;
        int start = lineStart + at;
        out.add(new DiagnosticShift.Diagnostic(start, start + word.length(), severity, message));
    }

    private List<DiagnosticShift.InlayHint> inlayHintsFor(String text) {
        // Indices de type factices pour les langages non typés.
        if (!"python".equals(language) && !"javascript".equals(language)
                && !"typescript".equals(language)) {
            return Collections.emptyList();
        }
        List<DiagnosticShift.InlayHint> out = new ArrayList<>();
        int lineStart = 0;
        int length = text.length();
        while (lineStart <= length && out.size() < MAX_INLAYS) {
            int lineEnd = text.indexOf('\n', lineStart);
            int stop = lineEnd < 0 ? length : lineEnd;
            String content = text.substring(lineStart, stop);

            Matcher nombre = INLAY_NUMBER.matcher(content);
            if (nombre.find() && !content.trim().startsWith("#")) {
                boolean decimal = nombre.group(2).contains(".");
                out.add(new DiagnosticShift.InlayHint(
                        lineStart + nombre.end(2),
                        decimal ? ": float" : ": int", false));
            } else {
                Matcher chaine = INLAY_STRING.matcher(content);
                if (chaine.find() && !content.trim().startsWith("#")) {
                    out.add(new DiagnosticShift.InlayHint(
                            lineStart + chaine.end(2), ": str", false));
                }
            }

            if (lineEnd < 0) break;
            lineStart = lineEnd + 1;
        }
        return out;
    }

    // ── Complétion ────────────────────────────────────────────────

    private List<CompletionSession.Item> completions(String text, int caret,
                                                     int tokenStart, String prefix) {
        List<CompletionSession.Item> out = new ArrayList<>();

        // 1. Snippets de démo (score le plus haut, badge snippet).
        String lineComment = lineCommentPrefix();
        out.add(new CompletionSession.Item("sysout", "snippet — insère un println",
                "System.out.println(\"\");", null, 15, 70, false, true));
        out.add(new CompletionSession.Item("main", "snippet — méthode principale",
                "public static void main(String[] args) {\n    \n}", null, 15, 68, false, true));
        if (lineComment != null) {
            out.add(new CompletionSession.Item("todo", "snippet — tâche restante",
                    lineComment + " TODO: ", null, 15, 66, false, true));
        }

        // 2. Fonctions et types génériques de démo.
        String[] fonctions = {"println", "print", "format", "substring", "toUpperCase",
                "trim", "length", "size", "append", "join"};
        for (String f : fonctions) {
            out.add(new CompletionSession.Item(f, "fonction de démo", f + "()", null,
                    3, 55, false, false));
        }
        String[] types = {"String", "Integer", "List", "Map", "Math", "System", "Runnable"};
        for (String t : types) {
            out.add(new CompletionSession.Item(t, "type de démo", t, null,
                    7, 50, false, false));
        }

        // 3. Auto-import factice (pièce jointe post-insertion) — Java.
        if ("java".equals(language)) {
            out.add(new CompletionSession.Item("ArrayList", "java.util.ArrayList (auto-import)",
                    "ArrayList", null, 7, 60, false, false,
                    (Runnable) () -> insertImport("import java.util.ArrayList;")));
            out.add(new CompletionSession.Item("HashMap", "java.util.HashMap (auto-import)",
                    "HashMap", null, 7, 60, false, false,
                    (Runnable) () -> insertImport("import java.util.HashMap;")));
        }

        // 4. Mots-clés du langage courant.
        LanguageProfile profile = LanguageRegistry.forName(language);
        if (profile != null && profile.keywords != null) {
            List<String> mots = new ArrayList<>(profile.keywords);
            Collections.sort(mots);
            for (String mot : mots) {
                out.add(new CompletionSession.Item(mot, "mot-clé " + language, mot, null,
                        14, 40, true, false));
            }
        }
        return out;
    }

    /** Insère un import après la ligne package (ou en tête de fichier). */
    private void insertImport(String importLine) {
        String text = session.getText();
        int insertAt = 0;
        int packageIdx = text.indexOf("package ");
        if (packageIdx >= 0) {
            int lineEnd = text.indexOf('\n', packageIdx);
            insertAt = lineEnd < 0 ? text.length() : lineEnd + 1;
        }
        session.replaceRange(insertAt, insertAt, importLine + "\n");
    }

    // ── Aide de signature ─────────────────────────────────────────

    private SignatureHelp signatureHelp(String text, int caret) {
        int open = -1;
        int depth = 0;
        for (int i = caret - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth > 0) depth--;
                else {
                    open = i;
                    break;
                }
            }
        }
        if (open < 0) return null;

        // Identifiant juste avant la parenthèse ouvrante.
        int idEnd = open;
        while (idEnd > 0 && Character.isWhitespace(text.charAt(idEnd - 1))) idEnd--;
        int idStart = idEnd;
        while (idStart > 0 && Character.isJavaIdentifierPart(text.charAt(idStart - 1))) idStart--;
        if (idStart == idEnd) return null;
        String name = text.substring(idStart, idEnd);

        // Paramètre actif = nombre de virgules de profondeur 0 avant le caret.
        int active = 0;
        depth = 0;
        for (int i = open + 1; i < caret && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) active++;
        }

        List<Parameter> params = new ArrayList<>();
        params.add(new Parameter("premier", "premier argument (factice)"));
        params.add(new Parameter("deuxieme", "deuxième argument (factice)"));
        params.add(new Parameter("options", "options facultatives (factice)"));

        List<Signature> signatures = new ArrayList<>();
        signatures.add(new Signature(
                name + "(premier, deuxieme, options)",
                "Signature de démonstration n°1 — générée par l'analyseur factice.",
                params, active));
        signatures.add(new Signature(
                name + "(premier)",
                "Surcharge minimale de démonstration.",
                Collections.singletonList(params.get(0)), active));

        return new SignatureHelp(signatures, 0, active);
    }

    // ── Quick doc ─────────────────────────────────────────────────

    private String quickDoc(String text, int offset) {
        int start = offset;
        int end = offset;
        int length = text.length();
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < length && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        if (start == end) return null;
        String word = text.substring(start, end);

        return "/**\n"
                + " * " + word + " — symbole de démonstration.\n"
                + " *\n"
                + " * <p>Cette documentation est générée par l'analyseur factice de la\n"
                + " * démo : le texte original du symbole est introuvable, on raconte\n"
                + " * quelque chose de plausible à la place.</p>\n"
                + " *\n"
                + " * @param " + word + " aucun, c'est une démo\n"
                + " * @since 3.38.0 (démo)\n"
                + " */";
    }

    // ── Code actions ──────────────────────────────────────────────

    private List<EditorView.CodeAction> codeActions(String text, int line) {
        int[] range = lineRange(text, line);
        if (range == null) return Collections.emptyList();
        int lineStart = range[0];
        int lineEnd = range[1];
        int nextStart = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
        String content = text.substring(lineStart, lineEnd);

        // Ampoule uniquement là où il y a quelque chose à faire.
        boolean interessante = content.contains("TODO") || content.contains("FIXME")
                || content.length() > 80 || content.trim().isEmpty();
        if (!interessante) return Collections.emptyList();

        List<EditorView.CodeAction> actions = new ArrayList<>();
        if (lineCommentPrefix() != null) {
            actions.add(new EditorView.CodeAction(
                    "Commenter / décommenter la ligne", "refactor", () -> {
                session.setSelection(lineStart);
                session.selectLineAt(lineStart);
                session.toggleLineComment();
            }));
        }
        actions.add(new EditorView.CodeAction(
                "Dupliquer la ligne", "refactor",
                () -> session.replaceRange(lineEnd, lineEnd, "\n" + content)));
        actions.add(new EditorView.CodeAction(
                "Supprimer la ligne", "quickfix",
                () -> session.replaceRange(lineStart, nextStart, "")));
        return actions;
    }

    /** Préfixe de commentaire de ligne du langage, ou null si aucun. */
    private String lineCommentPrefix() {
        CommentSyntax syntax = CommentSyntax.forLanguage(language);
        return syntax != null ? syntax.lineComment : null;
    }

    // ── Go-to-symbol ──────────────────────────────────────────────

    private List<NavigationMenu.Symbol> symbols(String text) {
        List<NavigationMenu.Symbol> out = new ArrayList<>();
        int lineStart = 0;
        int length = text.length();
        String conteneur = "";
        while (lineStart <= length) {
            int lineEnd = text.indexOf('\n', lineStart);
            int stop = lineEnd < 0 ? length : lineEnd;
            String content = text.substring(lineStart, stop);

            Matcher type = DECL_TYPE.matcher(content);
            if (type.find()) {
                conteneur = type.group(2);
                out.add(new NavigationMenu.Symbol(conteneur,
                        lineStart + type.start(2), type.group(1), conteneur));
            }
            Matcher func = DECL_FUNC.matcher(content);
            if (func.find()) {
                out.add(new NavigationMenu.Symbol(func.group(2),
                        lineStart + func.start(2), "function", conteneur));
            }

            if (lineEnd < 0) break;
            lineStart = lineEnd + 1;
        }
        return out;
    }

    // ── Utilitaires ───────────────────────────────────────────────

    /** Bornes [début, fin) de la ligne donnée (0-based, sans le \\n). */
    private static int[] lineRange(String text, int line) {
        int lineStart = 0;
        for (int i = 0; i < line; i++) {
            int next = text.indexOf('\n', lineStart);
            if (next < 0) return null;
            lineStart = next + 1;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        return new int[]{lineStart, lineEnd};
    }
}

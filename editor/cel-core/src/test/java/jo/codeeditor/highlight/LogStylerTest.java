package jo.codeeditor.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★ Tests du styleur « log » (console Gradle/JVM de
 * CodeIDE — {@code ConsoleLogView}).
 *
 * <p>Vérifie le mapping ligne → {@link TokenType} pour les lignes RÉELLEMENT
 * émises par Gradle 8.x via le Tooling API (format vérifié empiriquement :
 * en-tête de tâche, statut sur ligne séparée avec espace de tête, ligne vide
 * entre les blocs) ainsi que les lignes de cadrage synthétisées par l'app
 * («&nbsp;&gt;&nbsp;Build:&nbsp;…&nbsp;», «&nbsp;[Tooling]&nbsp;…&nbsp;»).</p>
 *
 * <p>Toutes les couleurs sont natives : le token produit est consommé par
 * {@code EditorTheme.colorForToken(TokenType)} — le styleur n'introduit
 * AUCUNE couleur custom (la console doit utiliser les couleurs natives de
 * l'EditorView).</p>
 *
 * @author jo@Dev
 */
class LogStylerTest {

    private static final SyntaxHighlighter HL = new SyntaxHighlighter();

    private static TokenType typeOf(String line) {
        StyledLine styled = HL.styleLine(line, LexState.NORMAL, "log");
        List<LineSpan> spans = styled.spans;
        if (spans == null || spans.isEmpty()) {
            return TokenType.PLAIN;  // pas de span = rendu texte brut
        }
        return spans.get(0).type;
    }

    // ─── Erreurs (ERROR — rouge natif du thème) ─────────────────────────

    @Test
    @DisplayName("« e: error: unresolved reference » → ERROR (préfixe Kotlin)")
    void kotlinError_isError() {
        assertEquals(TokenType.ERROR, typeOf("e: error: unresolved reference: foo"));
    }

    @Test
    @DisplayName("« > Task :app:compileDebugKotlin FAILED » → ERROR (échec de tâche)")
    void taskFailed_isError() {
        assertEquals(TokenType.ERROR, typeOf("> Task :app:compileDebugKotlin FAILED"));
    }

    @Test
    @DisplayName("« BUILD FAILED in 3s » → ERROR")
    void buildFailed_isError() {
        assertEquals(TokenType.ERROR, typeOf("BUILD FAILED in 3s"));
    }

    @Test
    @DisplayName("« FAILURE: Build failed with an exception. » → ERROR")
    void failureLine_isError() {
        assertEquals(TokenType.ERROR, typeOf("FAILURE: Build failed with an exception."));
    }

    @Test
    @DisplayName("« What went wrong: » → ERROR")
    void whatWentWrong_isError() {
        assertEquals(TokenType.ERROR, typeOf("What went wrong:"));
    }

    @Test
    @DisplayName("« 1 actionable task: 1 failed » → ERROR")
    void summaryWithFailed_isError() {
        assertEquals(TokenType.ERROR, typeOf("1 actionable task: 1 failed"));
    }

    @Test
    @DisplayName("« java.lang.RuntimeException: boom » → ERROR (stacktrace)")
    void exceptionStacktrace_isError() {
        assertEquals(TokenType.ERROR, typeOf("java.lang.RuntimeException: boom"));
    }

    // ─── Avertissements (WARNING — jaune natif) ─────────────────────────

    @Test
    @DisplayName("« w: deprecation warning » → WARNING (préfixe Kotlin)")
    void kotlinWarning_isWarning() {
        assertEquals(TokenType.WARNING, typeOf("w: file.kt: (12, 5): 'x' is deprecated"));
    }

    @Test
    @DisplayName("« warning: [options] source value 8 is obsolete » → WARNING")
    void javacWarning_isWarning() {
        assertEquals(TokenType.WARNING, typeOf("warning: [options] source value 8 is obsolete"));
    }

    // ─── Succès (SUCCESS — vert natif) ──────────────────────────────────

    @Test
    @DisplayName("« BUILD SUCCESSFUL in 7s » → SUCCESS")
    void buildSuccessful_isSuccess() {
        assertEquals(TokenType.SUCCESS, typeOf("BUILD SUCCESSFUL in 7s"));
    }

    @Test
    @DisplayName("« BUILD SUCCESSFUL (12345ms) » (résumé app) → SUCCESS")
    void appBuildSummary_isSuccess() {
        assertEquals(TokenType.SUCCESS, typeOf("BUILD SUCCESSFUL (12345ms)"));
    }

    @Test
    @DisplayName("« SYNC SUCCESSFUL — 3 module(s) · Gradle 8.9 » (résumé app) → SUCCESS")
    void appSyncSummary_isSuccess() {
        assertEquals(TokenType.SUCCESS, typeOf("SYNC SUCCESSFUL — 3 module(s) · Gradle 8.9"));
    }

    @Test
    @DisplayName("«  UP-TO-DATE » (statut Gradle sur ligne séparée) → SUCCESS")
    void upToDateStatus_isSuccess() {
        assertEquals(TokenType.SUCCESS, typeOf(" UP-TO-DATE"));
    }

    @Test
    @DisplayName("«  FROM-CACHE » → SUCCESS")
    void fromCacheStatus_isSuccess() {
        assertEquals(TokenType.SUCCESS, typeOf(" FROM-CACHE"));
    }

    @Test
    @DisplayName("«  NO-SOURCE » → SUCCESS")
    void noSourceStatus_isSuccess() {
        assertEquals(TokenType.SUCCESS, typeOf(" NO-SOURCE"));
    }

    @Test
    @DisplayName("«  SKIPPED » → SUCCESS")
    void skippedStatus_isSuccess() {
        assertEquals(TokenType.SUCCESS, typeOf(" SKIPPED"));
    }

    // ─── En-têtes de section (TYPE — couleur native des types) ─────────

    @Test
    @DisplayName("« > Task :app:preBuild » → TYPE (en-tête Gradle)")
    void taskHeader_isType() {
        assertEquals(TokenType.TYPE, typeOf("> Task :app:preBuild"));
    }

    @Test
    @DisplayName("« > Task :app:compileDebugKotlin UP-TO-DATE » (inline) → TYPE")
    void taskHeaderWithInlineStatus_isType() {
        assertEquals(TokenType.TYPE, typeOf("> Task :app:compileDebugKotlin UP-TO-DATE"));
    }

    @Test
    @DisplayName("« > Build: assembleDebug » (cadrage app) → TYPE")
    void appBuildHeader_isType() {
        assertEquals(TokenType.TYPE, typeOf("> Build: assembleDebug"));
    }

    @Test
    @DisplayName("« > Sync Gradle... » (cadrage app) → TYPE")
    void appSyncHeader_isType() {
        assertEquals(TokenType.TYPE, typeOf("> Sync Gradle..."));
    }

    @Test
    @DisplayName("« > Configure project :app » (Gradle) → TYPE")
    void configureHeader_isType() {
        assertEquals(TokenType.TYPE, typeOf("> Configure project :app"));
    }

    // ─── Infos (INFO — bleu natif) ──────────────────────────────────────

    @Test
    @DisplayName("« [Tooling] Démarrage du serveur… » → INFO")
    void toolingLine_isInfo() {
        assertEquals(TokenType.INFO, typeOf("[Tooling] Démarrage du serveur…"));
    }

    @Test
    @DisplayName("« [Sync] Synchronisation du projet » → INFO")
    void syncLine_isInfo() {
        assertEquals(TokenType.INFO, typeOf("[Sync] Synchronisation du projet"));
    }

    @Test
    @DisplayName("« [Sync] Modèle de projet prêt » → SUCCESS (SUCCESS avant INFO — règle d'ordre)")
    void syncLineEndingWithPret_isSuccess() {
        // Règle d'évaluation documentée : SUCCESS est testé AVANT INFO pour
        // que les états « … prêt / … avec succès » des lignes bracketées
        // soient verts (comportement historique préservé).
        assertEquals(TokenType.SUCCESS, typeOf("[Sync] Modèle de projet prêt"));
    }

    @Test
    @DisplayName("« [JVM-out] … » → INFO (flux brut JVM, onglet Diagnostic)")
    void jvmOutLine_isInfo() {
        assertEquals(TokenType.INFO, typeOf("[JVM-out] hello from stdout"));
    }

    @Test
    @DisplayName("« [Cancel] Annulation du build en cours… » → INFO")
    void cancelLine_isInfo() {
        assertEquals(TokenType.INFO, typeOf("[Cancel] Annulation du build en cours…"));
    }

    // ─── Sortie standard (PLAIN — pas de span) ──────────────────────────

    @Test
    @DisplayName("« 1 actionable task: 1 executed » → PLAIN")
    void actionableSummary_isPlain() {
        assertEquals(TokenType.PLAIN, typeOf("1 actionable task: 1 executed"));
    }

    @Test
    @DisplayName("ligne vide → PLAIN (jamais rendue par ConsoleLogView, mais robuste)")
    void emptyLine_isPlain() {
        assertEquals(TokenType.PLAIN, typeOf(""));
    }

    @Test
    @DisplayName("« one » (println de tâche) → PLAIN")
    void taskOutput_isPlain() {
        assertEquals(TokenType.PLAIN, typeOf("one"));
    }

    // ─── État lexical ────────────────────────────────────────────────────

    @Test
    @DisplayName("styleLog ignore l'état d'entrée — entry/exit state toujours NORMAL")
    void logStyling_hasNoCrossLineState() {
        StyledLine styled = HL.styleLine("> Task :app:x", LexState.BLOCK_COMMENT, "log");
        // Le styleur log n'a pas d'états lexicaux multi-lignes : quel que
        // soit l'état d'entrée (ex. commentaire ouvert par une vue
        // précédente), la ligne est re-normalisée en NORMAL.
        assertEquals(LexState.NORMAL, styled.entryState);
        assertEquals(LexState.NORMAL, styled.exitState);
    }

    @Test
    @DisplayName("Spans PLAIN : aucune span allouée (rendu texte brut rapide)")
    void plainLine_hasNoSpans() {
        StyledLine styled = HL.styleLine("du texte standard", LexState.NORMAL, "log");
        assertTrue(styled.spans.isEmpty());
    }

    @Test
    @DisplayName("Span colorée couvre TOUTE la ligne (startCol=0, endCol=len)")
    void coloredLine_coversWholeLine() {
        String line = "> Task :app:preBuild";
        StyledLine styled = HL.styleLine(line, LexState.NORMAL, "log");
        assertEquals(1, styled.spans.size());
        LineSpan span = styled.spans.get(0);
        assertEquals(0, span.startCol);
        assertEquals(line.length(), span.endCol);
        assertEquals(TokenType.TYPE, span.type);
    }
}

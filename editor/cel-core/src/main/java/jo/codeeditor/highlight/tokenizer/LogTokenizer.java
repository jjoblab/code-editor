package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Styleur de lignes de log (consoles Gradle/JVM embarquées) : une seule
 * span par ligne, classée ERROR/WARNING/SUCCESS/INFO/TYPE/PLAIN.
 * Extrait de SyntaxHighlighter car cette coloration sémantique n'a aucun
 * état lexical inter-lignes, contrairement aux tokéniseurs de code.
 */
public final class LogTokenizer {

    /**
     * Styleur de logs (console Gradle/JVM embarquée).
     *
     * <p>Utilisé par le langage « log » ({@code session.setLanguage("log")}) —
     * consommé par {@code ConsoleLogView} côté app CodeIDE pour la console de
     * build et l'onglet Diagnostic JVM.</p>
     *
     * <p>Coloration par ligne (une seule span par ligne — les consoles
     * reçoivent des milliers de lignes, la détection doit rester O(len)) :</p>
     * <ul>
     *   <li>{@link TokenType#ERROR} — "error", "failed", "build failed",
     *       "failure", "severe", "exception", "échec", "erreur",
     *       préfixe Kotlin/javac « e: »…</li>
     *   <li>{@link TokenType#WARNING} — "warning:…", préfixe Kotlin « w: »…</li>
     *   <li>{@link TokenType#SUCCESS} — "BUILD SUCCESSFUL", "succeeded",
     *       "succès", "prêt", et les statuts de tâche sans travail
     *       «&nbsp;UP-TO-DATE / FROM-CACHE / SKIPPED / NO-SOURCE&nbsp;»…</li>
     *   <li>{@link TokenType#INFO} — lignes structurées préfixées "[Tooling]",
     *       "[Sync]", "[JVM]", "[JVM-out]", "[Cancel]"…</li>
     *   <li>{@link TokenType#TYPE} — en-têtes de section Gradle «&nbsp;&gt;&nbsp;…&nbsp;»
     *       (« &gt; Task :… », « &gt; Configure… », « &gt; Build :… », « &gt; Sync… »).</li>
     *   <li>{@link TokenType#PLAIN} — sortie standard Gradle.</li>
     * </ul>
     *
     * <p>Toutes les couleurs proviennent du thème natif via
     * {@code EditorTheme.colorForToken(TokenType)} : le styleur n'introduit
     * AUCUNE couleur custom — la console affiche exactement les couleurs
     * natives de l'EditorView (thème de l'éditeur partagé).</p>
     *
     * <p>L'état d'entrée est ignoré : un log n'a pas d'états lexicaux
     * multi-lignes, l'exit state est toujours {@code LexState.NORMAL}.</p>
     */
    public static StyledLine styleLog(String line) {
        TokenType type = logTokenTypeFor(line);
        if (type == TokenType.PLAIN) {
            // Pas de span du tout → rendu texte brut (chemin le plus rapide,
            // cache-friendly dans EditorRenderer).
            return new StyledLine(java.util.Collections.emptyList(),
                LexState.NORMAL, LexState.NORMAL);
        }
        List<LineSpan> spans = new ArrayList<>(1);
        spans.add(new LineSpan(0, line.length(), type));
        return new StyledLine(spans, LexState.NORMAL, LexState.NORMAL);
    }

    /**
     * Détermine le type de token d'une ligne de log (règles présentées dans
     * l'ordre d'évaluation — important : ERROR avant SUCCESS pour que
     * « BUILD FAILED » gagne sur une éventuelle co-occurrence, SUCCESS avant
     * INFO pour que « [Tooling] … terminé avec succès » soit vert).
     *
     * <p>Couverture étendue aux lignes réellement émises par Gradle
     * via le Tooling API : statuts de tâche sur ligne séparée
     * («&nbsp; UP-TO-DATE&nbsp;»), en-têtes de section génériques
     * («&nbsp;&gt;&nbsp;…&nbsp;»), diagnostics Kotlin «&nbsp;e:&nbsp;»/«&nbsp;w:&nbsp;»,
     * échec de tâche («&nbsp;FAILED&nbsp;»). Couleurs 100% natives du thème.</p>
     */
    private static TokenType logTokenTypeFor(String line) {
        String l = line.toLowerCase(java.util.Locale.ROOT);
        // 1. Erreurs — messages d'échec javac/Gradle/JVM, stacktraces,
        //    "failed" générique ("Task :x FAILED", "1 failed") et préfixe
        //    Kotlin « e: ».
        if (l.contains("error")
                || l.contains("failed")
                || l.contains("failure")
                || l.contains("severe")
                || l.contains("exception")
                || l.startsWith("e:")
                || l.startsWith("erreur")
                || l.contains("échec")
                || l.contains("what went wrong")) {
            return TokenType.ERROR;
        }
        // 2. Avertissements, y compris le préfixe Kotlin « w: ».
        if (l.startsWith("warning:")
                || l.contains("warning:")
                || l.startsWith("> warning")
                || l.startsWith("w:")
                || l.contains("[warn]")) {
            return TokenType.WARNING;
        }
        // 3. Succès — résumés de build/sync réussis, état tooling prêt, et
        //    statuts de tâche « sans travail » que Gradle émet sur leur
        //    PROPRE ligne (sortie chunked du Tooling API) : rendus en
        //    SUCCESS (vert natif), comme dans les consoles pro.
        if (l.contains("build successful")
                || l.contains("sync successful")
                || l.contains("succeeded")
                || l.contains("succès")
                || l.endsWith("prêt")
                || l.contains("operation completed successfully")
                || l.startsWith(" up-to-date")
                || l.startsWith(" from-cache")
                || l.startsWith(" skipped")
                || l.startsWith(" no-source")) {
            return TokenType.SUCCESS;
        }
        // 4. En-têtes de section Gradle — généralisés à TOUTES les
        //    lignes « > … » : « > Task :app:x » (Gradle), « > Configure… »
        //    (Gradle), « > Build: … » / « > Sync Gradle… » (cadrage synthétisé
        //    par l'app). Un seul token TYPE = la couleur native des types
        //    du thème éditeur.
        if (line.startsWith("> ")) {
            return TokenType.TYPE;
        }
        // 5. Lignes bracketées simples ([Tooling] / [Sync] / [JVM] /
        //    [JVM-out] / [Cancel]) → INFO.
        if (l.startsWith("[tooling]") || l.startsWith("[sync]")
                || l.startsWith("[jvm]") || l.startsWith("[jvm-out]")
                || l.startsWith("[cancel]")) {
            return TokenType.INFO;
        }
        // 6. Sortie Gradle standard — texte brut.
        return TokenType.PLAIN;
    }

    private LogTokenizer() {}
}

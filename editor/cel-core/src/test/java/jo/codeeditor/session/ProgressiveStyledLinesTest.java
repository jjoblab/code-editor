package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.highlight.TextMateTokenizer;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suite du régime PROGRESSIF (plan 11 §5) — les invariants d'exactitude :
 *
 * <ul>
 *   <li><b>Équivalence piste intégrée</b> : les spans servis à la
 *       demande sont IDENTIQUES à ceux d'une chaîne d'états séquentielle
 *       complète (même tokéniseur, mêmes entrées).</li>
 *   <li><b>Équivalence piste grammaires</b> : idem avec un tokéniseur
 *       enregistré étatique (commentaire multi-lignes simulé).</li>
 *   <li><b>Pistes séparées (correctif P1-a)</b> : une ligne monstre
 *       bascule sur l'intégré SANS corrompre la pile grammaires —
 *       l'état d'entrée grammaires de la ligne suivante est intact.</li>
 *   <li><b>Épissure</b> : insertion/suppression au milieu d'un grand
 *       document — les lignes re-chaînées servent les spans d'une
 *       passe fraîche complète équivalente.</li>
 * </ul>
 */
class ProgressiveStyledLinesTest {

    @AfterEach
    void nettoyer() {
        SyntaxHighlighter.setTextMateTokenizer(null);
    }

    // ── Documents de test ─────────────────────────────────────────

    /** Bloc Java réaliste répété, avec commentaires multi-lignes. */
    private static String javaDoc(int blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks; i++) {
            sb.append("/* bloc ").append(i).append('\n');
            sb.append("   commentaire multi-lignes ").append(i).append(" */\n");
            sb.append("public final class Classe").append(i).append(" {\n");
            sb.append("    private int champ = ").append(i).append(";\n");
            sb.append("    public int methode(String arg) {\n");
            sb.append("        // commentaire ligne\n");
            sb.append("        return arg.length() * champ;\n");
            sb.append("    }\n");
            sb.append("}\n");
        }
        return sb.toString();
    }

    /** Chaîne eager de référence : tokénisation séquentielle complète. */
    private static List<StyledLine> chaineEager(String text, String lang,
            boolean allowTextMate) {
        SyntaxHighlighter hl = new SyntaxHighlighter();
        List<StyledLine> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int state = 0;
        for (String line : lines) {
            StyledLine styled = hl.styleLine(line, state, lang, allowTextMate);
            out.add(styled);
            state = styled.exitState;
        }
        return out;
    }

    private static String spansToString(StyledLine s) {
        StringBuilder sb = new StringBuilder();
        for (LineSpan span : s.spans) {
            sb.append('[').append(span.startCol).append(',')
                    .append(span.endCol).append(':').append(span.type)
                    .append(']');
        }
        return sb.toString();
    }

    private static EditorSession sessionDe(String text) {
        return new EditorSession(EditorDocument.of(text));
    }

    // ── Équivalence piste intégrée ────────────────────────────────

    @Test
    void equivalence_pisteIntegree() throws Exception {
        String text = javaDoc(120); // 960 lignes > EAGER → progressif
        EditorSession s = sessionDe(text);
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        List<StyledLine> attendu = chaineEager(text, "java", false);
        assertEquals(attendu.size(), s.getStyledLines().size());
        for (int i = 0; i < attendu.size(); i++) {
            assertEquals(spansToString(attendu.get(i)),
                    spansToString(s.getStyledLines().get(i)),
                    "ligne " + i + " : spans progressifs == chaîne eager");
        }
    }

    // ── Équivalence piste grammaires (tokéniseur étatique) ────────

    /**
     * Tokéniseur stub ÉTATIQUE : « commentaire multi-lignes » avec une
     * syntaxe PROPRIÉTÉ (§ … §§) que le tokéniseur intégré ignore — le
     * discrimineur des tests de séparation de pistes.
     */
    private static TextMateTokenizer stubEtatique() {
        return new TextMateTokenizer() {
            @Override
            public boolean isAvailable(String language) {
                return "java".equals(language);
            }

            @Override
            public StyledLine tokenize(String line, int entryState,
                    String language) {
                // états : bit 0 = dans un commentaire § ; bits 8+ =
                // profondeur de lignes dans le commentaire courant.
                boolean inComment = (entryState & 1) == 1;
                int depth = entryState >>> 8;
                List<LineSpan> spans = new ArrayList<>();
                if (inComment) {
                    spans.add(new LineSpan(0, line.length(), TokenType.COMMENT));
                    if (line.contains("§§")) {
                        inComment = false;
                        depth = 0;
                    } else {
                        depth++;
                    }
                } else if (line.startsWith("§")) {
                    spans.add(new LineSpan(0, line.length(), TokenType.COMMENT));
                    if (!line.contains("§§")) {
                        inComment = true;
                        depth = 1;
                    }
                } else {
                    spans.add(new LineSpan(0, line.length(), TokenType.ANNOTATION));
                }
                int exit = (inComment ? 1 : 0) | (depth << 8);
                return new StyledLine(spans, entryState, exit);
            }
        };
    }

    @Test
    void equivalence_pisteGrammaires() throws Exception {
        SyntaxHighlighter.setTextMateTokenizer(stubEtatique());
        // Corpus à états § : ouverture/fermeture multi-lignes réparties,
        // y compris juste avant la fin (pile portée jusqu'au bout).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append("§ ouverture ").append(i).append('\n');
            sb.append("corps du commentaire ").append(i).append('\n');
            sb.append("   fermeture §§\n");
            sb.append("public class Classe").append(i).append(" {}\n");
            sb.append("ligne code ").append(i).append('\n');
        }
        sb.append("§ ouverture finale non fermee\n");
        sb.append("encore dans le commentaire\n");
        for (int i = 0; i < 120; i++) {
            sb.append("queue ").append(i).append('\n');
        }
        String text = sb.toString();
        EditorSession s = sessionDe(text);
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        // Chaîne eager de référence SUR LE STUB (voie grammaires).
        List<StyledLine> attendu = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int state = 0;
        TextMateTokenizer stub = stubEtatique();
        for (String line : lines) {
            StyledLine styled = stub.tokenize(line, state, "java");
            attendu.add(styled);
            state = styled.exitState;
        }
        assertEquals(attendu.size(), s.getStyledLines().size());
        for (int i = 0; i < attendu.size(); i++) {
            assertEquals(spansToString(attendu.get(i)),
                    spansToString(s.getStyledLines().get(i)),
                    "ligne " + i + " : spans grammaires progressifs == chaîne eager");
        }
    }

    // ── Pistes séparées : ligne monstre (correctif P1-a) ──────────

    @Test
    void ligneMonstre_pistesSéparées_sansCorruption() throws Exception {
        SyntaxHighlighter.setTextMateTokenizer(stubEtatique());
        // Un commentaire § OUVERT avant la ligne monstre : la pile
        // grammaires doit rester « dans le commentaire » APRÈS la ligne
        // monstre (qui bascule sur l'intégré, indifférent au §) —
        // l'entrée grammaires de la ligne suivante est INTACTE.
        StringBuilder sb = new StringBuilder();
        sb.append("§ ouverture commentaire stub\n");    // 0 : ouvre (exit=1|1<<8)
        for (int i = 0; i < 60; i++) {
            sb.append("ligne ").append(i).append('\n');  // 1..60 : dans le commentaire §
        }
        sb.append("x".repeat(6000)).append('\n');       // 61 : MONSTRE (> 5000)
        sb.append("ligne apres monstre\n");             // 62 : doit rester COMMENT (stub)
        sb.append("   fin §§\n");                        // 63 : referme le §
        for (int i = 0; i < 900; i++) {
            sb.append("public class A").append(i).append(" {}\n"); // 64..
        }
        String text = sb.toString();
        EditorSession s = sessionDe(text);
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        List<StyledLine> styled = s.getStyledLines();
        // La ligne monstre : voie INTÉGRÉE — l'intégré ignore le §, la
        // ligne « xxxxx… » n'est PAS un commentaire intégré (code
        // C-like banal) alors que le stub l'aurait rendue COMMENT.
        StyledLine monster = styled.get(61);
        assertTrue(monster.spans.stream()
                        .noneMatch(sp -> sp.type == TokenType.COMMENT),
                "la ligne monstre est servie par l'intégré, pas par le stub");
        // La ligne suivante : la pile grammaires est INTACTE — le stub
        // la sert toujours « dans le commentaire § ».
        StyledLine apres = styled.get(62);
        assertTrue(apres.spans.stream()
                        .anyMatch(sp -> sp.type == TokenType.COMMENT),
                "P1-a : l'état grammaires survit à la ligne monstre");
        // La ligne 63 referme : la ligne 64 sort du commentaire (le
        // stub la rend ANNOTATION, pas COMMENT).
        StyledLine horsComment = styled.get(64);
        assertTrue(horsComment.spans.stream()
                        .noneMatch(sp -> sp.type == TokenType.COMMENT),
                "la fermeture §§ referme le commentaire grammaires");
    }

    // ── Épissure : re-chaînage après édition ──────────────────────

    @Test
    void épissure_insertionAuMilieu_équivalenceFraîche() throws Exception {
        String text = javaDoc(120);
        EditorSession s = sessionDe(text);
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        // Insérer un bloc COMMENTÉ multi-lignes au milieu (ouverture non
        // fermée puis fermée) : l'aval doit se re-chaîner correctement.
        int midOffset = text.length() / 2;
        int midLine = s.getDocument().lineForOffset(midOffset);
        s.replaceRange(s.getDocument().lineStart(midLine),
                s.getDocument().lineStart(midLine),
                "/* insertion\n   multi\n*/\n");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        // Référence fraîche : une session reconstruite du même texte.
        String texteFinal = s.getDocument().getText();
        EditorSession ref = sessionDe(texteFinal);
        ref.setLanguage("java");
        if (ref.isAsyncRestylePending()) ref.awaitPendingRestyle();

        assertEquals(ref.getDocument().lineCount(), s.getDocument().lineCount());
        for (int i = 0; i < ref.getDocument().lineCount(); i++) {
            assertEquals(
                    spansToString(ref.getStyledLines().get(i)),
                    spansToString(s.getStyledLines().get(i)),
                    "ligne " + i + " : post-épissure == session fraîche");
        }
    }

    @Test
    void épissure_suppression_équivalenceFraîche() throws Exception {
        String text = javaDoc(120);
        EditorSession s = sessionDe(text);
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        // Supprimer 3 lignes au milieu (dont une fermeture de
        // commentaire multi-lignes : la cascade doit re-stabiliser).
        int midOffset = text.length() / 2;
        int midLine = s.getDocument().lineForOffset(midOffset);
        int from = s.getDocument().lineStart(Math.max(0, midLine - 1));
        int to = s.getDocument().lineStart(Math.min(
                s.getDocument().lineCount() - 1, midLine + 2));
        s.replaceRange(from, to, "");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        String texteFinal = s.getDocument().getText();
        EditorSession ref = sessionDe(texteFinal);
        ref.setLanguage("java");
        if (ref.isAsyncRestylePending()) ref.awaitPendingRestyle();

        for (int i = 0; i < ref.getDocument().lineCount(); i++) {
            assertEquals(
                    spansToString(ref.getStyledLines().get(i)),
                    spansToString(s.getStyledLines().get(i)),
                    "ligne " + i + " : post-suppression == session fraîche");
        }
    }

    // ── Hint viewport ─────────────────────────────────────────────

    @Test
    void hintViewport_sautLoin_couvertureRapide() throws Exception {
        String text = javaDoc(150); // 1200 lignes
        EditorSession s = sessionDe(text);
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();
        s.hintViewport(1100, 1150);
        // Après le hint et l'attente : les lignes hintées sont servies
        // (voie intégrée au minimum — l'entrée est chaînée depuis 0).
        StyledLine l = s.getStyledLines().get(1125);
        assertNotEquals(null, l);
        assertTrue(l.spans.size() >= 1, "la ligne hintée est colorée");
    }
}

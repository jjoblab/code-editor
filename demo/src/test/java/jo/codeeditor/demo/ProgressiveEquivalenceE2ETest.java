package jo.codeeditor.demo;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.textmate.TextMateTokenizerImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Équivalence E2E du régime PROGRESSIF (plan 11 §5, exigence (2) du
 * propriétaire : « la coloration sera exacte ») — avec le VRAI moteur
 * de grammaires (module :cel-textmate) et la VRAIE grammaire java
 * tierce embarquée, sur un document de 960 lignes (régime progressif).
 *
 * <p>Invariant : pour CHAQUE ligne, les spans servis à la demande par
 * la liste paresseuse sont IDENTIQUES à ceux d'une chaîne d'états
 * séquentielle complète sur le même moteur — le découpage en piste /
 * morceaux / cache ne doit RIEN changer au résultat.</p>
 */
public class ProgressiveEquivalenceE2ETest {

    private TextMateTokenizerImpl moteur;

    @Before
    public void activerGrammaires() {
        moteur = TextMateTokenizerImpl.create();
        moteur.loadEmbeddedGrammar("java");
        SyntaxHighlighter.setTextMateTokenizer(moteur);
    }

    @After
    public void nettoyer() {
        SyntaxHighlighter.setTextMateTokenizer(null);
    }

    private static String corpusJava() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("/** Doc du bloc ").append(i).append('\n');
            sb.append(" * sur deux lignes */\n");
            sb.append("package exemple").append(i).append(";\n");
            sb.append("import java.util.List; // note\n");
            sb.append("public final class Classe").append(i).append("<T extends CharSequence> {\n");
            sb.append("    private static final String CHAMP = \"valeur \" + ").append(i).append(";\n");
            sb.append("    private T gabarit;\n");
            sb.append("    public int methode(String arg) {\n");
            sb.append("        // commentaire ligne\n");
            sb.append("        if (arg != null && !arg.isEmpty()) {\n");
            sb.append("            return arg.length() * CHAMP.length();\n");
            sb.append("        }\n");
            sb.append("        return 0;\n");
            sb.append("    }\n");
            sb.append("}\n");
        }
        return sb.toString(); // 1200 lignes > 800 → régime progressif
    }

    private static String spansDe(StyledLine s) {
        StringBuilder sb = new StringBuilder();
        for (LineSpan span : s.spans) {
            sb.append('[').append(span.startCol).append(',')
                    .append(span.endCol).append(':').append(span.type)
                    .append(']');
        }
        return sb.toString();
    }

    @Test
    public void regimeProgressif_identiqueALaChaineComplete() throws Exception {
        String text = corpusJava();
        assertTrue("corpus en régime progressif",
                text.split("\n", -1).length > 800);

        // Référence : chaîne séquentielle complète sur le moteur réel.
        List<String> attendus = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int state = 0;
        for (String line : lines) {
            StyledLine styled = moteur.tokenize(line, state, "java");
            attendus.add(spansDe(styled));
            state = styled.exitState;
        }

        // Sujet : session en régime progressif, remplissage complet.
        EditorSession s = new EditorSession(EditorDocument.of(text));
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        assertEquals("la liste paresseuse couvre tout le document",
                attendus.size(), s.getStyledLines().size());
        int ecarts = 0;
        StringBuilder premierEcart = new StringBuilder();
        for (int i = 0; i < attendus.size(); i++) {
            String obtenu = spansDe(s.getStyledLines().get(i));
            if (!attendus.get(i).equals(obtenu)) {
                ecarts++;
                if (premierEcart.length() == 0) {
                    premierEcart.append("ligne ").append(i).append(" : attendu ")
                            .append(attendus.get(i)).append(" / obtenu ")
                            .append(obtenu);
                }
            }
        }
        assertEquals("équivalence E2E : " + ecarts + " écart(s) — premier : "
                + premierEcart, 0, ecarts);
    }

    @Test
    public void editionPuisEquivalence_recuperée() throws Exception {
        String text = corpusJava();
        EditorSession s = new EditorSession(EditorDocument.of(text));
        s.setLanguage("java");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        // Insérer un commentaire multi-lignes NON fermé au milieu : la
        // piste grammaires doit se re-chaîner sur l'aval.
        int midOffset = text.length() / 2;
        int midLine = s.getDocument().lineForOffset(midOffset);
        s.replaceRange(s.getDocument().lineStart(midLine),
                s.getDocument().lineStart(midLine),
                "/* insertion non fermee\nencore\n");
        if (s.isAsyncRestylePending()) s.awaitPendingRestyle();

        // Référence fraîche sur le texte final.
        String texteFinal = s.getDocument().getText();
        String[] lines = texteFinal.split("\n", -1);
        int state = 0;
        List<String> attendus = new ArrayList<>();
        for (String line : lines) {
            StyledLine styled = moteur.tokenize(line, state, "java");
            attendus.add(spansDe(styled));
            state = styled.exitState;
        }
        int ecarts = 0;
        for (int i = 0; i < attendus.size(); i++) {
            if (!attendus.get(i).equals(spansDe(s.getStyledLines().get(i)))) {
                ecarts++;
            }
        }
        assertEquals("équivalence après édition : " + ecarts + " écart(s)",
                0, ecarts);
    }
}

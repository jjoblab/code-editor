package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static jo.codeeditor.highlight.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Caractérisation du tokéniseur Python (ADR-002, chantier C2 — cliquet
 * de démantèlement) : verrouille le comportement EXACT (bornes et types
 * de chaque span, état de sortie inter-lignes) de
 * {@code PythonTokenizer.stylePython} sur une batterie de lignes
 * représentatives, AVANT puis APRÈS la migration sur le moteur générique
 * {@link GenericTokenizer}. La migration doit garder cette suite verte
 * sans toucher à ses assertions — c'est le critère « octet pour octet »
 * du chantier C2.
 */
class PythonTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return PythonTokenizer.stylePython(line, LexState.NORMAL).spans;
    }

    // ── Commentaires ────────────────────────────────────────────────

    @Test
    void commentaire_dièse() {
        assertEquals(List.of(
                new LineSpan(0, 9, COMMENT)),
                spans("# comment"));
    }

    @Test
    void commentaire_enFinDeLigne() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 5, NUMBER),
                new LineSpan(6, 16, COMMENT)),
                spans("x = 5 # trailing"));
    }

    // ── Chaînes à triple guillemet ──────────────────────────────────

    @Test
    void chaîneTriple_surUneLigne() {
        assertEquals(List.of(
                new LineSpan(0, 15, STRING)),
                spans("\"\"\"docstring\"\"\""));
    }

    @Test
    void chaîneTriple_nonFermée_propageLEtat() {
        StyledLine line = PythonTokenizer.stylePython("\"\"\"multi line", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 13, STRING)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void chaîneTriple_continuationInterLignes() {
        StyledLine line = PythonTokenizer.stylePython("still string\"\"\" x",
                LexState.BLOCK_COMMENT);
        assertEquals(List.of(
                new LineSpan(0, 15, STRING),
                new LineSpan(16, 17, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void chaîneTriple_continuationSansFermante() {
        StyledLine line = PythonTokenizer.stylePython("still string",
                LexState.BLOCK_COMMENT);
        assertEquals(List.of(new LineSpan(0, 12, STRING)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void apostropheTriple_pasUneChaîneTriple() {
        // Garde historique : seuls les triples GUILLEMETS sont gérés —
        // ''' retombe dans la règle de chaîne ordinaire, apostrophe par
        // apostrophe.
        assertEquals(List.of(
                new LineSpan(0, 2, STRING),
                new LineSpan(2, 5, STRING),
                new LineSpan(5, 6, STRING)),
                spans("'''a''"));
    }

    // ── Chaînes à préfixe (f/r/b) ───────────────────────────────────

    @Test
    void chaînePréfixée_fString() {
        // Le préfixe fait partie du jeton de chaîne.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 19, STRING)),
                spans("x = f\"hello {name}\""));
    }

    @Test
    void chaînePréfixée_échappementApparent() {
        assertEquals(List.of(
                new LineSpan(0, 4, PLAIN),
                new LineSpan(5, 6, OPERATOR),
                new LineSpan(7, 14, STRING),
                new LineSpan(14, 16, ESCAPE),
                new LineSpan(16, 17, STRING)),
                spans("name = f\"value\\n\""));
    }

    @Test
    void chaînePréfixée_octets() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 12, STRING)),
                spans("x = b\"bytes\""));
    }

    @Test
    void chaînePréfixée_nonFerméeSansÉtat() {
        StyledLine line = PythonTokenizer.stylePython("x = f\"open", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 10, STRING)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void préfixeSeulEstUnMot() {
        // « f » suivi d'autre chose qu'une quote : un mot ordinaire.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, PLAIN)),
                spans("f x"));
    }

    // ── Chaînes ordinaires ──────────────────────────────────────────

    @Test
    void chaîne_apostrophe() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 12, STRING)),
                spans("s = 'single'"));
    }

    // ── Décorateurs ─────────────────────────────────────────────────

    @Test
    void décorateur_simple() {
        assertEquals(List.of(
                new LineSpan(0, 10, ANNOTATION)),
                spans("@decorator"));
    }

    @Test
    void décorateur_cheminAvecPoints() {
        // Le mot du décorateur inclut les points (@app.route).
        assertEquals(List.of(
                new LineSpan(0, 10, ANNOTATION)),
                spans("@app.route"));
    }

    @Test
    void arobaseEnMilieuDeMot_nEstPasUnDécorateur() {
        // @ précédé d'une LETTRE : pas un décorateur → PUNCT. (En
        // revanche « a @ b » — @ après un blanc — EST un décorateur,
        // la frontière du mot est le critère.)
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 2, PUNCT),
                new LineSpan(2, 3, PLAIN)),
                spans("a@b"));
    }

    // ── Mots ────────────────────────────────────────────────────────

    @Test
    void mot_cléEtFonction() {
        assertEquals(List.of(
                new LineSpan(0, 3, KEYWORD),
                new LineSpan(4, 7, FUNC),
                new LineSpan(7, 8, PUNCT),
                new LineSpan(8, 9, PLAIN),
                new LineSpan(9, 10, PUNCT),
                new LineSpan(10, 11, OPERATOR)),
                spans("def foo(x):"));
    }

    @Test
    void mot_majusculeInitialeAvantLappel() {
        // Garde historique Python : l'initiale majuscule est évaluée
        // AVANT la parenthèse d'appel — Foo( est TYPE, pas FUNC.
        assertEquals(List.of(
                new LineSpan(0, 3, TYPE),
                new LineSpan(3, 4, PUNCT),
                new LineSpan(4, 5, NUMBER),
                new LineSpan(5, 6, PUNCT)),
                spans("Foo(5)"));
    }

    @Test
    void mot_typeIntégré() {
        // str → TYPE via PYTHON_TYPES ; foo( → FUNC ; x → PLAIN.
        assertEquals(List.of(
                new LineSpan(0, 3, TYPE),
                new LineSpan(4, 7, FUNC),
                new LineSpan(7, 8, PUNCT),
                new LineSpan(8, 9, PLAIN),
                new LineSpan(9, 10, PUNCT)),
                spans("str foo(x)"));
    }

    @Test
    void mot_import() {
        assertEquals(List.of(
                new LineSpan(0, 6, KEYWORD),
                new LineSpan(7, 9, PLAIN)),
                spans("import os"));
    }

    @Test
    void mot_toutEnMajuscules() {
        assertEquals(List.of(
                new LineSpan(0, 8, CONSTANT),
                new LineSpan(9, 10, OPERATOR),
                new LineSpan(11, 13, NUMBER)),
                spans("MAX_SIZE = 10"));
    }

    @Test
    void mot_printEstUnAppel() {
        assertEquals(List.of(
                new LineSpan(0, 5, FUNC),
                new LineSpan(5, 6, PUNCT),
                new LineSpan(6, 7, PLAIN),
                new LineSpan(7, 8, PUNCT)),
                spans("print(x)"));
    }

    // ── Nombres ─────────────────────────────────────────────────────

    @Test
    void nombre_hexadécimalEtDécimal() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 8, NUMBER),
                new LineSpan(9, 13, NUMBER)),
                spans("x = 0xFF 3.14"));
    }

    @Test
    void nombre_imaginaireEtSoulignés() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 6, NUMBER),
                new LineSpan(7, 12, NUMBER)),
                spans("c = 3j 1_000"));
    }

    // ── Opérateurs ───────────────────────────────────────────────────

    @Test
    void opérateurs_course() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 4, OPERATOR),
                new LineSpan(5, 6, PLAIN)),
                spans("x != y"));
    }
}

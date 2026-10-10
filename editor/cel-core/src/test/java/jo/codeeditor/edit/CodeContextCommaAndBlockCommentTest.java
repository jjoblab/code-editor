package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 1 (audit v3.41.0) — corrige deux prédicats lexicaux de
 * {@link CodeContext} qui induisent de l'indentation inattendue.
 *
 * <h3>isDanglingOperator : virgule n'est pas un opérateur en suspension</h3>
 *
 * <p>Avant le correctif, la virgule était classée comme opérateur
 * « en suspension » au même titre que {@code +}, {@code -}, {@code *},
 * etc. Cela déclenchait +1 niveau d'indentation après Entrée pour
 * tous les contextes de séparation : listes d'arguments multi-lignes,
 * énumérations Java {@code enum}, JSON {@code "a": 1,}, paramètres
 * Python, listes YAML, etc. La virgule est un <b>séparateur</b>, pas
 * un opérateur de continuation.</p>
 *
 * <h3>isInBlockComment : doit ignorer les chaînes et les commentaires
 * de ligne</h3>
 *
 * <p>Avant le correctif, le prédicat balayait le document depuis
 * l'offset 0, sans tenir compte des chaînes littérales ni des
 * commentaires de ligne : un {@code /*} apparaissant dans une chaîne
 * ({@code "/*"}), dans un glob shell ({@code /tmp/*}), ou dans un
 * commentaire de ligne ({@code // /*}) activait faussement
 * {@code foundOpen = true} et marquait toutes les lignes suivantes
 * comme « dans un commentaire de bloc ». Chaque Entrée insérait alors
 * {@code " * "} jusqu'à la fin du fichier.</p>
 */
class CodeContextCommaAndBlockCommentTest {

    // ── isDanglingOperator : virgule retirée ────────────────────────

    @Test
    void isDanglingOperator_commaIsNotDangling() {
        // Une virgule est un séparateur, pas un opérateur de
        // continuation : ne doit PAS déclencher +1 indent après Entrée.
        // Position 5 = la virgule finale de "foo(a,".
        assertFalse(CodeContext.isDanglingOperator("foo(a,", 5),
                "La virgule ne doit pas être un opérateur en suspension — "
                        + "sinon Entrée après \"foo(a,\" insère +4 espaces");
    }

    @Test
    void isDanglingOperator_commaInJson() {
        // "a": 1, → Entrée ne doit pas indenter (séparateur JSON).
        // Position 6 = la virgule finale.
        assertFalse(CodeContext.isDanglingOperator("\"a\": 1,", 6),
                "La virgule finale d'un paire JSON ne doit pas indenter");
    }

    @Test
    void isDanglingOperator_commaInEnum() {
        // enum { A, B, } → Entrée après une virgule ne doit pas indenter.
        assertFalse(CodeContext.isDanglingOperator("A, B,", 4),
                "La virgule dans un enum ne doit pas être dangling");
    }

    @Test
    void isDanglingOperator_plus_stillDangling() {
        // Régression : les vrais opérateurs de continuation restent reconnus.
        assertTrue(CodeContext.isDanglingOperator("a +", 2),
                "Le + reste un opérateur en suspension");
        assertTrue(CodeContext.isDanglingOperator("a -", 2));
        assertTrue(CodeContext.isDanglingOperator("a *", 2));
        assertTrue(CodeContext.isDanglingOperator("a /", 2));
        assertTrue(CodeContext.isDanglingOperator("a =", 2));
        assertTrue(CodeContext.isDanglingOperator("a &", 2));
        assertTrue(CodeContext.isDanglingOperator("a |", 2));
        assertTrue(CodeContext.isDanglingOperator("a ^", 2));
        assertTrue(CodeContext.isDanglingOperator("a <", 2));
        assertTrue(CodeContext.isDanglingOperator("a >", 2));
    }

    @Test
    void isDanglingOperator_outOfBounds() {
        assertFalse(CodeContext.isDanglingOperator("abc", -1));
        assertFalse(CodeContext.isDanglingOperator("abc", 3));
        assertFalse(CodeContext.isDanglingOperator("", 0));
    }

    // ── isInBlockComment : doit ignorer chaînes et // ──────────────

    @Test
    void isInBlockComment_starSlashInString_doesNotOpenBlock() {
        // Un /* dans une chaîne n'ouvre pas un commentaire de bloc.
        String text = "String s = \"/*\";\nint x = 1;";
        // Position sur la deuxième ligne (après la chaîne) —
        // ne doit PAS être marquée comme dans un commentaire de bloc.
        assertFalse(CodeContext.isInBlockComment(text, text.indexOf("int x")),
                "Un /* dans une chaîne ne doit pas ouvrir un commentaire de bloc");
    }

    @Test
    void isInBlockComment_starSlashInLineComment_doesNotOpenBlock() {
        // Un /* dans un commentaire de ligne // n'ouvre pas de bloc.
        String text = "// un commentaire /* ici\nint x = 1;";
        assertFalse(CodeContext.isInBlockComment(text, text.indexOf("int x")),
                "Un /* dans un // commentaire ne doit pas ouvrir un bloc");
    }

    @Test
    void isInBlockComment_realBlockComment_isDetected() {
        // Un vrai commentaire de bloc est correctement détecté.
        String text = "/* début\ncontenu\nfin */\ncode";
        assertTrue(CodeContext.isInBlockComment(text, text.indexOf("contenu")),
                "Le contenu d'un commentaire de bloc est dans un commentaire de bloc");
    }

    @Test
    void isInBlockComment_afterClosedBlock_isNotInBlock() {
        // Après la fermeture d'un bloc, on n'est plus dans un commentaire.
        String text = "/* b * /\ncode";  // note: avoid early */ close
        // Cas réel avec un commentaire fermé :
        String realText = "/* b */\ncode";
        assertFalse(CodeContext.isInBlockComment(realText, realText.indexOf("code")),
                "Après un commentaire de bloc fermé, on n'est plus dans le bloc");
    }

    @Test
    void isInBlockComment_globShellSlashStar_doesNotOpenBlock() {
        // Un /tmp/* dans une chaîne n'ouvre pas de commentaire de bloc.
        String text = "String glob = \"/tmp/*\";\nint x = 1;";
        assertFalse(CodeContext.isInBlockComment(text, text.indexOf("int x")),
                "Un /* dans un glob shell ne doit pas ouvrir un commentaire de bloc");
    }

    @Test
    void isInBlockComment_outOfBounds() {
        assertFalse(CodeContext.isInBlockComment("", 0));
        assertFalse(CodeContext.isInBlockComment("abc", -1));
        assertFalse(CodeContext.isInBlockComment("abc", 3));
    }
}

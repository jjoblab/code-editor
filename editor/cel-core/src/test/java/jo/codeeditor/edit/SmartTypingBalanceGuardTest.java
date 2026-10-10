package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 1 (audit v3.41.0) — correctif de la garde d'équilibre dans
 * {@link SmartTyping#smartInsert}.
 *
 * <p>Avant le correctif, la condition d'auto-fermeture des
 * crochets était :</p>
 *
 * <pre>
 * if (balance &lt; BracketPairs.BALANCE_SCAN_LIMIT) {  // BALANCE_SCAN_LIMIT = 50 000
 *     return new RangeEdit(pos, pos, "" + ch + closer, pos + 1);
 * }
 * </pre>
 *
 * <p>Cette garde est <b>toujours vraie</b> pour des documents
 * normaux (balance ~ 0 à quelques dizaines, jamais 50k+), donc
 * l'auto-close se déclenche dans tous les contextes :
 * <ul>
 *   <li>{@code (} devant un identifiant donne {@code (\|)foo} — l'utilisateur
 *       veut en général envelopper un identifiant/expression, pas créer une
 *       paire vide.</li>
 *   <li>{@code (} dans un commentaire de ligne donne {@code // foo(\|)} —
 *       insérer une paire dans du texte mort n'apporte rien.</li>
 *   <li>{@code (} dans une chaîne littérale donne {@code "x(\|)y"} — l'utilisateur
 *       tape du texte, pas du code.</li>
 * </ul></p>
 *
 * <p>Le correctif applique les deux gardes suivantes, calquées
 * sur celles qui existent déjà pour les guillemets (ligne 53-58,
 * garde {@code afterIdent}) :</p>
 *
 * <ol>
 *   <li>Pas d'auto-close si le caractère suivant est un caractère
 *       d'identifiant (lettre, chiffre, {@code _}, {@code $}) —
 *       l'utilisateur est probablement en train d'envelopper une
 *       expression.</li>
 *   <li>Pas d'auto-close si la position courante est à l'intérieur
 *       d'une chaîne ou d'un commentaire (ligne ou bloc).</li>
 * </ol>
 *
 * <p>La garde {@code balance < BALANCE_SCAN_LIMIT} est conservée
 * comme filet de sécurité pour les documents pathologiques (50k+
 * ouvreurs non fermés) mais ne sera plus la seule condition.</p>
 */
class SmartTypingBalanceGuardTest {

    // ── CasBroken : ( devant un identifiant → pas d'auto-close ────

    @Test
    void smartInsert_parenBeforeIdent_doesNotAutoClose() {
        // "foo" avec curseur au début (pos=0) ; user tape '('.
        // Le prochain caractère ('f') est un identifiant → pas d'auto-close.
        RangeEdit e = EditOps.smartInsert("foo", 0, 0, '(', "java");
        // On attend juste l'insertion de '(' (text == "("), pas "()" .
        assertEquals("(", e.text,
                "Pas d'auto-close de ( devant un identifiant — "
                        + "l'utilisateur veut probablement envelopper foo");
        assertEquals(1, e.caret);
    }

    @Test
    void smartInsert_bracketBeforeIdent_doesNotAutoClose() {
        // Même logique pour [ et { .
        RangeEdit e1 = EditOps.smartInsert("foo", 0, 0, '[', "java");
        assertEquals("[", e1.text);
        RangeEdit e2 = EditOps.smartInsert("foo", 0, 0, '{', "java");
        assertEquals("{", e2.text);
    }

    @Test
    void smartInsert_parenBeforeDigit_doesNotAutoClose() {
        // Le prochain caractère est un chiffre → pas d'auto-close.
        RangeEdit e = EditOps.smartInsert("123", 0, 0, '(', "java");
        assertEquals("(", e.text);
    }

    @Test
    void smartInsert_parenBeforeUnderscoreOrDollar_doesNotAutoClose() {
        // _ et $ sont des caractères d'identifiant.
        RangeEdit e1 = EditOps.smartInsert("_var", 0, 0, '(', "java");
        assertEquals("(", e1.text);
        RangeEdit e2 = EditOps.smartInsert("$var", 0, 0, '(', "java");
        assertEquals("(", e2.text);
    }

    // ── CasOk : ( devant un blanc ou un fermant → auto-close ──────

    @Test
    void smartInsert_parenBeforeSpace_autoCloses() {
        // " foo" → user tape ( au début. Next char = ' ' (blanc) → auto-close.
        RangeEdit e = EditOps.smartInsert(" foo", 0, 0, '(', "java");
        assertEquals("()", e.text,
                "Auto-close déclenche devant un blanc");
        assertEquals(1, e.caret);
    }

    @Test
    void smartInsert_parenBeforeCloser_autoCloses() {
        // Next char = ')' (un fermant) → auto-close.
        RangeEdit e = EditOps.smartInsert(")", 0, 0, '(', "java");
        assertEquals("()", e.text);
        assertEquals(1, e.caret);
    }

    @Test
    void smartInsert_parenAtEmptyDocument_autoCloses() {
        // Document vide → pas de prochain caractère → auto-close.
        RangeEdit e = EditOps.smartInsert("", 0, 0, '(', "java");
        assertEquals("()", e.text);
        assertEquals(1, e.caret);
    }

    @Test
    void smartInsert_parenAtEndOfDocument_autoCloses() {
        // "abc" + ( à la fin → pas de prochain caractère → auto-close.
        RangeEdit e = EditOps.smartInsert("abc", 3, 3, '(', "java");
        assertEquals("()", e.text);
        assertEquals(4, e.caret);
    }

    // ── CasBroken : ( dans un commentaire ou une chaîne ──────────

    @Test
    void smartInsert_parenInLineComment_doesNotAutoClose() {
        // "// foo(" → user tape ( après "foo". Curseur à la fin de "foo("
        // dans un commentaire de ligne → pas d'auto-close.
        String text = "// foo";
        int pos = text.length(); // après "foo"
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '(', "java");
        assertEquals("(", e.text,
                "Pas d'auto-close de ( dans un commentaire de ligne");
    }

    @Test
    void smartInsert_parenInString_doesNotAutoClose() {
        // "\"abc" → user tape ( à l'intérieur de la chaîne non fermée.
        String text = "\"abc";
        int pos = 3; // entre 'b' et 'c' — dans la chaîne
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '(', "java");
        assertEquals("(", e.text,
                "Pas d'auto-close de ( dans un littéral de chaîne");
    }

    @Test
    void smartInsert_parenInBlockComment_doesNotAutoClose() {
        // "/* foo */" → user tape ( à l'intérieur du bloc.
        String text = "/* foo */";
        int pos = 5; // après "foo " — dans le bloc
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '(', "java");
        assertEquals("(", e.text,
                "Pas d'auto-close de ( dans un commentaire de bloc");
    }

    // ── Régression : cas existants toujours valides ───────────────

    @Test
    void smartInsert_parenAtEmptyPosition_stillAutoCloses() {
        // Régression : "hello" + ( à la fin (next char = EOF) → auto-close.
        RangeEdit e = EditOps.smartInsert("hello", 5, 5, '(', "java");
        assertEquals("()", e.text);
        assertEquals(6, e.caret);
    }

    @Test
    void smartInsert_braceBeforeCloserBrace_autoCloses() {
        // "}" + { au début → next char = '}' (fermant) → auto-close.
        RangeEdit e = EditOps.smartInsert("}", 0, 0, '{', "java");
        assertEquals("{}", e.text);
        assertEquals(1, e.caret);
    }
}

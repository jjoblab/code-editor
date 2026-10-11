package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 2/3 (audit v3.41.0) — dédentation électrique pour les
 * langages C_LIKE. Quand l'utilisateur tape {@code }} sur une
 * ligne qui ne contient que de l'indentation (et le caret au
 * début), le caret est déplacé à la colonne 0 (ou au niveau
 * d'indentation de l'accolade ouvrante correspondante) AVANT
 * l'insertion du {@code }}.
 *
 * <p>Comportement attendu (cf. les éditeurs de code de bureau, les IDE Android) :</p>
 * <pre>
 * if (cond) {
 *     |curseur    ← l'utilisateur tape }
 * }
 * </pre>
 * <p>Le {@code }} doit être inséré à la colonne 0, pas après
 * les 4 espaces d'indentation :</p>
 * <pre>
 * if (cond) {
 *     code
 * }|curseur
 * </pre>
 *
 * <p>Périmètre minimal (décision 4.4) : seulement pour les
 * langages C_LIKE (Java, Kotlin, C/C++, Go, Rust, JS/TS,
 * PHP, Swift, Dart, Groovy, Scala). Les autres langages
 * (Python, YAML, XML, etc.) ne sont pas affectés.</p>
 */
class SmartTypingElectricDedentTest {

    /**
     * Cas 1 : tape {@code }} sur une ligne qui ne contient que
     * 4 espaces — le caret doit être déplacé à la colonne 0
     * avant l'insertion du {@code }}.
     */
    @Test
    void electricDedent_braceOnIndentedLine_dedentsBeforeInsert() {
        // « if (cond) {\n    » — caret après les 4 espaces.
        String text = "if (cond) {\n    ";
        int pos = text.length();
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '}', "java");
        // Le texte inséré doit être « } » (sans les 4 espaces
        // avant). Le RangeEdit doit supprimer l'indentation
        // précédente et insérer « } » à la colonne 0.
        // start = début de l'indentation (pos - 4), end = pos,
        // text = « } ».
        assertEquals("}", e.text,
                "Le } doit être inséré SANS l'indentation précédente — "
                        + "text='" + e.text + "'");
        assertEquals(pos - 4, e.start,
                "Le RangeEdit doit commencer au début de l'indentation — "
                        + "start=" + e.start);
        assertEquals(pos, e.end,
                "Le RangeEdit doit finir à la position du caret — "
                        + "end=" + e.end);
    }

    /**
     * Cas 2 : tape {@code }} sur une ligne qui contient du code
     * avant le caret — pas de dédentation (le {@code }} est
     * inséré normalement).
     */
    @Test
    void electricDedent_braceAfterCode_noDedent() {
        // « int x = 1; }| » — caret après « 1; } ».
        String text = "    int x = 1; ";
        int pos = text.length();
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '}', "java");
        // Pas de dédentation — le } est inséré normalement
        // (auto-close peut se déclencher, mais pas de dédentation).
        // Le texte doit contenir « } » et start == pos (pas de
        // suppression d'indentation).
        assertTrue(e.text.contains("}"),
                "Le } doit être inséré normalement après du code — "
                        + "text='" + e.text + "'");
    }

    /**
     * Cas 3 : tape {@code }} sur une ligne vide (pas d'indentation)
     * — pas de dédentation (rien à dédenter).
     */
    @Test
    void electricDedent_braceOnEmptyLine_noDedent() {
        String text = "if (cond) {\n";
        int pos = text.length();
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '}', "java");
        // Pas de dédentation — le } est inséré normalement.
        assertTrue(e.text.contains("}"),
                "Le } doit être inséré normalement sur une ligne vide — "
                        + "text='" + e.text + "'");
    }

    /**
     * Cas 4 : tape un caractère autre que {@code }} sur une
     * ligne indentée — pas de dédentation.
     */
    @Test
    void electricDedent_otherCharOnIndentedLine_noDedent() {
        String text = "if (cond) {\n    ";
        int pos = text.length();
        RangeEdit e = EditOps.smartInsert(text, pos, pos, 'x', "java");
        // Pas de dédentation — « x » est inséré normalement
        // après les 4 espaces.
        assertEquals("x", e.text,
                "Le 'x' doit être inséré normalement après l'indentation — "
                        + "text='" + e.text + "'");
    }

    /**
     * Cas 5 : dédentation électrique pour Kotlin (C_LIKE).
     */
    @Test
    void electricDedent_kotlin_dedentsBeforeInsert() {
        String text = "fun foo() {\n    ";
        int pos = text.length();
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '}', "kotlin");
        assertEquals("}", e.text,
                "Kotlin : Le } doit être inséré SANS l'indentation — "
                        + "text='" + e.text + "'");
        assertEquals(pos - 4, e.start);
    }

    /**
     * Cas 6 : Python (non C_LIKE) — pas de dédentation électrique
     * pour {@code }}.
     */
    @Test
    void electricDedent_python_noDedent() {
        String text = "if x:\n    ";
        int pos = text.length();
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '}', "python");
        // Python n'utilise pas de } — pas de dédentation électrique.
        // Le } est inséré normalement.
        assertEquals("}", e.text,
                "Python : Le } doit être inséré normalement (pas de "
                        + "dédentation électrique pour les langages non C_LIKE) — "
                        + "text='" + e.text + "'");
    }

    /**
     * Cas 7 : dédentation électrique à 2 espaces.
     */
    @Test
    void electricDedent_twoSpaceIndent_dedentsCorrectly() {
        // Document 2-espaces : « if (cond) {\n  »
        String text = "if (cond) {\n  ";
        int pos = text.length();
        RangeEdit e = EditOps.smartInsert(text, pos, pos, '}', "java");
        assertEquals("}", e.text);
        // L'indentation est 2 espaces, donc start = pos - 2.
        assertEquals(pos - 2, e.start);
    }
}

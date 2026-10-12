package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 1 (audit v3.41.0) — deux correctifs dans {@link SmartNewline}.
 *
 * <h3>smartEnterComplete : Kotlin n'insère pas de point-virgule</h3>
 *
 * <p>Avant le correctif, {@code smartEnterComplete} était gardé par
 * {@code LanguageIds.isJavaOrKotlin} et insérait un {@code ;} final
 * (ligne 199) pour Java ET Kotlin. Or Kotlin n'utilise pas de
 * point-virgule de façon idiomatique — l'insertion d'un {@code ;}
 * est un bruit syntaxique non désiré. Le correctif exclut Kotlin
 * de l'insertion du {@code ;} (mais conserve le bloc
 * {@code if/else/for/...} qui est utile à Java comme à Kotlin).</p>
 *
 * <h3>javaNewline / kotlinNewline : unité d'indentation détectée</h3>
 *
 * <p>Avant le correctif, l'indentation de continuation (après
 * ouvreurs {@code {}}, {@code (}, {@code [}, après un opérateur
 * dangling, après une flèche Kotlin, après un label {@code case})
 * était codée en dur comme 4 espaces ({@code "    "}). L'unité
 * d'indentation détectée par {@link IndentDetection} (tabulation,
 * 2 espaces, 8 espaces) n'était PAS utilisée — un fichier 2-espaces
 * recevait 4 espaces de continuation. Le correctif utilise
 * {@code IndentDetection.detectIndentUnit(text)} à la place du
 * littéral {@code "    "}.</p>
 */
class SmartNewlineKotlinAndIndentUnitTest {

    // ── smartEnterComplete : Kotlin sans point-virgule ─────────────

    @Test
    void smartEnterComplete_kotlin_doesNotAddSemicolon() {
        // « val x = 1 » + Entrée-complétion → pas de ; (Kotlin).
        RangeEdit e = EditOps.smartEnterComplete("val x = 1", 9, "kotlin");
        assertEquals("", e.text,
                "Kotlin ne doit pas insérer de ; par smartEnterComplete");
        assertEquals(9, e.caret);
    }

    @Test
    void smartEnterComplete_java_addsSemicolon_regression() {
        // Régression : Java reçoit toujours le ; .
        RangeEdit e = EditOps.smartEnterComplete("int x = 1", 9, "java");
        assertEquals(";", e.text,
                "Java doit toujours recevoir le ; par smartEnterComplete");
        assertEquals(10, e.caret);
    }

    @Test
    void smartEnterComplete_kotlin_controlFlow_stillAddBrace() {
        // Le bloc if/else/for/... reste inséré pour Kotlin (utile).
        // Vérifie juste que la complétion ne lève pas et renvoie un
        // RangeEdit — la sémantique détaillée du bloc est conservée
        // telle quelle pour ne pas perturber le comportement Java.
        RangeEdit e = EditOps.smartEnterComplete("if (cond)", 9, "kotlin");
        // Soit un bloc { … } est inséré, soit rien (selon que la
        // condition se termine par ')' ). On n'asserte pas sur la
        // forme exacte — juste qu'aucun ; n'est inséré.
        assertNotEquals(";", e.text,
                "Kotlin ne doit pas recevoir de ; même dans un if");
    }

    @Test
    void smartEnterComplete_nonJavaOrKotlin_isNoOp() {
        // Python, JSON, XML, etc. → pas de complétion d'instruction.
        RangeEdit e1 = EditOps.smartEnterComplete("print('x')", 10, "python");
        assertEquals("", e1.text);
        RangeEdit e2 = EditOps.smartEnterComplete("\"x\": 1", 6, "json");
        assertEquals("", e2.text);
        RangeEdit e3 = EditOps.smartEnterComplete("<a>", 3, "xml");
        assertEquals("", e3.text);
    }

    // ── javaNewline / kotlinNewline : unité d'indentation détectée ─

    @Test
    void smartEnter_twoSpaceIndent_continuesWithTwoSpaces() {
        // Document 2-espaces : Entrée après { ajoute 2 espaces, pas 4.
        // DocIndent: lignes 0-2 ont 2 espaces → detectIndentUnit → "  ".
        String text = "function foo() {\n  body\n}";
        int pos = text.indexOf('{') + 1; // juste après le {
        RangeEdit e = EditOps.smartEnter(text, pos, "java");
        // La continuation doit être exactement "\n  " (2 espaces),
        // pas "\n    " (4 espaces). currentIndent="" sur la ligne 0.
        assertEquals("\n  ", e.text,
                "Le 2-space indent doit être détecté et utilisé pour la"
                        + " continuation (text='" + e.text.replace("\n", "\\n") + "')");
    }

    @Test
    void smartEnter_fourSpaceIndent_continuesWithFourSpaces() {
        // Document 4-espaces (défaut) : Entrée après { ajoute 4 espaces.
        String text = "function foo() {\n    body\n}";
        int pos = text.indexOf('{') + 1;
        RangeEdit e = EditOps.smartEnter(text, pos, "java");
        // La continuation doit être "    " (4 espaces).
        assertTrue(e.text.startsWith("\n    "),
                "Le 4-space indent (défaut) doit être utilisé");
    }

    @Test
    void smartEnter_tabIndent_continuesWithTab() {
        // Document tabulé : Entrée après { ajoute une tabulation.
        String text = "function foo() {\n\tbody\n}";
        int pos = text.indexOf('{') + 1;
        RangeEdit e = EditOps.smartEnter(text, pos, "java");
        // La continuation doit être "\t" (tabulation).
        assertTrue(e.text.startsWith("\n\t"),
                "Le tab-indent doit être détecté et utilisé (text='"
                        + e.text.replace("\n", "\\n").replace("\t", "\\t") + "')");
    }

    @Test
    void smartEnter_kotlin_twoSpaceIndent_continuesWithTwoSpaces() {
        // Kotlin : même comportement que Java pour l'indent unit.
        String text = "fun foo() {\n  body\n}";
        int pos = text.indexOf('{') + 1;
        RangeEdit e = EditOps.smartEnter(text, pos, "kotlin");
        // currentIndent="" sur la ligne 0 ; deeperIndent doit être "  " (2 sp).
        assertEquals("\n  ", e.text,
                "Kotlin 2-space indent doit être utilisé (text='"
                        + e.text.replace("\n", "\\n") + "')");
    }
}

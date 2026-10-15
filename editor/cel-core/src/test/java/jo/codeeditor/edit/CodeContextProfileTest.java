package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (chantier C1, étape 4) — les délimiteurs de
 * chaîne/commentaire deviennent des <b>données du profil</b> et le
 * contexte lexical ({@code CodeContext}) les lit au lieu de coder en
 * dur {@code //}, {@code /* *&#47;} et les guillemets C.
 *
 * <p>Avant C1-é4, {@link CodeContext#isInString},
 * {@link CodeContext#isInBlockComment} et
 * {@link CodeContext#isInLineComment} ne connaissaient QUE les
 * délimiteurs C — quel que soit le langage : un commentaire
 * {@code #} Python ou Lua {@code --} n'était pas vu (l'auto-close
 * insérait des paires dans du texte mort, la complétion s'ouvrait
 * dans un commentaire), un langage à backticks n'était pas reconnu
 * comme chaîne. Désormais le profil porte {@code stringQuotes}
 * (guillemets du contexte lexical), la syntaxe de commentaire
 * existante ({@code commentSyntax}) est consommée par le scanner, et
 * le délimiteur de documentation ({@code docComment}) pilote la
 * continuation «&#160;*&#160;» du saut de ligne.</p>
 */
class CodeContextProfileTest {

    @AfterEach
    void tearDown() {
        LanguageRegistry.unregister("t-jsk");
        LanguageRegistry.unregister("t-lua");
        LanguageRegistry.unregister("t-doc");
    }

    // ── stringQuotes : guillemets du contexte lexical ────────────

    @Test
    void langageMaison_backtickEstUneChaîne() {
        LanguageProfile p = LanguageProfile.builder("t-jsk")
                .family(SyntaxFamily.C_LIKE)
                .stringQuotes('"', '`')
                .build();
        assertTrue(CodeContext.isInString("var s = `abc", 12, p),
                "Une backtick déclarée dans stringQuotes ouvre une chaîne");
        assertFalse(CodeContext.isInString("var s = 'abc", 12, p),
                "L'apostrophe NON déclarée n'ouvre pas une chaîne pour ce"
                        + " langage");
    }

    @Test
    void défautGuillemets_quotesEtApostrophePartout() {
        // Comportement historique préservé : " et ' pour tout langage
        // (y compris XML — les valeurs d'attribut sont des chaînes).
        LanguageProfile xml = LanguageRegistry.forName("xml");
        assertTrue(CodeContext.isInString("a=\"valeur", 6, xml));
        LanguageProfile java = LanguageRegistry.forName("java");
        assertTrue(CodeContext.isInString("s='x", 3, java));
    }

    // ── Commentaires du profil vus par le contexte lexical ───────

    @Test
    void python_dièseEstUnCommentaireDeLigne() {
        LanguageProfile py = LanguageRegistry.forName("python");
        assertTrue(CodeContext.isInLineComment("# un commentaire", 10, py),
                "Le # de la syntaxe Python doit être vu comme commentaire");
        assertTrue(CodeContext.isInStringOrComment("# un commentaire", 10, py),
                "isInStringOrComment doit englober le commentaire # Python");
    }

    @Test
    void luaMaison_blocLongEtLigne() {
        LanguageProfile p = LanguageProfile.builder("t-lua")
                .family(SyntaxFamily.LUA)
                .commentSyntax(new CommentSyntax("--", "--[[", "]]"))
                .build();
        assertTrue(CodeContext.isInLineComment("-- note", 4, p),
                "Le -- déclaré doit être vu comme commentaire de ligne");
        assertTrue(CodeContext.isInBlockComment("--[[ bloc\nencore", 12, p),
                "Le bloc --[[ … ]] doit être vu comme commentaire de bloc");
        assertFalse(CodeContext.isInBlockComment("-- simple", 5, p),
                "Un commentaire de ligne Lua n'est pas un bloc");
    }

    @Test
    void python_leDièseVu_leSlashCommentaireNeLEstPlus() {
        LanguageProfile py = LanguageRegistry.forName("python");
        // Correction collatérale : le // du C n'est PAS un commentaire
        // Python — l'ancien scanner C le voyait à tort.
        assertFalse(CodeContext.isInLineComment("a = b // c", 8, py),
                "Le // n'est pas un commentaire Python (le scanner lit la"
                        + " syntaxe du profil, plus les délimiteurs C en"
                        + " dur)");
    }

    // ── EditOps.isInStringOrComment : variante par langage ───────

    @Test
    void editOps_varianteLangage_litLeProfil() {
        assertTrue(EditOps.isInStringOrComment("# commentaire", 8, "python"),
                "La variante (text, pos, language) doit résoudre le profil");
        assertFalse(EditOps.isInStringOrComment("a = b // c", 8, "python"),
                "Commentaire C vu en Python : la variante langage doit"
                        + " corriger");
        // Repli historique : sans langage (null/inconnu), les
        // délimiteurs C restent la référence — compatibilité des
        // appelants existants.
        assertTrue(EditOps.isInStringOrComment("// c", 2, null));
    }

    // ── SmartTyping : auto-close éteint dans un commentaire # ────

    @Test
    void smartInsert_pasDAutoCloseDansUnCommentairePython() {
        RangeEdit e = EditOps.smartInsert("# comm", 6, 6, '(', "python");
        assertEquals("(", e.text,
                "Pas d'auto-close de crochet dans un commentaire # Python —"
                        + " le scanner voit désormais le délimiteur du"
                        + " profil");
    }

    // ── docComment + continuation de bloc : la newline lit la table ──

    @Test
    void java_milieuDeBloc_insèreÉtoile() {
        // Garde : comportement Java inchangé (continuation « * »).
        RangeEdit e = EditOps.smartEnter("/* bloc\n   corps", 13, "java");
        assertTrue(e.text.startsWith("\n    * "),
                "Entrée dans un bloc Java : continuation « * » préservée —"
                        + " text='" + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void java_ouvertureDeDoc_insèreÉtoile() {
        // Garde : le motif /** + Entrée insère « * » (comportement
        // historique).
        RangeEdit e = EditOps.smartEnter("/**", 3, "java");
        assertTrue(e.text.startsWith("\n * "),
                "Ouverture de /** Java : continuation « * » préservée —"
                        + " text='" + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void langageMaison_docTroisSlashes_continueEnTroisSlashes() {
        LanguageProfile p = LanguageProfile.builder("t-doc")
                .family(SyntaxFamily.C_LIKE)
                .commentSyntax(new CommentSyntax("//", "/*", "*/"))
                .docComment("///", "*/")
                .blockCommentContinuation("/// ")
                .build();
        LanguageRegistry.register(p);
        RangeEdit e = EditOps.smartEnter("///", 3, "t-doc");
        assertEquals("\n/// ", e.text,
                "Un langage maison déclarant un doc /// et sa continuation"
                        + " doit les recevoir — l'ancien motif C (/ + *)"
                        + " déclenchait une indentation d'opérateur —"
                        + " text='" + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void lua_dansUnBlocLong_pasDEContinuationC() {
        // Lua intégré : Entrée dans un bloc --[[ … ]] ne doit PAS
        // insérer la continuation « * » du C (le bloc n'en a pas).
        RangeEdit e = EditOps.smartEnter("--[[ bloc\n  suite", 15, "lua");
        assertFalse(e.text.startsWith("\n  *"),
                "Pas de continuation « * » dans un bloc Lua — text='"
                        + e.text.replace("\n", "\\n") + "'");
    }
}

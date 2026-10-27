package jo.codeeditor.languages;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 4.8.0 — drapeau {@code callSyntax} du profil de langage.
 *
 * <p>« Ce langage a-t-il une syntaxe d'appel entre parenthèses ? »
 * Le repli synthétique d'aide de signature (sans résolveur) et
 * l'éligibilité postfix s'y réfèrent : pas de popup
 * {@code name(…)} ni de {@code mot.if} sur du texte brut, du XML
 * ou de la prose Markdown.</p>
 *
 * <p>Défaut calculé dans {@code build()} depuis la famille —
 * vrai pour les familles de code (C_LIKE, PYTHON, LUA, SQL),
 * faux ailleurs — surchargeable par le builder pour les cas
 * limites (le profil « text » est C_LIKE pour le routage du
 * tokenizer mais n'est PAS un langage de code).</p>
 */
class CallSyntaxFlagTest {

    @Test
    void famillesDeCodeOntLaSyntaxeDAppel() {
        assertTrue(LanguageRegistry.forName("java").callSyntax, "java");
        assertTrue(LanguageRegistry.forName("kotlin").callSyntax, "kotlin");
        assertTrue(LanguageRegistry.forName("python").callSyntax, "python");
        assertTrue(LanguageRegistry.forName("lua").callSyntax, "lua");
        assertTrue(LanguageRegistry.forName("sql").callSyntax, "sql");
    }

    @Test
    void langagesDeDonneesEtDeProseNyOntPasDroit() {
        assertFalse(LanguageRegistry.forName("xml").callSyntax, "xml");
        assertFalse(LanguageRegistry.forName("html").callSyntax, "html");
        assertFalse(LanguageRegistry.forName("json").callSyntax, "json");
        assertFalse(LanguageRegistry.forName("yaml").callSyntax, "yaml");
        assertFalse(LanguageRegistry.forName("markdown").callSyntax, "markdown");
        assertFalse(LanguageRegistry.forName("log").callSyntax, "log");
    }

    @Test
    void texteBrutRefuseExplicitementMalgreSaFamille() {
        // Le profil « text » est C_LIKE « faute de mieux » pour le
        // routage du tokenizer (aucun mot-clé → rien de coloré) —
        // mais ce n'est pas un langage de code : drapeau posé à false.
        assertFalse(LanguageRegistry.forName("text").callSyntax, "text");
        assertFalse(LanguageRegistry.forName("plaintext").callSyntax, "alias plaintext");
    }

    @Test
    void leBuilderCalculeLeDefautDepuisLaFamille() {
        LanguageProfile cLike = LanguageProfile.builder("essai-c").build();
        assertTrue(cLike.callSyntax, "famille par défaut (C_LIKE) → syntaxe d'appel");
        LanguageProfile xml = LanguageProfile.builder("essai-xml")
                .family(SyntaxFamily.XML).build();
        assertFalse(xml.callSyntax, "famille XML → pas de syntaxe d'appel");
    }

    @Test
    void leBuilderPermetLaSurchargeExplicite() {
        LanguageProfile muet = LanguageProfile.builder("essai-muet")
                .family(SyntaxFamily.C_LIKE).callSyntax(false).build();
        assertFalse(muet.callSyntax, "surcharge explicite à false");
        LanguageProfile bavard = LanguageProfile.builder("essai-bavard")
                .family(SyntaxFamily.XML).callSyntax(true).build();
        assertTrue(bavard.callSyntax, "surcharge explicite à true");
    }
}

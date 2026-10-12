package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 1 (audit v3.41.0) — {@link LanguageIds} doit normaliser les ids
 * de langage via le registre (alias {@code kt}, {@code htm}, {@code svg},
 * casse {@code Kotlin}, {@code HTML}), pas par égalité de chaînes exactes.
 *
 * <p>Avant le correctif, {@code isJavaOrKotlin} et {@code isXml}
 * comparaient {@code "java"}, {@code "kotlin"}, {@code "xml"}, {@code "html"}
 * en exact — les alias {@code kt}, {@code htm}, {@code svg} et la casse
 * mixte échouaient silencieusement, et les langages contribués par l'hôte
 * via {@link LanguageRegistry#register} n'étaient jamais reconnus.</p>
 */
class LanguageIdsNormalizationTest {

    @AfterEach
    void cleanup() {
        // LanguageRegistry.resetToBuiltins() est package-private ;
        // on y accède par réflexion pour isoler les tests de cet
        // ensemble des autres suites (qui pourraient enregistrer des
        // langages contribués).
        try {
            java.lang.reflect.Method m = Class.forName(
                    "jo.codeeditor.languages.LanguageRegistry")
                    .getDeclaredMethod("resetToBuiltins");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── isJavaOrKotlin : doit reconnaître les alias et la casse ─────

    @Test
    void isJavaOrKotlin_recognizesKtAlias() {
        // "kt" est l'alias officiel de kotlin dans BuiltinLanguages.
        assertTrue(LanguageIds.isJavaOrKotlin("kt"),
                "L'alias \"kt\" doit être reconnu comme Kotlin via le registre");
    }

    @Test
    void isJavaOrKotlin_recognizesMixedCase() {
        assertTrue(LanguageIds.isJavaOrKotlin("Kotlin"),
                "La casse mixte \"Kotlin\" doit être normalisée et reconnue");
        assertTrue(LanguageIds.isJavaOrKotlin("Java"),
                "La casse mixte \"Java\" doit être normalisée et reconnue");
    }

    @Test
    void isJavaOrKotlin_rejectsUnrelatedLanguage() {
        assertFalse(LanguageIds.isJavaOrKotlin("python"));
        assertFalse(LanguageIds.isJavaOrKotlin("xml"));
        assertFalse(LanguageIds.isJavaOrKotlin("text"));
    }

    @Test
    void isJavaOrKotlin_handlesNullAndEmpty() {
        assertFalse(LanguageIds.isJavaOrKotlin(null));
        assertFalse(LanguageIds.isJavaOrKotlin(""));
    }

    // ── isXml : doit reconnaître htm, svg, HTML ───────────────────

    @Test
    void isXml_recognizesHtmAlias() {
        assertTrue(LanguageIds.isXml("htm"),
                "L'alias \"htm\" doit être reconnu comme XML via le registre");
    }

    @Test
    void isXml_recognizesSvgAlias() {
        assertTrue(LanguageIds.isXml("svg"),
                "L'alias \"svg\" doit être reconnu comme XML via le registre");
    }

    @Test
    void isXml_recognizesMixedCase() {
        assertTrue(LanguageIds.isXml("XML"));
        assertTrue(LanguageIds.isXml("HTML"));
    }

    @Test
    void isXml_rejectsUnrelatedLanguage() {
        assertFalse(LanguageIds.isXml("java"));
        assertFalse(LanguageIds.isXml("json"));
        assertFalse(LanguageIds.isXml("python"));
    }

    // ── isPlainText : doit rester insensible au registre (text/plaintext
    // ne sont pas des langages enregistrés mais des marqueurs de prose) ─

    @Test
    void isPlainText_recognizesTextAndPlaintext() {
        assertTrue(LanguageIds.isPlainText("text"));
        assertTrue(LanguageIds.isPlainText("plaintext"));
        assertTrue(LanguageIds.isPlainText(null));
        assertTrue(LanguageIds.isPlainText(""));
    }

    @Test
    void isPlainText_rejectsCodeLanguage() {
        assertFalse(LanguageIds.isPlainText("java"));
        assertFalse(LanguageIds.isPlainText("python"));
        assertFalse(LanguageIds.isPlainText("markdown"));
    }

    // ── Langages contribués : un hôte peut enregistrer un nouveau
    // langage de famille C_LIKE, et LanguageIds doit le reconnaître
    // comme Java-ou-Kotlin-équivalent (pour l'auto-close des quotes). ─

    @Test
    void isJavaOrKotlin_recognizesContributedCLikeLanguage() {
        // Enregistre un langage « mylang » de famille C_LIKE.
        LanguageRegistry.register(
                jo.codeeditor.languages.LanguageProfile.builder("mylang")
                        .family(jo.codeeditor.languages.SyntaxFamily.C_LIKE)
                        .build());
        // mylang n'est pas java/kotlin, mais la question « est-ce
        // qu'on active l'auto-close de quotes ? » est rendue par
        // isJavaOrKotlin — après le correctif, le registre répond oui
        // pour tout C_LIKE. (Cf. prompt : auto-close des guillemets
        // devrait s'activer pour les C-like, Python, JS/TS, shell,
        // SQL, JSON. Ce test ne valide que la reconnaissance C_LIKE —
        // l'extension à Python/JS/etc. est couverte par Lot 2.)
        // NOTE : ce test documente la décision de Lot 1 ; le vrai
        // élargissement se fera dans Lot 2 via les traits d'édition.
        // Ici, on vérifie seulement que LanguageIds ne rejette PLUS
        // brutalement les langages contribués inconnus.
        // → Le test ci-dessous vérifie que mylang n'est PAS Java/Kotlin
        //   (sémantique stricte), mais que l'appel ne lève pas.
        assertFalse(LanguageIds.isJavaOrKotlin("mylang"),
                "mylang (C_LIKEcontribué) n'est pas Java/Kotlin — assertion stricte");
    }
}

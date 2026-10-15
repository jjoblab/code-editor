package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static jo.codeeditor.highlight.LineSpan.*;
import static jo.codeeditor.highlight.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Caractérisation du tokéniseur générique « à syntaxe C » (ADR-002,
 * chantier C2 — cliquet de démantèlement) : verrouille le comportement
 * EXACT (bornes et types de chaque span, état de sortie inter-lignes,
 * portes par langage) de {@code CLikeTokenizer.styleCLike} sur une
 * batterie de lignes représentatives, AVANT puis APRÈS la migration
 * sur le moteur générique {@link GenericTokenizer}. La migration doit
 * garder cette suite verte sans toucher à ses assertions — c'est le
 * critère « octet pour octet » du chantier C2.
 */
class CLikeTokenizerTest {

    private static List<LineSpan> spans(String line, String language) {
        return CLikeTokenizer.styleCLike(line, LexState.NORMAL, language).spans;
    }

    // ── Mots-clés et raffinements ──────────────────────────────────

    @Test
    void mot_cléSimple() {
        assertEquals(List.of(
                new LineSpan(0, 3, KEYWORD),
                new LineSpan(4, 5, PLAIN),
                new LineSpan(6, 7, OPERATOR),
                new LineSpan(8, 10, NUMBER),
                new LineSpan(10, 11, PUNCT)),
                spans("int x = 42;", "java"));
    }

    @Test
    void mot_modificateurEtFonction() {
        // public → KEYWORD_MODIFIER (raffinement), foo( → FUNC.
        assertEquals(List.of(
                new LineSpan(0, 6, KEYWORD_MODIFIER),
                new LineSpan(7, 10, KEYWORD),
                new LineSpan(11, 14, FUNC),
                new LineSpan(14, 15, PUNCT),
                new LineSpan(15, 18, KEYWORD),
                new LineSpan(19, 20, PLAIN),
                new LineSpan(20, 21, PUNCT)),
                spans("public int foo(int a)", "java"));
    }

    @Test
    void mot_contrôleEtConstanteAllCaps() {
        assertEquals(List.of(
                new LineSpan(0, 2, KEYWORD_CONTROL),
                new LineSpan(3, 4, PUNCT),
                new LineSpan(4, 9, PLAIN),
                new LineSpan(10, 11, OPERATOR),
                new LineSpan(12, 21, CONSTANT),
                new LineSpan(21, 22, PUNCT),
                new LineSpan(23, 29, KEYWORD_CONTROL),
                new LineSpan(29, 30, PUNCT)),
                spans("if (count > MAX_LIMIT) return;", "java"));
    }

    @Test
    void mot_typeJavaEtPropriétéAprèsPoint() {
        // String → JAVA_TYPES → TYPE ; champ après point → PROPERTY.
        assertEquals(List.of(
                new LineSpan(0, 6, TYPE),
                new LineSpan(7, 11, PLAIN)),
                spans("String name", "java"));
        assertEquals(List.of(
                new LineSpan(0, 3, PLAIN),
                new LineSpan(3, 4, PUNCT),
                new LineSpan(4, 9, PROPERTY)),
                spans("obj.field", "java"));
    }

    @Test
    void mot_majusculeAprèsPoint_typeAvantPropriété() {
        // L'initiale majuscule gagne sur la règle « après point ».
        assertEquals(List.of(
                new LineSpan(0, 3, PLAIN),
                new LineSpan(3, 4, PUNCT),
                new LineSpan(4, 9, TYPE)),
                spans("obj.Field", "java"));
    }

    @Test
    void mot_langageInconnu_repliMotsClésJava() {
        // Identifiant non enregistré → mots-clés Java (garde
        // historique de getKeywords).
        assertEquals(List.of(
                new LineSpan(0, 12, KEYWORD_MODIFIER),
                new LineSpan(13, 14, PLAIN)),
                spans("synchronized x", "xyznotlang"));
    }

    @Test
    void mot_kotlin() {
        assertEquals(List.of(
                new LineSpan(0, 3, KEYWORD),
                new LineSpan(4, 8, FUNC),
                new LineSpan(8, 9, PUNCT),
                new LineSpan(9, 10, PUNCT),
                new LineSpan(11, 12, PUNCT)),
                spans("fun main() {", "kotlin"));
    }

    // ── Commentaires ───────────────────────────────────────────────

    @Test
    void commentaire_deLigne() {
        assertEquals(List.of(
                new LineSpan(0, 3, KEYWORD),
                new LineSpan(4, 5, PLAIN),
                new LineSpan(5, 6, PUNCT),
                new LineSpan(7, 14, COMMENT)),
                spans("int x; // note", "java"));
    }

    @Test
    void commentaire_deBlocFermé() {
        assertEquals(List.of(
                new LineSpan(0, 9, COMMENT),
                new LineSpan(10, 13, KEYWORD),
                new LineSpan(14, 15, PLAIN),
                new LineSpan(15, 16, PUNCT)),
                spans("/* hmm */ int x;", "java"));
    }

    @Test
    void commentaire_deBlocAutoRéférentielPasFermé() {
        // Garde historique : la fermante est cherchée après pos+2 —
        // « /*/ » ne se referme donc PAS lui-même (orphan → fin de
        // ligne, état BLOCK_COMMENT).
        StyledLine l = CLikeTokenizer.styleCLike("/*/ x", LexState.NORMAL, "java");
        assertEquals(List.of(
                new LineSpan(0, 5, COMMENT)), l.spans);
        assertEquals(LexState.BLOCK_COMMENT, l.exitState);
    }

    @Test
    void commentaire_deBlocMultiLignes() {
        StyledLine l1 = CLikeTokenizer.styleCLike("/* start", LexState.NORMAL, "java");
        assertEquals(List.of(new LineSpan(0, 8, COMMENT)), l1.spans);
        assertEquals(LexState.BLOCK_COMMENT, l1.exitState);

        StyledLine l2 = CLikeTokenizer.styleCLike("middle */ int", LexState.BLOCK_COMMENT, "java");
        assertEquals(List.of(
                new LineSpan(0, 9, COMMENT),
                new LineSpan(10, 13, KEYWORD)), l2.spans);
        assertEquals(LexState.NORMAL, l2.exitState);

        // Ligne sans fermante : l'état se propage.
        StyledLine l3 = CLikeTokenizer.styleCLike("still", LexState.BLOCK_COMMENT, "java");
        assertEquals(List.of(new LineSpan(0, 5, COMMENT)), l3.spans);
        assertEquals(LexState.BLOCK_COMMENT, l3.exitState);
    }

    @Test
    void commentaire_deDocumentation() {
        // /** ouvre DOC_COMMENT — fermé sur la ligne ou propagé.
        assertEquals(List.of(
                new LineSpan(0, 11, DOC_COMMENT),
                new LineSpan(12, 15, KEYWORD),
                new LineSpan(16, 17, PLAIN),
                new LineSpan(17, 18, PUNCT)),
                spans("/** Doc. */ int x;", "java"));

        // /**/ : la fermante « chevauche » l'ouverture /** — fermé,
        // en DOC_COMMENT (garde historique de la recherche à pos+2).
        assertEquals(List.of(
                new LineSpan(0, 4, DOC_COMMENT)),
                spans("/**/", "java"));

        StyledLine l1 = CLikeTokenizer.styleCLike("/** First", LexState.NORMAL, "java");
        assertEquals(List.of(new LineSpan(0, 9, DOC_COMMENT)), l1.spans);
        assertEquals(LexState.DOC_COMMENT, l1.exitState);

        StyledLine l2 = CLikeTokenizer.styleCLike(" * middle", l1.exitState, "java");
        assertEquals(List.of(new LineSpan(0, 9, DOC_COMMENT)), l2.spans);
        assertEquals(LexState.DOC_COMMENT, l2.exitState);

        StyledLine l3 = CLikeTokenizer.styleCLike(" */ end", l2.exitState, "java");
        assertEquals(List.of(
                new LineSpan(0, 3, DOC_COMMENT),
                new LineSpan(4, 7, PLAIN)), l3.spans);
        assertEquals(LexState.NORMAL, l3.exitState);
    }

    // ── Chaînes et caractères ──────────────────────────────────────

    @Test
    void chaîne_guillemetsSimplesJetonUnique() {
        // Contrairement à JSON, les échappements ne sont PAS découpés
        // en ESCAPE : la chaîne est un seul span.
        assertEquals(List.of(
                new LineSpan(0, 13, STRING)),
                spans("\"hello world\"", "java"));
        assertEquals(List.of(
                new LineSpan(0, 6, STRING)),
                spans("\"a\\nb\"", "java"));
    }

    @Test
    void chaîne_nonFermée_jusquEnFinDeLigneSansÉtat() {
        // Garde historique : PAS d'état inter-lignes pour les chaînes
        // C non fermées (contrairement à JSON).
        StyledLine l = CLikeTokenizer.styleCLike("\"abc", LexState.NORMAL, "java");
        assertEquals(List.of(new LineSpan(0, 4, STRING)), l.spans);
        assertEquals(LexState.NORMAL, l.exitState);
    }

    @Test
    void caractère_littéral() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 7, CHAR),
                new LineSpan(7, 8, PUNCT)),
                spans("c = 'a';", "java"));
        assertEquals(List.of(
                new LineSpan(0, 4, CHAR)),
                spans("'\\n'", "java"));
        StyledLine orphan = CLikeTokenizer.styleCLike("'x", LexState.NORMAL, "java");
        assertEquals(List.of(new LineSpan(0, 2, CHAR)), orphan.spans);
        assertEquals(LexState.NORMAL, orphan.exitState);
    }

    // ── Chaîne brute Kotlin (porte par langage exact) ──────────────

    @Test
    void kotlin_chaîneBruteFermée() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 13, RAW_STRING)),
                spans("s = \"\"\"raw\"\"\"", "kotlin"));
    }

    @Test
    void kotlin_chaîneBruteOuverte_étatInterLignes() {
        StyledLine l = CLikeTokenizer.styleCLike("s = \"\"\"raw", LexState.NORMAL, "kotlin");
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 10, RAW_STRING)), l.spans);
        assertEquals(LexState.KT_RAW_STRING, l.exitState);

        StyledLine l2 = CLikeTokenizer.styleCLike("raw\"\"\" ok", l.exitState, "kotlin");
        assertEquals(List.of(
                new LineSpan(0, 6, RAW_STRING),
                new LineSpan(7, 9, PLAIN)), l2.spans);
        assertEquals(LexState.NORMAL, l2.exitState);
    }

    @Test
    void tripleGuillemets_horsKotlin_deuxChaînes() {
        // « kotlin » exact : ni java, ni l'alias kt — les triples
        // guillemets retombent dans la règle de chaîne « normale ».
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 6, STRING),
                new LineSpan(6, 10, STRING)),
                spans("s = \"\"\"raw", "java"));
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 6, STRING),
                new LineSpan(6, 10, STRING)),
                spans("s = \"\"\"raw", "kt"));
    }

    // ── Template literals JS/TS (porte par langage) ────────────────

    @Test
    void js_templateLiteralInterpolations() {
        assertEquals(List.of(
                new LineSpan(0, 7, STRING),
                new LineSpan(7, 14, VARIABLE),
                new LineSpan(14, 24, STRING),
                new LineSpan(24, 30, VARIABLE),
                new LineSpan(30, 31, STRING)),
                spans("`Hello ${name}, you are ${age}`", "javascript"));
    }

    @Test
    void js_templateLiteralÉchappement() {
        assertEquals(List.of(
                new LineSpan(0, 2, STRING),
                new LineSpan(2, 4, ESCAPE),
                new LineSpan(4, 6, STRING)),
                spans("`a\\nb`", "javascript"));
    }

    @Test
    void js_templateLiteralAccoladesImbriquées() {
        assertEquals(List.of(
                new LineSpan(0, 1, STRING),
                new LineSpan(1, 13, VARIABLE),
                new LineSpan(13, 14, STRING)),
                spans("`${ {a:1}.a }`", "javascript"));
    }

    @Test
    void js_templateLiteralOrphelin() {
        StyledLine l = CLikeTokenizer.styleCLike("`unterminated", LexState.NORMAL, "javascript");
        assertEquals(List.of(new LineSpan(0, 13, STRING)), l.spans);
        assertEquals(LexState.NORMAL, l.exitState, "pas d'état inter-lignes (limite historique documentée)");
    }

    @Test
    void backtick_horsJsTs_ponctuation() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 5, PUNCT),
                new LineSpan(5, 8, PLAIN),
                new LineSpan(8, 9, PUNCT)),
                spans("x = `cmd`", "java"));
    }

    // ── Annotations ────────────────────────────────────────────────

    @Test
    void annotation_simple() {
        assertEquals(List.of(
                new LineSpan(0, 9, ANNOTATION)),
                spans("@Override", "java"));
        // @ seul : annotation d'un caractère.
        assertEquals(List.of(
                new LineSpan(0, 1, ANNOTATION)),
                spans("@", "java"));
    }

    @Test
    void annotation_avecValeur() {
        assertEquals(List.of(
                new LineSpan(0, 17, ANNOTATION),
                new LineSpan(17, 18, PUNCT),
                new LineSpan(18, 21, STRING),
                new LineSpan(21, 22, PUNCT)),
                spans("@SuppressWarnings(\"x\")", "java"));
    }

    // ── Nombres et couleurs ────────────────────────────────────────

    @Test
    void nombres_hexaSuffixesEtSoulignés() {
        assertEquals(List.of(
                new LineSpan(0, 3, KEYWORD),
                new LineSpan(4, 8, PLAIN),
                new LineSpan(9, 10, OPERATOR),
                new LineSpan(11, 15, NUMBER),
                new LineSpan(15, 16, PUNCT)),
                spans("int mask = 0xFF;", "java"));
        assertEquals(List.of(
                new LineSpan(0, 3, NUMBER)),
                spans("10L", "java"));
        assertEquals(List.of(
                new LineSpan(0, 5, NUMBER)),
                spans("1_000", "java"));
    }

    @Test
    void couleur_hexadécimale() {
        assertEquals(List.of(
                new LineSpan(0, 5, PLAIN),
                new LineSpan(6, 7, OPERATOR),
                new LineSpan(8, 15, NUMBER)),
                spans("color = #FF0000", "java"));
        // Longueur invalide : le # retombe en PUNCT et la suite en mot.
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 3, CONSTANT)),
                spans("#GG", "java"));
    }

    // ── Opérateurs et ponctuation ──────────────────────────────────

    @Test
    void opérateurs_courses() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 4, OPERATOR),
                new LineSpan(5, 6, PLAIN)),
                spans("a == b", "java"));
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 4, OPERATOR),
                new LineSpan(5, 6, PLAIN)),
                spans("a -> b", "java"));
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 5, PLAIN)),
                spans("a + b", "java"));
    }

    @Test
    void ponctuation_pointVirguleEtCrochets() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 2, PUNCT),
                new LineSpan(3, 4, PLAIN)),
                spans("a; b", "java"));
    }

    // ── États d'entrée défensifs ───────────────────────────────────

    @Test
    void entréeXMLString_chaîneDéfensive() {
        // Garde historique : styleCLike sait CONSOMMER l'état
        // XML_STRING (partagé avec Json) mais ne le produit jamais.
        StyledLine l = CLikeTokenizer.styleCLike("tail\"", LexState.XML_STRING, "java");
        assertEquals(List.of(new LineSpan(0, 5, STRING)), l.spans);
        assertEquals(LexState.NORMAL, l.exitState);

        StyledLine l2 = CLikeTokenizer.styleCLike("still open", LexState.XML_STRING, "java");
        assertEquals(List.of(new LineSpan(0, 10, STRING)), l2.spans);
        assertEquals(LexState.XML_STRING, l2.exitState);
    }
}

package jo.codeeditor.textmate;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 10 (étape P1-8) — corpus de l'audit : attendus 4.0.
 *
 * <p>Histoire : ces tests ont d'abord caractérisé le comportement
 * 3.73.0 (P0, golden tests — défauts C8/C9 compris) pour verrouiller
 * le comportement avant la refonte ; la bascule P1 les met à jour vers
 * les attendus 4.0 du plan 10 §5 — CHAQUE écart 3.73 → 4.0 ci-dessous
 * est une correction VOULUE et justifiée (défaut C8 ou C9), jamais une
 * régression accidentelle. Toute évolution future non justifiée par le
 * plan est une régression.</p>
 *
 * <p>Deux supports : la ressource de travail
 * {@code textmate/unicode-audit.tmg.json} (règles ordonnées
 * {@code \bété\b} AVANT {@code \w+} : en 4.0, {@code \w} normalisé
 * capterait « été » avant le témoin {@code \b}) et les grammaires
 * tierces EMBARQUÉES réelles (markdown, python — les 8 règles du
 * constat C8).</p>
 */
class AuditCorpusTest {

    /** Grammaire de travail de l'audit (plan 10 §8, étape 2). */
    private static final TextMateGrammar AUDIT = load("textmate/unicode-audit.tmg.json");

    private final GrammarTokenizer engine = new GrammarTokenizer();

    private static TextMateGrammar load(String path) {
        try (InputStream in = AuditCorpusTest.class.getClassLoader()
                .getResourceAsStream(path);
                Scanner sc = new Scanner(in, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            return TextMateGrammar.parse(sc.next(), true);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ressource de test manquante : " + path, e);
        }
    }

    /** Représentation « [0,3)VARIABLE [3,5)TYPE » d'une ligne stylée. */
    private static String dump(StyledLine line) {
        StringBuilder sb = new StringBuilder();
        for (LineSpan s : line.spans) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('[').append(s.startCol).append(',').append(s.endCol)
                    .append(')').append(s.type);
        }
        return sb.toString();
    }

    // ── C9 résolu : Unicode natif (normalisation \w, \b natif) ────

    @Test
    void c9_w_unicode_surAnnée() {
        // 3.73 : « année » → [0,3)VARIABLE [3,4)COMMENT [4,5)VARIABLE (\w
        // ASCII). 4.0 (plan 10 §5, C9) : \w normalisé \p{Word} — le mot
        // entier en VARIABLE.
        StyledLine l = engine.tokenize(AUDIT, "année", 0);
        assertEquals("[0,5)VARIABLE", dump(l), "attendu 4.0 — \\w Unicode (C9 résolu)");
    }

    @Test
    void c9_w_unicode_surÉnorme() {
        // 3.73 : « é » initial en COMMENT (complément \W ASCII).
        // 4.0 : le mot entier en VARIABLE.
        StyledLine l = engine.tokenize(AUDIT, "énorme", 0);
        assertEquals("[0,6)VARIABLE", dump(l), "attendu 4.0 — \\W normalisé \\p{^Word}");
    }

    @Test
    void c9_b_unicode_surLÉté2026() {
        // 3.73 : \b ASCII — \bété\b ne matchait JAMAIS (« été » entier en
        // COMMENT, seul « t » passait en VARIABLE). 4.0 : \b natif Unicode
        // — « été » en KEYWORD [2,5) : le témoin direct de \b conforme à
        // la référence du dialecte.
        StyledLine l = engine.tokenize(AUDIT, "l'été 2026", 0);
        assertEquals("[0,1)VARIABLE [1,2)COMMENT [2,5)KEYWORD [5,6)COMMENT "
                + "[6,10)VARIABLE", dump(l), "attendu 4.0 — \\b Unicode (C9 résolu)");
    }

    @Test
    void c9_h_hexadécimal_sur1a2b() {
        // 3.73 : \h lu comme espace horizontal (l'espace en NUMBER).
        // 4.0 : \h natif = chiffre hexadécimal — l'espace n'est pas hexa,
        // il tombe dans \W (\p{^Word}) en COMMENT ; les heximaux restent
        // des identifiants (\w, déclaré après \bété\b).
        StyledLine l = engine.tokenize(AUDIT, "1a 2b", 0);
        assertEquals("[0,2)VARIABLE [2,3)COMMENT [3,5)VARIABLE", dump(l),
                "attendu 4.0 — \\h hexadécimal (écart de dialecte résolu)");
    }

    @Test
    void c9_posixAlpha_unicode_surCyrillique() {
        // 3.73 : [[:alpha:]] traduit \p{Alpha} (ASCII) — « переменная »
        // entier en COMMENT. 4.0 : \w normalisé capture le mot cyrillique
        // entier en VARIABLE (les classes POSIX et \w convergent vers la
        // même sémantique Unicode, conforme à la référence).
        StyledLine l = engine.tokenize(AUDIT, "переменная 1", 0);
        assertEquals("[0,10)VARIABLE [10,11)COMMENT [11,12)VARIABLE", dump(l),
                "attendu 4.0 — mot cyrillique entier");
    }

    // ── C8 résolu : les 7 règles markdown natives ────────────────

    @Test
    void c8_markdown_règlesNatives() {
        // 3.73 : les 7 règles \g<square> (gras, italique, 2 images,
        // 3 liens) étaient muettes — « **gras** » entier en PLAIN,
        // avertissements perdus. 4.0 (plan 10 §5, C8) : récursion \g<nom>
        // NATIVE — zéro avertissement au chargement, gras et italique en
        // EMPHASIS, lien complet typé (délimiteurs PUNCT, texte et URL
        // FUNC), lien imbriqué dans du gras.
        TextMateGrammar md = load("textmate/markdown.tmLanguage.json");
        assertTrue(md.warnings.isEmpty(),
                "4.0 : aucune règle markdown n'est muette (7 règles natives)");
        StyledLine gras = engine.tokenize(md, "**gras**", 0);
        assertEquals("[0,8)EMPHASIS", dump(gras), "attendu 4.0 — gras natif (C8 résolu)");
        StyledLine ital = engine.tokenize(md, "*italique* et du texte", 0);
        assertEquals("[0,10)EMPHASIS [10,22)PLAIN", dump(ital),
                "attendu 4.0 — italique natif");
        StyledLine lien = engine.tokenize(md, "voir [lien](http://x) là", 0);
        assertEquals("[0,5)PLAIN [5,6)PUNCT [6,10)FUNC [10,12)PUNCT [12,20)FUNC "
                + "[20,21)PUNCT [21,24)PLAIN", dump(lien),
                "attendu 4.0 — lien à crochets natif (délimiteurs + texte + URL)");
        StyledLine imbriqué = engine.tokenize(md, "**[lien](http://x)**", 0);
        assertEquals("[0,2)EMPHASIS [2,3)PUNCT [3,7)FUNC [7,9)PUNCT [9,17)FUNC "
                + "[17,18)PUNCT [18,20)EMPHASIS", dump(imbriqué),
                "attendu 4.0 — lien imbriqué dans du gras (le cas d'école du plan 10)");
    }

    @Test
    void c8_python_docstring_nonRégression() {
        // 3.73 ET 4.0 : la docstring une ligne fonctionne (la fermante
        // est résolue au runtime avec substitution de la backref — la
        // forme substituée, lookbehind de longueur fixe, compile dans
        // les deux moteurs). Ce test verrouille la NON-régression 4.0 ;
        // l'écart C8-python reste un écart de CAPACITÉ (motifs hôtes
        // sans backref substituable), documenté au plan 10.
        TextMateGrammar py = load("textmate/python.tmLanguage.json");
        assertTrue(py.warnings.isEmpty(), "4.0 : aucune règle python muette");
        StyledLine ds = engine.tokenize(py, "\"\"\"docstring\"\"\"", 0);
        assertEquals("[0,15)STRING", dump(ds),
                "docstring une ligne en STRING entier (non-régression 3.73 → 4.0)");
        StyledLine ind = engine.tokenize(py, "    \"\"\"résumé\"\"\"", 0);
        assertEquals("[0,4)PLAIN [4,16)STRING", dump(ind),
                "docstring indentée (non-régression)");
        assertEquals(0, ind.exitState, "le cadre docstring referme en ligne (exit 0)");
        // C9 visible côté python : identifiant accentué coloré (3.73 :
        // « def énorme(x): » laissait le nom en PLAIN).
        StyledLine defn = engine.tokenize(py, "def énorme(x):", 0);
        assertEquals("[0,3)KEYWORD [3,4)PLAIN [4,10)FUNC [10,11)PUNCT [11,12)VARIABLE "
                + "[12,14)PUNCT", dump(defn),
                "attendu 4.0 — « énorme » en FUNC (C9 résolu côté python)");
    }
}

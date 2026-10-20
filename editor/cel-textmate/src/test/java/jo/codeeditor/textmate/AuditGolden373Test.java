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
 * Plan 10 (étape P0-2) — golden tests de CARACTÉRISATION du comportement
 * 3.73.0 sur le corpus de l'audit (constats C2/C8/C9).
 *
 * <p>Chaque test verrouille le comportement ACTUEL, y compris ses DÉFAUTS
 * documentés (règles muettes C8, classes ASCII C9, espace horizontal
 * {@code \h}, avertissements perdus), pour distinguer en P1 les
 * corrections VOULUES des régressions accidentelles. Ces tests seront
 * MIS À JOUR en P1 vers les attendus 4.0 — chaque mise à jour sera
 * justifiée par une référence au plan 10 (§5, mapping C2/C8/C9) ;
 * jusqu'au GO de P3, tout écart INJUSTIFIÉ par le plan est une
 * régression.</p>
 *
 * <p>Deux supports : la ressource de travail
 * {@code textmate/unicode-audit.tmg.json} (une seule ressource caractérise
 * 3.73 puis 4.0 sur {@code \w}, {@code \b}, {@code [[:alpha:]]},
 * {@code \h}, {@code \W} et entrées accentuées) et les grammaires tierces
 * EMBARQUÉES réelles (markdown, python — les 8 règles du constat C8).</p>
 */
class AuditGolden373Test {

    /** Grammaire de travail de l'audit (plan 10 §8, étape 2). */
    private static final TextMateGrammar AUDIT = load("textmate/unicode-audit.tmg.json");

    private final GrammarTokenizer engine = new GrammarTokenizer();

    private static TextMateGrammar load(String path) {
        try (InputStream in = AuditGolden373Test.class.getClassLoader()
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

    /** Type du span couvrant la colonne col (premier span contenant). */
    private static TokenType typeOf(StyledLine line, int col) {
        for (LineSpan s : line.spans) {
            if (col >= s.startCol && col < s.endCol) return s.type;
        }
        throw new AssertionError("aucun span ne couvre la colonne " + col + " : " + dump(line));
    }

    // ── C9 : classes ASCII sur la ressource unicode-audit ────────

    @Test
    void c9_w_ascii_surAnnée() {
        // 3.73.0 : \w est ASCII — « année » n'est colorée que sur « ann » ;
        // le « é » tombe dans le complément \W (ASCII, COMMENT), le « e »
        // final dans \w (VARIABLE). Défaut C9 documenté ; attendu 4.0
        // (plan 10 §5) : [0,5)VARIABLE (normalisation \w -> \p{Word}).
        StyledLine l = engine.tokenize(AUDIT, "année", 0);
        assertEquals("[0,3)VARIABLE [3,4)COMMENT [4,5)VARIABLE", dump(l),
                "caractérisation 3.73.0 — \\w/\\W ASCII (défaut C9)");
    }

    @Test
    void c9_w_ascii_surÉnorme() {
        // 3.73.0 : « é » initial n'est pas un caractère de mot : il est
        // consommé par \W (complément ASCII) en COMMENT, seul « norme »
        // devient VARIABLE. Défaut C9 ; attendu 4.0 : [0,6)VARIABLE.
        StyledLine l = engine.tokenize(AUDIT, "énorme", 0);
        assertEquals("[0,1)COMMENT [1,6)VARIABLE", dump(l),
                "caractérisation 3.73.0 — « é » hors classe de mot (défaut C9)");
    }

    @Test
    void c9_b_ascii_surLÉté2026() {
        // 3.73.0 : \b est ASCII — « \bété\b » ne correspond JAMAIS (pas de
        // frontière entre « ' »/« é », tous deux hors mot) : « été » entier
        // tombe dans \W, seul le « t » passe dans \w. Défaut C9 ;
        // attendu 4.0 : « été » en KEYWORD ([2,5)) par \b natif Unicode.
        StyledLine l = engine.tokenize(AUDIT, "l'été 2026", 0);
        assertEquals("[0,1)VARIABLE [1,3)COMMENT [3,4)VARIABLE [4,6)COMMENT "
                + "[6,10)VARIABLE", dump(l),
                "caractérisation 3.73.0 — \\b ASCII, \\bété\\b muet (défaut C9)");
    }

    @Test
    void c9_h_espaceHorizontal_sur1a2b() {
        // 3.73.0 : \h est lu par java.util.regex comme ESPACE HORIZONTAL —
        // l'espace de « 1a 2b » devient NUMBER, les heximaux « 1a »/« 2b »
        // restent des IDENTIFIANTS (\w, déclaré avant). Écart de dialecte
        // documenté (plan 10 §1 : \h = chiffre hexadécimal dans le dialecte,
        // 0 occurrence dans le corpus embarqué) ; attendu 4.0 : \h natif =
        // hexadécimal → l'espace tombe dans \W (\p{^Word}) en COMMENT.
        StyledLine l = engine.tokenize(AUDIT, "1a 2b", 0);
        assertEquals("[0,2)VARIABLE [2,3)NUMBER [3,5)VARIABLE", dump(l),
                "caractérisation 3.73.0 — \\h comme espace horizontal (écart de dialecte)");
    }

    @Test
    void c9_posixAlpha_ascii_surCyrillique() {
        // 3.73.0 : [[:alpha:]] est traduit en \p{Alpha} (ASCII) et \W en
        // complément ASCII — sur « переменная 1 » (cyrillique), tout le mot
        // tombe dans \W (COMMENT) d'un seul tenant (l'espace compris), seul
        // le « 1 » passe dans \w. Défaut C9 ; attendu 4.0 :
        // [0,10)VARIABLE [10,11)VARIABLE (norme Unicode du mot) — la
        // cyrillique devient un mot plein.
        StyledLine l = engine.tokenize(AUDIT, "переменная 1", 0);
        assertEquals("[0,11)COMMENT [11,12)VARIABLE", dump(l),
                "caractérisation 3.73.0 — [[:alpha:]]/\\W ASCII sur cyrillique (défaut C9)");
    }

    // ── C8 : les 8 règles des grammaires tierces embarquées ──────

    @Test
    void c8_markdown_règlesMuettes_etAvertissementsPerdus() {
        // 3.73.0 : les 7 règles markdown à récursion \g<square> (gras,
        // italique, 2 images, 3 liens) sont rejetées à la traduction et
        // remplacées par des règles muettes en mode indulgent — MAIS les
        // avertissements sont PERDUS (grammar.warnings est copié AVANT le
        // parse des règles : la liste publiée reste vide, les rejets ne se
        // voient nulle part). Double défaut C8/C7 documenté ; attendu 4.0 :
        // les 7 règles NATIVES (correction C8) et les avertissements
        // publiés par grammaire via EditorLog (C7, plan 10 §8 étape 5).
        TextMateGrammar md = load("textmate/markdown.tmLanguage.json");
        assertTrue(md.warnings.isEmpty(),
                "caractérisation 3.73.0 — avertissements perdus (défaut C7) : "
                        + md.warnings);
        // Le gras et l'italique restent PLAIN (règle markup.bold/italic
        // muette) — défaut C8 ; attendu 4.0 : EMPHASIS.
        StyledLine gras = engine.tokenize(md, "**gras**", 0);
        assertEquals("[0,8)PLAIN", dump(gras),
                "caractérisation 3.73.0 — gras muet (défaut C8)");
        StyledLine ital = engine.tokenize(md, "*italique* et du texte", 0);
        assertEquals("[0,22)PLAIN", dump(ital),
                "caractérisation 3.73.0 — italique muet (défaut C8)");
        // Lien à crochets : seuls les délimiteurs des règles de repli
        // colorent (PUNCT/FUNC partiels), jamais la construction entière —
        // attendu 4.0 : la règle link.inline native type la construction.
        StyledLine lien = engine.tokenize(md, "voir [lien](http://x) là", 0);
        assertEquals("[0,5)PLAIN [5,6)PUNCT [6,10)FUNC [10,11)PUNCT [11,24)PLAIN",
                dump(lien), "caractérisation 3.73.0 — lien partiel (défaut C8)");
    }

    @Test
    void c8_python_docstring() {
        // 3.73.0 : la fin de docstring-statement ((?<=\1), backref dans
        // lookbehind) est intraduisible TELLE QU ELLE (constat C8), mais la
        // fermante est résolue au runtime avec substitution de la backref —
        // le texte substitué (lookbehind de longueur fixe) compile : la
        // docstring UNE LIGNE fonctionne et retombe en STRING entier.
        // L'écart C8 pratique ne se voit donc pas ici : il frappe les
        // motifs SANS backref substituable (grammaires hôtes). Attendu 4.0 :
        // identique (la variante native compile aussi en longueur fixe) —
        // ce test verrouille la NON-régression de la docstring python.
        TextMateGrammar py = load("textmate/python.tmLanguage.json");
        assertTrue(py.warnings.isEmpty(),
                "caractérisation 3.73.0 — avertissements perdus (défaut C7)");
        StyledLine ds = engine.tokenize(py, "\"\"\"docstring\"\"\"", 0);
        assertEquals("[0,15)STRING", dump(ds),
                "caractérisation 3.73.0 — docstring une ligne en STRING entier");
        // Docstring indentée (bloc def) : même comportement, fermeture en
        // ligne (exit 0) — la pile ne fuit pas sur la ligne suivante.
        StyledLine ind = engine.tokenize(py, "    \"\"\"résumé\"\"\"", 0);
        assertEquals("[0,4)PLAIN [4,16)STRING", dump(ind),
                "caractérisation 3.73.0 — docstring indentée");
        assertEquals(0, ind.exitState, "le cadre docstring referme en ligne (exit 0)");
    }
}

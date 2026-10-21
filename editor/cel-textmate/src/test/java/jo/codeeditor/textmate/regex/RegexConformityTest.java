package jo.codeeditor.textmate.regex;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 10 (§7) — suite de conformité PERMANENTE du moteur embarqué.
 *
 * <p>Les valeurs attendues ont été établies sur la RÉFÉRENCE du
 * dialecte (le moteur C, binaire de développement reconstruit hors
 * distribution — annexe A du plan 10) puis embarquées ici comme
 * constantes. La suite s'exécute en CI contre la couture et garantit
 * pour toute la vie de la bibliothèque l'équivalence
 * port ↔ référence. Toute divergence (montée de version du moteur,
 * nouveau constructeur) devient une entrée de {@link DialectNormalizer}
 * + un cas de la présente suite — règle d'or 3 du plan 10.</p>
 *
 * <p>Chaque cas est un essai ANCRÉ à une position char sur
 * « ligne + "\n" virtuel » (le modèle de {@link EncodedLine}) ; les
 * positions attendues sont en CARACTÈRES de la ligne, groupes
 * compris (g0 = match entier).</p>
 */
class RegexConformityTest {

    /** Un cas de conformité : essai ancré, attendu oracle. */
    private record Case(String id, String pattern, String line, int at,
                        int[] expected /* [s,e) par groupe : g0,g1,g2… ; null = NOMATCH */) {
    }

    private static final Map<String, Case> CASES = build();

    private static Map<String, Case> build() {
        Map<String, Case> m = new LinkedHashMap<>();
        // ── Correspondances et groupes ────────────────────────────
        m.put("groupes", new Case("groupes", "(\\w+)\\s+(\\d+)", "année 2026", 0,
                new int[] {0, 10, 0, 5, 6, 10}));
        // ── Ancres ────────────────────────────────────────────────
        m.put("ancre-chapeau", new Case("ancre-chapeau", "^\\s*(\\w+)", "  année", 0,
                new int[] {0, 7, 2, 7}));
        m.put("ancre-chapeau-ailleurs", new Case("ancre-chapeau-ailleurs", "^ab", "xxab", 2, null));
        m.put("bigA-zero", new Case("bigA-zero", "\\Aabc", "abcdef", 0, new int[] {0, 3}));
        m.put("bigA-ailleurs", new Case("bigA-ailleurs", "\\Aabc", "abcdef", 3, null));
        m.put("dollar-fin", new Case("dollar-fin", "abc$", "xababc", 3, new int[] {3, 6}));
        m.put("point-arrete-avant-nl", new Case("point-arrete-avant-nl", "[a-z]+", "ab", 0,
                new int[] {0, 2}));
        m.put("consomme-nl-virtuel", new Case("consomme-nl-virtuel", "b\\n?", "ab", 1,
                new int[] {1, 3}));
        // ── Unicode (C9) ──────────────────────────────────────────
        m.put("w-unicode", new Case("w-unicode", "\\w+", "année", 0, new int[] {0, 5}));
        m.put("W-complement", new Case("W-complement", "\\W+", "l'été", 1, new int[] {1, 2}));
        m.put("b-unicode", new Case("b-unicode", "\\bété\\b", "l'été", 2, new int[] {2, 5}));
        m.put("B-non-frontiere", new Case("B-non-frontiere", "\\Ban", "année", 2, null));
        m.put("d-arabe", new Case("d-arabe", "\\d+", "٣١٤", 0, new int[] {0, 3}));
        m.put("d-ascii", new Case("d-ascii", "\\d+", "42", 0, new int[] {0, 2}));
        m.put("s-ascii", new Case("s-ascii", "\\s+", "a  b", 1, new int[] {1, 3}));
        m.put("s-nbsp", new Case("s-nbsp", "\\s", "a b", 1, new int[] {1, 2}));
        // ── Classes POSIX ─────────────────────────────────────────
        m.put("posix-alpha", new Case("posix-alpha", "[[:alpha:]]+", "énorme", 0, new int[] {0, 6}));
        m.put("posix-negword", new Case("posix-negword", "[[:^word:]]+", "l'été 123", 1,
                new int[] {1, 2}));
        // ── Constructions du dialecte ─────────────────────────────
        m.put("possessif", new Case("possessif", "a*+b", "aaab", 0, new int[] {0, 4}));
        m.put("k-nomme", new Case("k-nomme", "(?<pair>ab)\\k<pair>", "abab", 0,
                new int[] {0, 4, 0, 2}));
        m.put("g-recursion", new Case("g-recursion",
                "(?<sq>\\[(?:[^\\[\\]]|\\g<sq>)*\\])", "[[a][b]]", 0, new int[] {0, 8, 0, 8}));
        m.put("lookbehind-fixe", new Case("lookbehind-fixe", "(?<=ab)c", "xabc", 3,
                new int[] {3, 4}));
        m.put("lookbehind-alternances", new Case("lookbehind-alternances",
                "(?<=(?:'''|\"\"\"|'|\"))x", "a'''x", 4, new int[] {4, 5}));
        m.put("commentaire-inline", new Case("commentaire-inline", "a(?# coucou)b", "ab", 0,
                new int[] {0, 2}));
        m.put("accolade-litterale", new Case("accolade-litterale", "\\{", "{", 0, new int[] {0, 1}));
        m.put("quantificateur-exact", new Case("quantificateur-exact", "a{3}", "aaa", 0,
                new int[] {0, 3}));
        m.put("etendu-classe-diese", new Case("etendu-classe-diese", "(?x)[-+#0 ]+", "+#0 ", 0,
                new int[] {0, 4}));
        m.put("etendu-commentaire", new Case("etendu-commentaire", "(?x)a # comment", "a", 0,
                new int[] {0, 1}));
        // ── Bornes char ↔ octet ───────────────────────────────────
        m.put("multi-octets-cyrillique", new Case("multi-octets-cyrillique", "\\w+",
                "переменная", 0, new int[] {0, 10}));
        m.put("multi-octets-emoji", new Case("multi-octets-emoji", "\\w+", "a\uD83D\uDE00b", 0,
                new int[] {0, 1}));
        return Collections.unmodifiableMap(m);
    }

    /** Exécute un cas et rend les positions CHAR par groupe, ou null. */
    private static int[] run(Case c) {
        CompiledRegex regex = CompiledRegex.compile(c.pattern());
        EncodedLine line = EncodedLine.of(c.line());
        LineMatcher matcher = regex.matcher(line);
        if (!matcher.matchAt(c.at())) return null;
        int n = matcher.groupCount();
        int[] out = new int[(n + 1) * 2];
        out[0] = matcher.start();
        out[1] = matcher.end();
        for (int g = 1; g <= n; g++) {
            out[g * 2] = matcher.start(g);
            out[g * 2 + 1] = matcher.end(g);
        }
        return out;
    }

    @Test
    void conformitéAuRéférenceDuDialecte() {
        StringBuilder echecs = new StringBuilder();
        for (Case c : CASES.values()) {
            int[] got = run(c);
            if (c.expected() == null) {
                if (got != null) {
                    echecs.append(c.id()).append(" : attendu NOMATCH, obtenu ")
                            .append(java.util.Arrays.toString(got)).append('\n');
                }
            } else if (got == null) {
                echecs.append(c.id()).append(" : attendu ")
                        .append(java.util.Arrays.toString(c.expected()))
                        .append(", obtenu NOMATCH\n");
            } else if (got.length != c.expected().length) {
                echecs.append(c.id()).append(" : ")
                        .append(java.util.Arrays.toString(got))
                        .append(" (arité de groupes inattendue)\n");
            } else if (!java.util.Arrays.equals(c.expected(), got)) {
                echecs.append(c.id()).append(" : attendu ")
                        .append(java.util.Arrays.toString(c.expected()))
                        .append(", obtenu ").append(java.util.Arrays.toString(got)).append('\n');
            }
        }
        assertTrue(echecs.length() == 0,
                "divergences port ↔ référence (plan 10, règle d'or 3) :\n" + echecs);
    }

    @Test
    void budgetParEssai_interruptionEtRepli() {
        // (a+)+$ sur entrée adversariale : l'essai DOIT s'interrompre
        // (budget 100 ms) et se replier en non-correspondance — jamais
        // bloquer (constat C2).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append('a');
        sb.append('!');
        CompiledRegex regex = CompiledRegex.compile("(a+)+$");
        EncodedLine line = EncodedLine.of(sb.toString());
        LineMatcher matcher = regex.matcher(line);
        long t0 = System.nanoTime();
        boolean matched = matcher.matchAt(0);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(matched, "le motif catastrophique ne correspond jamais");
        assertTrue(ms < 5_000, "l'interruption doit être rapide (observé " + ms + " ms)");
    }

    @Test
    void budgetParEssai_réarméÀChaqueAppel() {
        // Deux essaiS SAINS lourds consécutifs sur le MÊME matcher : le
        // budget se réarme à chaque appel — si une échéance globale
        // persistait entre les essais, le second échouerait. Essai lourd
        // sain = (a|b)+c sur 2000 alternances (passe les points de
        // contrôle du moteur sans dépasser le budget).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("ab");
        sb.append('c');
        CompiledRegex regex = CompiledRegex.compile("(a|b)+c");
        EncodedLine line = EncodedLine.of(sb.toString());
        LineMatcher matcher = regex.matcher(line);
        assertTrue(matcher.matchAt(0), "premier essai sain lourd");
        assertEquals(4001, matcher.end());
        assertTrue(matcher.matchAt(1), "second essai sain lourd — budget réarmé");
        assertEquals(4001, matcher.end());
    }

    @Test
    void bornesCharOctet_surLigneMultiOctets() {
        // Index de préfixes : char→octet en O(1), octet→char par
        // dichotomie ; un octet AU MILIEU d'un caractère rend le
        // caractère qui le contient. Le texte porte le « \n » virtuel
        // (11 octets pour 10 cyrilliques + 1).
        EncodedLine l = EncodedLine.of("переменная");
        assertEquals(21, l.bytes().length, "10 caractères cyrilliques + « \n » = 21 octets");
        assertEquals(0, l.byteOffset(0));
        assertEquals(2, l.byteOffset(1));
        assertEquals(20, l.byteOffset(10));
        assertEquals(0, l.charIndex(0));
        assertEquals(1, l.charIndex(2));
        assertEquals(1, l.charIndex(3), "un octet au milieu du 2e caractère rend ce caractère");
        assertEquals(4, l.charIndex(9));
        assertEquals(10, l.charIndex(20));
        // Emoji (paire de substitution) : 4 octets pour DEUX chars Java ;
        // le BAS porte le préfixe du HAUT (le point de code commence au haut).
        EncodedLine e = EncodedLine.of("a\uD83D\uDE00b");
        assertEquals("a\uD83D\uDE00b\n", e.text());
        assertEquals(1, e.byteOffset(1));
        assertEquals(1, e.byteOffset(2), "le bas de la paire porte le préfixe du haut");
        assertEquals(5, e.byteOffset(3), "« b » commence à l'octet 5");
        assertEquals(7, e.bytes().length);
        assertEquals(3, e.charIndex(5), "« b » est le caractère 3");
        assertEquals(1, e.charIndex(1), "une fin de correspondance au début de l’emoji s’exprime au HAUT");
    }

    @Test
    void cacheLRU_cléSourceNormalisée() {
        // Deux ouvertures du même cadre avec la même capture partagent
        // le motif compilé ; la clé est la source APRÈS normalisation.
        CompiledRegex a = RegexCache.getOrCompile("\\w+");
        CompiledRegex b = RegexCache.getOrCompile("\\w+");
        assertSame(a, b, "hit par clé normalisée");
        CompiledRegex c = RegexCache.getOrCompile("[a-z]");
        assertNotSame(a, c, "source différente : miss");
        assertEquals("\\p{Word}+", a.source(), "la clé est la source normalisée");
        LineMatcher m = a.matcher(EncodedLine.of("année"));
        assertTrue(m.matchAt(0));
        assertEquals(0, m.start());
        assertEquals(5, m.end(), "la normalisation s'applique avant la mise en cache");
    }
}

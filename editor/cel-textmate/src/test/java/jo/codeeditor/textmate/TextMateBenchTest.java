package jo.codeeditor.textmate;

import jo.codeeditor.highlight.StyledLine;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 10 (§8, étape 11) — banc de la refonte : corpus réel + cas
 * adversarial. Les nombres sont IMPRIMÉS dans le journal de test (le
 * record vit au plan 10 §12) ; les plafonds d'assertion sont
 * volontairement GÉNÉREUX (×100 le typique) : ce banc surveille les
 * dérersions, il ne course pas la montre.
 *
 * <p>Plafonds D4 (calés au banc, sans nouveau plan) : moyenne du
 * corpus ≤ 10 ms/ligne ; ligne adversarial bornée par la garde
 * anti-interruptions (≤ 3 essais interrompus par motif et par ligne,
 * puis le motif est sauté) — l'ordre de grandeur attendu est
 * « 3 × budget » soit ~0,3 s, jamais « positions × budget ».</p>
 */
class TextMateBenchTest {

    private final GrammarTokenizer engine = new GrammarTokenizer();

    private static TextMateGrammar load(String path) {
        try (InputStream in = TextMateBenchTest.class.getClassLoader()
                .getResourceAsStream(path);
                Scanner sc = new Scanner(in, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            return TextMateGrammar.parse(sc.next(), true);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ressource de test manquante : " + path, e);
        }
    }

    /** Lignes représentatives du corpus embarqué (une par grammaire). */
    private static final Map<String, String> CORPUS = buildCorpus();

    private static Map<String, String> buildCorpus() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("csharp", "var compteur = 3; // incrémentation");
        m.put("css", "body { color: red; margin: 0 auto; }");
        m.put("html", "<div class=\"titre\">éditeur &amp; ampersand</div>");
        m.put("java", "Map<String, List<Integer>> cache = new HashMap<>();");
        m.put("javascript", "const total = items.reduce((a, b) => a + b.prix, 0);");
        m.put("kotlin", "fun saluer(nom: String): String = \"Bonjour $nom\"");
        m.put("lua", "local function dessine(x, y) return x + y end");
        m.put("markdown", "**[lien imbriqué](https://example.org)** et du texte");
        m.put("python", "def calcule(montant: float) -> float: return montant * 1.2");
        m.put("xml", "<module état=\"actif\">cellule</module>");
        return m;
    }

    @Test
    void banc_corpusRéel() {
        // 20 itérations par grammaire (échauffement compris), moyenne
        // affichée puis plafonnée à 10 ms/ligne.
        double pire = 0;
        StringBuilder rapport = new StringBuilder("\n— banc corpus (µs/ligne, moyenne 20 essais) —\n");
        for (Map.Entry<String, String> e : CORPUS.entrySet()) {
            String res = "textmate/" + e.getKey()
                    + (e.getKey().equals("csharp") ? ".tmg.json"
                     : e.getKey().equals("kotlin") ? ".tmLanguage" : ".tmLanguage.json");
            TextMateGrammar grammar = load(res);
            String ligne = e.getValue();
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                engine.tokenize(grammar, ligne, 0);
            }
            double us = (System.nanoTime() - t0) / 1_000.0 / 20;
            rapport.append(String.format("  %-10s %8.1f µs/ligne%n", e.getKey(), us));
            pire = Math.max(pire, us / 1000.0);
        }
        System.out.println(rapport);
        assertTrue(pire < 10.0,
                "dérive du banc (pire grammaire " + pire + " ms/ligne, plafond D4 10 ms)");
    }

    @Test
    void banc_adversarial_bornéParLaGarde() {
        // Grammaire hôte au motif catastrophique ((a+)+$ en règle match,
        // essayée à CHAQUE position) : sans la garde anti-interruptions,
        // la ligne consommerait « positions × budget » (41 × 100 ms) ;
        // avec elle, ~3 × budget puis le motif est sauté — la ligne
        // complète reste bornée et le rendu dégrade proprement (PLAIN).
        String source = "{\"scopeName\": \"source.adversarial\", \"patterns\": "
                + "[{\"match\": \"(a+)+$\", \"name\": \"invalid.adversarial\"}]}";
        TextMateGrammar grammar = TextMateGrammar.parse(source);
        StringBuilder ligne = new StringBuilder();
        for (int i = 0; i < 40; i++) ligne.append('a');
        ligne.append('!');
        // Trois lignes successives (la garde est par ligne : elle se
        // réarme, chaque ligne reste bornée ~3 × budget).
        long t0 = System.nanoTime();
        StyledLine l1 = engine.tokenize(grammar, ligne.toString(), 0);
        StyledLine l2 = engine.tokenize(grammar, ligne.toString(), l1.exitState);
        StyledLine l3 = engine.tokenize(grammar, ligne.toString(), l2.exitState);
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        System.out.printf("%n— banc adversarial : 3 lignes de 41 caractères en %.0f ms "
                + "(attendu ~3 × budget par ligne, jamais 41 × budget) —%n", ms);
        assertTrue(ms < 5_000, "ligne adversarial non bornée : " + ms + " ms");
        assertTrue(l3.spans.get(l3.spans.size() - 1).endCol == 41,
                "la ligne entière est couverte (dégradation propre en PLAIN)");
    }
}

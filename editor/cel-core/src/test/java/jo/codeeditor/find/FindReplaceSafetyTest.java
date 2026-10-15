package jo.codeeditor.find;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-05 — recherche regex SÛRE : une expression à retour sur soi-même
 * catastrophique ne fige plus le thread appelant (budget temps +
 * plafond de résultats), l'état est exposé pour signal à l'UI.
 *
 * <p>Rouge avant : {@code findRegex} bouclait sans limite — le test
 * catastrophique échoue par TIMEOUT (le moteur backtracke pendant des
 * minutes sur 60 000 caractères adverses) ; le plafond n'existait pas
 * (200 000 correspondances collectées).</p>
 */
class FindReplaceSafetyTest {

    private static final FindOptions REGEX = new FindOptions().withRegex(true);
    private static final FindOptions REGEX_CI = new FindOptions()
            .withRegex(true).withCaseSensitive(false);

    /**
     * Motif à retour sur soi-même catastrophique sur texte adverse :
     * la recherche doit rendre la main en moins d'une seconde avec
     * l'état « trop coûteuse ».
     */
    @Test
    @Timeout(value = 5)
    void motifCatastrophique_rendLaMainSousLaSeconde_étatTropCoûteuse() {
        // 60 000 « a » sans le « b » final : backtracking exponentiel
        // de (a+)+b.
        String adverse = "a".repeat(60_000);
        long t0 = System.nanoTime();
        FindReplace.SearchResult résultat =
                FindReplace.find(adverse, "(a+)+b", REGEX);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(résultat.tooCostly, "l'état trop coûteuse est exposé");
        assertTrue(ms < 1_000, "retour borné < 1 s (mesuré " + ms + " ms)");
    }

    /** Variante insensible à la casse : même garde-fou. */
    @Test
    @Timeout(value = 5)
    void motifCatastrophique_insensibleCasse_gardeFouActif() {
        String adverse = "aAaA".repeat(15_000);
        FindReplace.SearchResult résultat =
                FindReplace.find(adverse, "(a+)+b", REGEX_CI);
        assertTrue(résultat.tooCostly, "le garde-fou couvre le mode insensible à la casse");
    }

    /** Le plafond borne la collecte et porte l'état « tronqué ». */
    @Test
    void plafondDeRésultats_étatTronqué() {
        // 200 000 correspondances potentielles → collecte arrêtée à 10 000.
        String texte = "a".repeat(200_000);
        FindReplace.SearchResult résultat = FindReplace.find(texte, "a", REGEX);
        assertEquals(FindReplace.MAX_MATCHES, résultat.matches.size(),
                "la collecte est bornée au plafond");
        assertTrue(résultat.truncated, "l'état tronqué est exposé");
        // Mode plain : même plafond.
        FindReplace.SearchResult résultatPlain =
                FindReplace.find(texte, "a", new FindOptions());
        assertEquals(FindReplace.MAX_MATCHES, résultatPlain.matches.size());
        assertTrue(résultatPlain.truncated, "le plafond s'applique aussi au mode plain");
    }

    /** Recherche saine : comportement inchangé, aucun signal. */
    @Test
    void rechercheNormale_inchangée() {
        String texte = "int foo = 1;\nint bar = foo + foo;\n";
        FindReplace.SearchResult résultat = FindReplace.find(texte, "foo", new FindOptions());
        assertEquals(3, résultat.matches.size());
        assertFalse(résultat.truncated);
        assertFalse(résultat.tooCostly);
        assertEquals(4, résultat.matches.get(0).start);
        assertEquals(7, résultat.matches.get(0).end);

        // Regex saine : positions exactes.
        FindReplace.SearchResult regex = FindReplace.find(texte, "int \\w+", REGEX);
        assertEquals(2, regex.matches.size());
        assertFalse(regex.tooCostly);
    }

    /** Motif invalide : aucune correspondance, aucun signal (historique). */
    @Test
    void motifInvalide_aucunMatch() {
        FindReplace.SearchResult résultat = FindReplace.find("abc", "(a", REGEX);
        assertTrue(résultat.matches.isEmpty());
        assertFalse(résultat.tooCostly);
    }

    /** Pattern centralisé : mêmes flags que l'ancienne construction. */
    @Test
    void patternCentralisé_flagsHistoriques() {
        FindOptions sensible = new FindOptions().withRegex(true).withCaseSensitive(true);
        assertEquals(java.util.regex.Pattern.MULTILINE,
                FindReplace.compilePattern("a", sensible).flags());
        assertEquals(java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE,
                FindReplace.compilePattern("a", new FindOptions()).flags(),
                "insensible à la casse par défaut (options historiques)");
    }
}

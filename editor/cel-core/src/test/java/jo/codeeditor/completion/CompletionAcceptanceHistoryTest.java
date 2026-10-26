package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L'historique d'acceptation de complétion — le moteur de
 * l'apprentissage du classement : enregistrement par (langage,
 * identifiant), score (fréquence puis fraîcheur), compartiments par
 * langage, bascule, purge, éviction capacitaire.
 */
class CompletionAcceptanceHistoryTest {

    @Test
    void inconnu_scoreNul() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        assertEquals(0L, h.boost("java", "Text"));
        assertEquals(0, h.size());
    }

    @Test
    void enregistrement_puisBoostPositif() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        assertTrue(h.boost("java", "Text") > 0L);
        assertEquals(1, h.size());
    }

    @Test
    void frequence_domineFraicheur() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        // textField accepté UNE fois, récemment ; text accepté TROIS
        // fois, plus tôt — la fréquence domine.
        h.record("java", "textField");
        h.record("java", "text");
        h.record("java", "text");
        h.record("java", "text");
        assertTrue(h.boost("java", "text") > h.boost("java", "textField"));
    }

    @Test
    void aFrequenceEgaleLePlusRecentPasseDevant() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "alpha");
        h.record("java", "beta");
        // Même fréquence (1) : beta plus récent → score supérieur.
        assertTrue(h.boost("java", "beta") > h.boost("java", "alpha"));
    }

    @Test
    void langages_etanches() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        assertEquals(0L, h.boost("xml", "Text"));
        h.clear("xml"); // ne touche pas au compartiment java
        assertTrue(h.boost("java", "Text") > 0L);
    }

    @Test
    void desactive_collecteEtBoostCoupes() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.setEnabled(false);
        // Boost éteint, même pour l'entrée déjà connue.
        assertEquals(0L, h.boost("java", "Text"));
        // La collecte est coupée : rien de nouveau.
        h.record("java", "TextField");
        h.setEnabled(true);
        assertEquals(0L, h.boost("java", "TextField"));
        // L'historique d'avant est conservé.
        assertTrue(h.boost("java", "Text") > 0L);
    }

    @Test
    void purgeTotale_etParLangage() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.record("xml", "div");
        h.clear("java");
        assertEquals(0L, h.boost("java", "Text"));
        assertTrue(h.boost("xml", "div") > 0L);
        h.clear();
        assertEquals(0, h.size());
        assertEquals(0L, h.boost("xml", "div"));
    }

    @Test
    void entreesInvalides_ignorees() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record(null, "Text");
        h.record("java", null);
        h.record("", "Text");
        h.record("java", "");
        assertEquals(0, h.size());
        assertEquals(0L, h.boost(null, "Text"));
        assertEquals(0L, h.boost("java", null));
    }

    @Test
    void capacitaire_evictionDuPlusFaible() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        // Le premier accepté une seule fois : candidat à l'éviction
        // quand le compartiment dépasse la capacité.
        h.record("java", "premier");
        for (int i = 0; i < CompletionAcceptanceHistory.PER_LANGUAGE_CAPACITY; i++) {
            h.record("java", "mot" + i);
        }
        // Le compartiment est plein (200), « premier » a été évincé au
        // 200ᵉ enregistrement (score le plus faible : fréquence 1,
        // fraîcheur la plus ancienne).
        assertEquals(0L, h.boost("java", "premier"));
        // Les récents tiennent toujours.
        assertTrue(h.boost("java", "mot" + (CompletionAcceptanceHistory.PER_LANGUAGE_CAPACITY - 1)) > 0L);
        // Un frequenté survit : il domine les uniques.
        CompletionAcceptanceHistory h2 = new CompletionAcceptanceHistory();
        h2.record("java", "chouchou");
        h2.record("java", "chouchou");
        h2.record("java", "chouchou");
        for (int i = 0; i < CompletionAcceptanceHistory.PER_LANGUAGE_CAPACITY; i++) {
            h2.record("java", "mot" + i);
        }
        assertTrue(h2.boost("java", "chouchou") > 0L);
    }
}

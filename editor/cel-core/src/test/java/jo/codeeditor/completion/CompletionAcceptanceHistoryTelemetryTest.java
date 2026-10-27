package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La télémétrie de l'apprentissage (4.6.0) — mesurer ce que le
 * mécanisme RAPPORTE avant de l'enrichir : acceptations comptabilisées
 * (recorded) et, parmi elles, acceptations de candidats déjà appris
 * (boosted — celles que le boost avait fait remonter). Compteurs
 * cumulatifs de session, remis à zéro par la purge totale, épargnés par
 * la purge par langage, absents du snapshot.
 */
class CompletionAcceptanceHistoryTelemetryTest {

    @Test
    void premiereAcceptation_compteeMaisNonBoostee() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        assertEquals(1, h.recordedAcceptanceCount());
        assertEquals(0, h.boostedAcceptanceCount(),
                "un candidat jamais appris n'a pas été remonté par le boost");
    }

    @Test
    void reacceptation_compteeEtBoostee() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.record("java", "Text");
        h.record("java", "Text");
        assertEquals(3, h.recordedAcceptanceCount());
        assertEquals(2, h.boostedAcceptanceCount(),
                "les 2e et 3e acceptations étaient des candidats déjà appris");
    }

    @Test
    void compartimentsEtangers_comptesEnsemble() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.record("xml", "Text");
        h.record("xml", "Text");
        assertEquals(3, h.recordedAcceptanceCount());
        assertEquals(1, h.boostedAcceptanceCount(), "seul le Text XML était déjà appris");
    }

    @Test
    void basculeFermee_rienNestCompte() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.setEnabled(false);
        h.record("java", "Text");
        h.record("kotlin", "List");
        assertEquals(1, h.recordedAcceptanceCount(), "le no-op intégral ne compte pas");
        assertEquals(0, h.boostedAcceptanceCount());
        h.setEnabled(true);
        assertEquals(1, h.recordedAcceptanceCount(), "la réactivation ne rejoue rien");
    }

    @Test
    void purgeTotale_remetLesCompteursAZero() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.record("java", "Text");
        h.clear();
        assertEquals(0, h.recordedAcceptanceCount());
        assertEquals(0, h.boostedAcceptanceCount());
    }

    @Test
    void purgeParLangage_epargneLesCompteurs() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.record("java", "Text");
        h.clear("java");
        assertEquals(2, h.recordedAcceptanceCount(),
                "les compteurs sont une mesure de session, pas un état par langage");
        assertEquals(1, h.boostedAcceptanceCount());
    }

    @Test
    void entreesInvalides_neComptentPas() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", null);
        h.record(null, "Text");
        h.record("", "");
        assertEquals(0, h.recordedAcceptanceCount());
        assertEquals(0, h.boostedAcceptanceCount());
    }

    @Test
    void snapshot_neTransportePasLesCompteurs() {
        CompletionAcceptanceHistory source = new CompletionAcceptanceHistory();
        source.record("java", "Text");
        source.record("java", "Text");

        CompletionAcceptanceHistory cible = new CompletionAcceptanceHistory();
        cible.restore(source.snapshot());

        // Les compteurs mesurent CETTE session ; l'historique restauré
        // est de la donnée apprise, pas des acceptations vécues ici.
        assertEquals(0, cible.recordedAcceptanceCount());
        assertEquals(0, cible.boostedAcceptanceCount());
    }
}

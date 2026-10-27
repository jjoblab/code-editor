package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La persistance de l'historique d'acceptation (4.6.0) —
 * {@code snapshot()} photographie l'état appris, {@code restore(...)}
 * le remplace intégralement : aller-retour complet, rebase du compteur
 * de fraîcheur (une nouvelle acceptation ne doit jamais paraître plus
 * VIEILLE qu'une entrée restaurée), garde-fous (null, version inconnue,
 * entrées malformées, capacité).
 */
class CompletionAcceptanceHistoryPersistenceTest {

    @Test
    void allerRetourComplet_boostIdentiques() {
        CompletionAcceptanceHistory source = new CompletionAcceptanceHistory();
        source.record("java", "Text");
        source.record("java", "Text");
        source.record("java", "Intent");
        source.record("xml", "LinearLayout");
        long boostText = source.boost("java", "Text");
        long boostIntent = source.boost("java", "Intent");
        long boostLayout = source.boost("xml", "LinearLayout");

        CompletionAcceptanceHistory cible = new CompletionAcceptanceHistory();
        cible.restore(source.snapshot());

        assertEquals(3, cible.size());
        assertEquals(boostText, cible.boost("java", "Text"));
        assertEquals(boostIntent, cible.boost("java", "Intent"));
        assertEquals(boostLayout, cible.boost("xml", "LinearLayout"));
    }

    @Test
    void restore_remplaceTotalementLetatCourant() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Ancien");
        CompletionAcceptanceHistory autre = new CompletionAcceptanceHistory();
        autre.record("kotlin", "Nouveau");

        h.restore(autre.snapshot());

        assertEquals(0L, h.boost("java", "Ancien"), "l'état d'avant doit partir");
        assertTrue(h.boost("kotlin", "Nouveau") > 0L);
        assertEquals(1, h.size());
    }

    @Test
    void rebase_fraicheurRestaureeNeDominePasLesNouvelles() {
        // LE piège : une instance chargée d'un snapshot riche doit
        // rebaser son compteur de fraîcheur — sinon la PREMIÈRE
        // acceptation fraîche (seq 1) paraîtrait plus vieille que les
        // entrées restaurées (seq 5 000) et serait classée derrière.
        Map<String, Map<String, long[]>> data = new LinkedHashMap<>();
        Map<String, long[]> java = new LinkedHashMap<>();
        java.put("Vieux", new long[]{1, 5_000L});
        data.put("java", java);
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.restore(new CompletionAcceptanceHistory.Snapshot(data,
                CompletionAcceptanceHistory.Snapshot.FORMAT_VERSION));

        h.record("java", "Neuf");

        // Même fréquence (1) : le candidat fraîchement accepté doit
        // passer DEVANT le restauré — sans rebase, il resterait derrière.
        assertTrue(h.boost("java", "Neuf") > h.boost("java", "Vieux"));
    }

    @Test
    void apresRestoreLaFraicheurContinueDAvancer() {
        CompletionAcceptanceHistory source = new CompletionAcceptanceHistory();
        source.record("java", "A");
        source.record("java", "B"); // B plus récent que A
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.restore(source.snapshot());
        h.record("java", "A"); // A ré-accepté APRÈS le restore → devant B

        assertTrue(h.boost("java", "A") > h.boost("java", "B"));
    }

    @Test
    void restore_null_estUnNoOp() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        h.restore(null);
        assertTrue(h.boost("java", "Text") > 0L);
        assertEquals(1, h.size());
    }

    @Test
    void restore_versionInconnue_estUnNoOp() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        Map<String, Map<String, long[]>> data = new LinkedHashMap<>();
        Map<String, long[]> java = new LinkedHashMap<>();
        java.put("Text", new long[]{2, 7L});
        data.put("java", java);

        h.restore(new CompletionAcceptanceHistory.Snapshot(data, 99));
        assertEquals(0, h.size(), "un format du futur n'est pas interprétable");

        h.restore(new CompletionAcceptanceHistory.Snapshot(data, 0));
        assertEquals(0, h.size(), "une version nulle est tout aussi refusée");
    }

    @Test
    void restore_entreesMalformees_sontIgnorees() {
        Map<String, Map<String, long[]>> data = new LinkedHashMap<>();
        Map<String, long[]> java = new LinkedHashMap<>();
        java.put("Bon", new long[]{3, 10L});      // valide
        java.put("Nul", new long[]{0, 11L});      // fréquence nulle → jeté
        java.put("Negatif", new long[]{-2, 12L}); // fréquence négative → jeté
        java.put("SansSeq", new long[]{2, 0L});   // séquence nulle → jeté
        java.put("TropCourt", new long[]{2});     // arité ≠ 2 → jeté
        java.put("TropLong", new long[]{2, 5L, 9}); // arité ≠ 2 → jeté
        data.put("java", java);
        Map<String, long[]> vide = new LinkedHashMap<>();
        vide.put("X", (long[]) null);             // valeur nulle → jeté
        data.put("xml", vide);

        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.restore(new CompletionAcceptanceHistory.Snapshot(data,
                CompletionAcceptanceHistory.Snapshot.FORMAT_VERSION));

        assertEquals(1, h.size());
        assertTrue(h.boost("java", "Bon") > 0L);
        assertEquals(0L, h.boost("java", "Nul"));
        assertEquals(0L, h.boost("xml", "X"));
    }

    @Test
    void restore_auDelaDeLaCapacite_gardeLesPlusForts() {
        Map<String, long[]> java = new LinkedHashMap<>();
        for (int i = 0; i < CompletionAcceptanceHistory.PER_LANGUAGE_CAPACITY + 20; i++) {
            // scores croissants : les 20 derniers sont les plus forts
            java.put("id" + i, new long[]{1 + i / 100, i + 1});
        }
        Map<String, Map<String, long[]>> data = new LinkedHashMap<>();
        data.put("java", java);

        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.restore(new CompletionAcceptanceHistory.Snapshot(data,
                CompletionAcceptanceHistory.Snapshot.FORMAT_VERSION));

        assertEquals(CompletionAcceptanceHistory.PER_LANGUAGE_CAPACITY, h.size());
        assertEquals(0L, h.boost("java", "id0"), "le plus faible part");
        assertTrue(h.boost("java",
                "id" + (CompletionAcceptanceHistory.PER_LANGUAGE_CAPACITY + 19)) > 0L,
                "le plus fort reste");
        // L'invariant capacitaire tient dès le restore, pas seulement au
        // prochain record().
    }

    @Test
    void snapshot_estImmuable_etNeCouplePasLesInstances() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.record("java", "Text");
        CompletionAcceptanceHistory.Snapshot s = h.snapshot();

        // Muter la copie rendue ne corrompt ni la source ni les futurs restores
        Map<String, Map<String, long[]>> entries = s.entries();
        entries.put("pirate", new LinkedHashMap<>());
        entries.get("java").put("Intrus", new long[]{9, 9L});

        assertEquals(1, h.size());
        assertEquals(0L, h.boost("java", "Intrus"));

        CompletionAcceptanceHistory cible = new CompletionAcceptanceHistory();
        cible.restore(h.snapshot()); // re-photographier la source, pas la copie
        assertEquals(1, cible.size());
        assertEquals(0L, cible.boost("java", "Intrus"));
    }

    @Test
    void snapshot_vide_puisRestore() {
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        CompletionAcceptanceHistory.Snapshot s = h.snapshot();
        assertEquals(CompletionAcceptanceHistory.Snapshot.FORMAT_VERSION, s.version());
        assertTrue(s.entries().isEmpty());

        CompletionAcceptanceHistory autre = new CompletionAcceptanceHistory();
        autre.record("java", "Text");
        autre.restore(s);
        assertEquals(0, autre.size());
        assertEquals(0L, autre.boost("java", "Text"));
    }

    @Test
    void snapshot_construitDepuisUneMapDeserialisee() {
        // Le chemin JSON/ DataStore de l'hôte : des données plates
        // rechargées redeviennent un historique actif.
        Map<String, Map<String, long[]>> data = new HashMap<>();
        Map<String, long[]> java = new HashMap<>();
        java.put("Text", new long[]{4, 12L});
        data.put("java", java);

        CompletionAcceptanceHistory.Snapshot s =
                new CompletionAcceptanceHistory.Snapshot(data,
                        CompletionAcceptanceHistory.Snapshot.FORMAT_VERSION);
        // Le constructeur copie défensivement : muter data après coup
        // ne change pas le snapshot.
        data.get("java").put("Intrus", new long[]{1, 1L});

        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.restore(s);
        assertEquals(1, h.size());
        assertTrue(h.boost("java", "Text") > 0L);
        assertEquals(0L, h.boost("java", "Intrus"));
    }

    @Test
    void restore_desactive_quandApprentissageFerme_nePerdPasLesDonnees() {
        // La bascule coupe la COLLECTE et le boost, pas le droit de
        // sauvegarder/restaurer : un restore pendant l'arrêt remplit
        // l'historique, la réactivation le retrouve.
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.setEnabled(false);
        CompletionAcceptanceHistory source = new CompletionAcceptanceHistory();
        source.record("java", "Text");
        h.restore(source.snapshot());
        assertEquals(1, h.size());
        assertEquals(0L, h.boost("java", "Text"), "mais le boost reste coupé");
        h.setEnabled(true);
        assertTrue(h.boost("java", "Text") > 0L);
    }

    @Test
    void clear_apresRestore_repartDeZero() {
        CompletionAcceptanceHistory source = new CompletionAcceptanceHistory();
        source.record("java", "Text");
        CompletionAcceptanceHistory h = new CompletionAcceptanceHistory();
        h.restore(source.snapshot());
        h.clear();
        assertEquals(0, h.size());
        assertEquals(0L, h.boost("java", "Text"));
    }
}

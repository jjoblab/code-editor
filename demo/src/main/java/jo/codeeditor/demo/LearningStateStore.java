package jo.codeeditor.demo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import jo.codeeditor.completion.CompletionAcceptanceHistory;
import jo.codeeditor.view.EditorView;

/**
 * Le magasin d'état de l'apprentissage de la démo (4.6.0) — la
 * démonstration OBSERVABLE de la persistance de
 * {@code CompletionAcceptanceHistory} : photographie en JSON dans
 * {@code onStop}, relecture au démarrage suivant.
 *
 * <p>Le format est intentionnellement simple ({@code version} +
 * {@code {langage → {identifiant → [fréquence, séquence]}}}) : la
 * bibliothèque n'impose RIEN (elle expose la structure plate, l'hôte
 * choisit son stockage) — la démo montre le chemin
 * JSON/SharedPreferences, un hôte réel préférera DataStore ou un
 * fichier.</p>
 */
final class LearningStateStore {

    private LearningStateStore() {
    }

    /**
     * Photographie l'historique de la vue en JSON — la chaîne à
     * sauvegarder par l'hôte dans {@code onStop}.
     */
    static String snapshotJson(EditorView view) {
        JSONObject root = new JSONObject();
        try {
            root.put("version",
                    CompletionAcceptanceHistory.Snapshot.FORMAT_VERSION);
            JSONObject languages = new JSONObject();
            for (Map.Entry<String, Map<String, long[]>> lang
                    : view.completionHistory.snapshot().entries().entrySet()) {
                JSONObject compartment = new JSONObject();
                for (Map.Entry<String, long[]> id : lang.getValue().entrySet()) {
                    compartment.put(id.getKey(), new JSONArray(id.getValue()));
                }
                languages.put(lang.getKey(), compartment);
            }
            root.put("languages", languages);
        } catch (JSONException impossible) {
            // Map/long[] vers JSONObject : aucune cause d'échec réelle.
            return null;
        }
        return root.toString();
    }

    /**
     * Restaure l'historique de la vue depuis le JSON sauvegardé —
     * no-op silencieux si null, vide, corrompu ou d'une version
     * inconnue (la bibliothèque valide la version elle-même, la
     * démo ne fait que décoder).
     */
    static void restore(EditorView view, String json) {
        if (view == null || json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("version", -1);
            // Sans objet « languages », le payload n'est pas de NOTRE
            // format (corruption, autre époque) — no-op, on n'efface
            // JAMAIS l'existant sur des données illisibles. Un
            // historique VIDE légitime voyage avec « languages » : {}.
            JSONObject languages = root.optJSONObject("languages");
            if (languages == null) {
                return;
            }
            Map<String, Map<String, long[]>> data = new LinkedHashMap<>();
            Iterator<String> langs = languages.keys();
            while (langs.hasNext()) {
                String lang = langs.next();
                Map<String, long[]> compartment = new LinkedHashMap<>();
                JSONObject entries = languages.optJSONObject(lang);
                if (entries != null) {
                    Iterator<String> ids = entries.keys();
                    while (ids.hasNext()) {
                        String id = ids.next();
                        JSONArray pair = entries.optJSONArray(id);
                        if (pair == null || pair.length() != 2) {
                            continue;
                        }
                        compartment.put(id,
                                new long[]{pair.getLong(0), pair.getLong(1)});
                    }
                }
                data.put(lang, compartment);
            }
            view.completionHistory.restore(
                    new CompletionAcceptanceHistory.Snapshot(data, version));
        } catch (JSONException corrompu) {
            // Données illisibles (troncature, encodage d'une autre
            // époque) — no-op : on n'efface jamais l'existant sur du
            // corrompu, on ne crashe jamais au démarrage.
        }
    }
}

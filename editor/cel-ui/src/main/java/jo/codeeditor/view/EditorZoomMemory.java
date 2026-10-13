package jo.codeeditor.view;

import jo.codeeditor.view.input.EditorZoomController;

import java.util.HashMap;
import java.util.Map;

/**
 * B12 (feuille de route « écarts Android Studio », écart P1-3) —
 * mémoire PROCESS des échelles de police, par clé de type de fichier.
 *
 * <p>Avant : le pincement B9 était ancré mais l'échelle (0,6–2,6) ne
 * vivait qu'en mémoire de VUE — à la réouverture d'un fichier dans une
 * vue neuve (nouvel onglet, activité recréée sans état sauvé), tout
 * revenait à ×1, même si l'utilisateur avait réglé ce type de fichier
 * la veille. Android Studio mémorise le zoom par éditeur de façon
 * persistante.</p>
 *
 * <p>Le mécanisme : l'hôte pose une clé par type de fichier sur la vue
 * ({@code EditorView.setZoomMemoryKey(".java")}) ; chaque mutation
 * d'échelle (pincement, A+/A−, {@code setFontScale}) enregistre
 * l'échelle bornée sous la clé courante ; une vue neuve qui repose la
 * même clé récupère l'échelle enregistrée. La clé est choisie par
 * l'HÔTE (extension, id de langage…) — la bibliothèque n'impose pas sa
 * granularité. Un hôte qui ne pose jamais de clé garde le comportement
 * historique : aucune mémoire, aucun changement.</p>
 *
 * <p>Portée : le PROCESS (statique). Survit aux recreations de vues et
 * d'activités ; la rotation reste couverte par l'état d'instance B17,
 * PLUS PRÉCIS (l'échelle exacte de CETTE vue l'emporte sur la mémoire
 * du type lors de la restauration — l'ordre d'appel de l'hôte puis
 * {@code onRestoreInstanceState} le garantit). Ne survit PAS au redémarr
 * du processus — pour cela l'hôte persisterait les clés/valeurs lui-même
 * (Settings), la classe reste alors un cache d'appoint.</p>
 *
 * <p>Thread : thread UI uniquement (contrat des vues Android) ;
 * aucune synchronisation pour ne pas payer un verrou par frame de
 * pincement.</p>
 *
 * @author jo@Dev
 */
public final class EditorZoomMemory {

    /** Échelles mémorisées par clé d'hôte (extension/langage). */
    private static final Map<String, Float> BY_KEY = new HashMap<>();

    private EditorZoomMemory() {
    }

    /**
     * Enregistre l'échelle pour la clé — bornée à la plage du zoom
     * ([0,6, 2,6], même source de vérité que le contrôleur).
     * Clé nulle ignorée (pas de mémoire sans clé).
     */
    public static void remember(String key, float scale) {
        if (key == null) return;
        BY_KEY.put(key, EditorZoomController.clampFontScale(scale));
    }

    /**
     * Renvoie l'échelle mémorisée pour la clé, 1 par défaut (clé
     * inconnue ou nulle — un type jamais zoomé est à l'échelle de base).
     */
    public static float recall(String key) {
        if (key == null) return 1f;
        Float scale = BY_KEY.get(key);
        return scale != null ? scale : 1f;
    }

    /** Vrai si la clé porte une échelle mémorisée (clé nulle : faux). */
    public static boolean isRemembered(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /** Oublie la clé (no-op si nulle/inconnue). */
    public static void forget(String key) {
        if (key != null) BY_KEY.remove(key);
    }

    /** Purge toute la mémoire (état de départ — utilisé par les tests). */
    public static void clear() {
        BY_KEY.clear();
    }
}

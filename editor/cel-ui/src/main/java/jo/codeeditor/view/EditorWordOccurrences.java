package jo.codeeditor.view;

import android.os.Handler;
import android.os.Looper;

import java.util.List;

import jo.codeeditor.document.Selection;
import jo.codeeditor.find.Match;
import jo.codeeditor.find.WordOccurrences;
import jo.codeeditor.session.EditorSession;

/**
 * Surbrillance des occurrences entières du mot sous le caret (4.4.0).
 *
 * <p>Au repos du caret ou de la frappe (débounce {@link #DEBOUNCE_MS}),
 * toutes les occurrences ENTIÈRES du mot touché par le caret s'allument
 * dans la voie de rendu des surlignages de recherche
 * ({@code theme.findMatch} / l'occurrence du caret en
 * {@code theme.findCurrent}) — géométrie wrap/plis réutilisée telle
 * quelle. La règle « au moins deux occurrences » filtre les mots
 * uniques ; les littéraux numériques et la ponctuation ne s'allument
 * jamais (voir {@link WordOccurrences#isEligibleWord}).</p>
 *
 * <p><b>Cédage de la voie</b> : la voie find est une ressource partagée.
 * Ce producteur pousse SANS revendiquer la propriété
 * ({@code FindState.set(…, false)}) et s'efface devant la session de
 * recherche OUVERTE ({@code searchOwned}) comme devant un hôte ayant
 * posé ses propres surlignages ({@code manualOwned}) — à la fermeture
 * de la recherche, le prochain déplacement du caret ou la prochaine
 * édition ré-allume les occurrences.</p>
 *
 * <p><b>Coupures</b> : très gros documents ({@code isLarge} — le
 * balayage complet est reporté, miroir du filtrage des diagnostics
 * d'onglets), sélections en plage (pas de « mot sous le caret »),
 * bascule publique {@code setWordOccurrencesEnabled(false)}.</p>
 *
 * <p><b>Thread</b> : tout vit sur le thread UI (handler main-looper
 * propre, miroir du contrôleur de recherche) — l'état find est confiné
 * au thread UI par contrat.</p>
 */
final class EditorWordOccurrences {

    /**
     * Repos avant calcul — entre le débounce de la recherche (160 ms) et
     * celui des document highlights (400 ms).
     */
    static final long DEBOUNCE_MS = 300L;

    private final EditorView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable task = this::run;
    private boolean enabled = true;

    EditorWordOccurrences(EditorView view) {
        this.view = view;
    }

    /** Programme le calcul au repos (réunit les déplacements rapprochés). */
    void schedule() {
        handler.removeCallbacks(task);
        handler.postDelayed(task, DEBOUNCE_MS);
    }

    /** Annule toute tâche en attente (détachement, changement de session). */
    void cancel() {
        handler.removeCallbacks(task);
    }

    /** Bascule publique — la désactivation purge la voie si on la possède. */
    void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (!on) {
            cancel();
            if (ownsLane()) view.setFindHighlights(null, -1);
        } else {
            schedule();
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    /** La voie est-elle libre (ni recherche ouverte, ni hôte propriétaire) ? */
    private boolean ownsLane() {
        return !view.find.searchOwned && !view.find.manualOwned;
    }

    private void run() {
        if (!enabled || !ownsLane()) return;
        EditorSession session = view.session;
        if (session == null || session.getDocument() == null) return;
        // Mode gros fichiers EFFECTIF (4.5.0) : seuils du document OU
        // forçage hôte — le balayage complet est coupé et la voie
        // purgée (les offsets d'un précédent calcul restent valides mais
        // le coût par repos de frappe n'est plus defendable).
        if (view.isLargeFileModeActive()) {
            view.setFindHighlights(null, -1);
            return;
        }
        // Pendant un drag de sélection, le geste dirige — le recalcul
        // reprendra au déplacement suivant.
        if (view.isSelectionDragActive()) return;
        Selection sel = session.getSelection();
        if (!sel.isCursor()) {
            // Sélection en plage : pas de « mot sous le caret » — on éteint.
            view.setFindHighlights(null, -1);
            return;
        }
        String text = session.getText();
        int[] range = WordOccurrences.wordAt(text, sel.start);
        if (range == null) {
            view.setFindHighlights(null, -1);
            return;
        }
        String word = text.substring(range[0], range[1]);
        if (!WordOccurrences.isEligibleWord(word)) {
            view.setFindHighlights(null, -1);
            return;
        }
        List<Match> matches = WordOccurrences.findAll(text, word);
        if (matches.size() < 2) {
            // Règle « au moins deux » : un mot unique n'allume rien.
            view.setFindHighlights(null, -1);
            return;
        }
        // Poussée SANS revendication : l'occurrence du caret est la
        // courante (teinte forte), les autres la teinte simple.
        view.find.set(matches, WordOccurrences.indexOfContaining(matches, range[0]), false);
        view.invalidate();
    }
}

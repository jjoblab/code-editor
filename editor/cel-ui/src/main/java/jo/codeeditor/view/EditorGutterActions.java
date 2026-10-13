package jo.codeeditor.view;

import android.graphics.RectF;

import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jo.codeeditor.view.chrome.GutterView;

/**
 * Registre des <b>actions de gouttière injectées par l'hôte</b> (B15,
 * P2-2) — l'API {@code GutterAction} généralisée fondée sur B9c.
 *
 * <p>Porteur de l'état, délibérément HORS d'EditorView (budget de
 * champs/méthodes tenu) : une seule instance vit sur le champ public
 * {@link EditorView#gutterActions}. L'hôte enregistre ses actions
 * (points d'arrêt, « exécuter ce test », flèches de diff…) et la
 * gouttière les dessine dans la zone des numéros de leur ligne —
 * pill B9c, même géométrie déterministe au dessin et au hit-test
 * ({@code GutterView.hostActionRect}).</p>
 *
 * <ul>
 *   <li>{@link #add} — enregistre (retire au passage toute action de
 *       même id OU de même ligne : UNE pill par ligne, la dernière
 *       inscription gagne) puis invalide ;</li>
 *   <li>{@link #remove(String)} / {@link #clear} — retrait ciblé /
 *       vidage (l'hôte change de fichier) ;</li>
 *   <li>{@link #actionForLine(int)} — consultation (première action de
 *       la ligne, consommée par le rendu de GutterView) ;</li>
 *   <li>{@code hitTest} (interne) — résout l'action dont la pill
 *       contient le point : ligne par {@code docLineForScreenY}
 *       (wrap + plis), rangée hors écran = pas de zone tactile
 *       fantôme (parité B9c), rect déterministe.</li>
 * </ul>
 *
 * <p>Les actions sont des données d'HÔTE : elles survivent à
 * {@code setSession} (parité avec les actions de pill de sélection) —
 * l'hôte est responsable de leur pertinence, la démo vide le registre
 * au changement d'échantillon.</p>
 *
 * @author jjoblab
 */
public final class EditorGutterActions {

    private final EditorView view;

    /** Les actions enregistrées (ordre d'injection, une par ligne au plus). */
    private final List<GutterAction> actions = new ArrayList<>(0);

    /** Rect de hit-test réutilisé (aucune allocation par tap). */
    private final RectF hitRect = new RectF();

    EditorGutterActions(EditorView view) {
        this.view = view;
    }

    /**
     * Enregistre l'action. Retire au passage toute action de même
     * {@code id} ou posée sur la même {@code line} — une seule pill
     * par ligne (la dernière inscription gagne), jamais d'empilement.
     * {@code null} est ignoré. Invalide la vue.
     */
    public void add(GutterAction action) {
        if (action == null) return;
        removeInternal(action.id, action.line);
        actions.add(action);
        view.invalidate();
    }

    /**
     * Retire l'action d'identifiant {@code id} (rien si absent).
     *
     * @return vrai si une action a été retirée
     */
    public boolean remove(String id) {
        if (id == null) return false;
        for (Iterator<GutterAction> it = actions.iterator(); it.hasNext(); ) {
            if (id.equals(it.next().id)) {
                it.remove();
                view.invalidate();
                return true;
            }
        }
        return false;
    }

    /** Vide le registre (l'hôte change de fichier). Idempotent. */
    public void clear() {
        if (actions.isEmpty()) return;
        actions.clear();
        view.invalidate();
    }

    /** Nombre d'actions enregistrées. */
    public int size() {
        return actions.size();
    }

    /**
     * Première action posée sur la ligne donnée ({@code null} sinon) —
     * consommée par le rendu de la gouttière (une pill par ligne).
     */
    public GutterAction actionForLine(int line) {
        for (int i = 0; i < actions.size(); i++) {
            GutterAction a = actions.get(i);
            if (a.line == line) return a;
        }
        return null;
    }

    /**
     * B15 — résout l'action dont la pill contient le point ({@code null}
     * sinon). DÉTERMINISTE : même formule que le dessin
     * ({@code GutterView.hostActionRect} = pill B9c) — aucun état
     * mutable partagé entre le rendu et le hit-test. La ligne est
     * résolue par {@code docLineForScreenY} (wrap + plis) ; une rangée
     * hors écran ne touche rien (pas de zone tactile fantôme, parité
     * B9c), une ligne sans action non plus.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public GutterAction hitTest(float x, float y) {
        if (actions.isEmpty() || view.session == null
                || view.session.getDocument() == null) {
            return null;
        }
        int line = view.docLineForScreenY(y);
        GutterAction action = actionForLine(line);
        if (action == null) return null;
        float rowTop = view.docLineToY(line) - view.vOffset;
        float lineHeight = view.metrics.getLineHeight();
        // Rangée hors écran (repliée au-dessus ou en dessous du
        // viewport) : la pill n'est pas visible — pas de zone tactile
        // fantôme (parité B9c).
        if (rowTop + lineHeight < 0 || rowTop > view.getHeight()) return null;
        GutterView.hostActionRect(view.metrics,
                view.getResources().getDisplayMetrics().density,
                rowTop, lineHeight, hitRect);
        return hitRect.contains(x, y) ? action : null;
    }

    /** Retire les actions de même id ou de même ligne (remplacement). */
    private void removeInternal(String id, int line) {
        for (Iterator<GutterAction> it = actions.iterator(); it.hasNext(); ) {
            GutterAction a = it.next();
            if (a.line == line || (id != null && id.equals(a.id))) {
                it.remove();
            }
        }
    }
}

package jo.codeeditor.view;

import java.util.Collections;
import java.util.List;

/**
 * Résultat d'une requête de complétion auprès du fournisseur de la vue :
 * les candidats <b>et</b> le drapeau « liste tronquée ».
 *
 * <p>Historiquement, {@link EditorView.CompletionProvider#provide} ne
 * renvoyait qu'une liste : un fournisseur dont le backend plafonne le
 * nombre de candidats ne pouvait pas signaler que la liste était
 * partielle, et le client filtrait localement sur une base tronquée —
 * un candidat écarté par le plafond ne réapparaissait jamais. Le drapeau
 * {@link #isIncomplete} transporte cette information : quand il est
 * vrai, la base est partielle et le client sait qu'une re-requête peut
 * faire réapparaître des candidats.</p>
 *
 * <p>Construisez ce résultat depuis la variante
 * {@link EditorView.CompletionProvider#provideResult} ; la fabrique
 * {@link #CompletionResult(List)} (drapeau absent) couvre le cas
 * « liste complète » et reste le comportement des fournisseurs
 * existants.</p>
 *
 * @since 4.3.0
 */
public final class CompletionResult {

    /** Les candidats (jamais null ; vide = aucune proposition). */
    public final List<jo.codeeditor.completion.CompletionSession.Item> items;

    /**
     * Vrai quand le fournisseur signale une liste TRONQUÉE (backend qui
     * plafonne ses réponses) : le filtrage local travaille sur une base
     * partielle. Faux = liste réputée complète.
     */
    public final boolean isIncomplete;

    /** Liste réputée complète (comportement historique). */
    public CompletionResult(List<jo.codeeditor.completion.CompletionSession.Item> items) {
        this(items, false);
    }

    /** Construit un résultat avec drapeau explicite. */
    public CompletionResult(List<jo.codeeditor.completion.CompletionSession.Item> items,
                            boolean isIncomplete) {
        this.items = items != null ? items : Collections.emptyList();
        this.isIncomplete = isIncomplete;
    }

    @Override
    public String toString() {
        return "CompletionResult(items=" + items.size()
                + ", incomplete=" + isIncomplete + ")";
    }
}

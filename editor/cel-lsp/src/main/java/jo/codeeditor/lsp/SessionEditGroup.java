package jo.codeeditor.lsp;

import jo.codeeditor.session.EditorSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Application d'une liste d'éditions LSP (déjà résolues en offsets sur le
 * texte COURANT) à une {@link EditorSession}, en UN SEUL groupe
 * d'annulation.
 *
 * <p>L'ancien chemin reconstruisait le texte entier puis appelait
 * {@code session.replaceRange(0, doc.length(), newText)} : une seule
 * étape d'undo géante qui écrasait caret, sélection et plis, au coût
 * O(n) par correctif rapide. Ici chaque édition passe INDIVIDUELLEMENT
 * par le pipeline commun ({@code replaceRange}, qui décale
 * annotations/plis/composition), triée par offset DÉCROISSANT, entre
 * {@code beginBatch()} et {@code endBatch()} — une seule étape d'annulation,
 * plis hors zone conservés, sélection restaurée.</p>
 *
 * <p>Compatible API 24 — Collections/ArrayList uniquement.</p>
 */
final class SessionEditGroup {

    /** Une édition résolue : offsets [start, end) sur le texte courant. */
    static final class Edit {
        final int start;
        final int end;
        final String text;

        Edit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text != null ? text : "";
        }
    }

    private SessionEditGroup() {
        // Classe utilitaire.
    }

    /**
     * Applique les éditions à la session, tout-ou-rien.
     *
     * @return true si appliqué ; false si les éditions sont invalides
     *         (chevauchement, bornes hors document) — la session n'est
     *         alors PAS modifiée et aucune étape d'annulation n'est
     *         empilée.
     */
    static boolean apply(EditorSession session, List<Edit> edits) {
        if (edits == null || edits.isEmpty()) return true;
        if (session == null) return false;
        int length = session.getText().length();

        // ── Validation complète AVANT toute mutation (tout-ou-rien).
        int n = edits.size();
        for (int i = 0; i < n; i++) {
            Edit e = edits.get(i);
            if (e.start < 0 || e.end < e.start || e.end > length) return false;
        }
        Integer[] ordre = new Integer[n];
        for (int i = 0; i < n; i++) ordre[i] = i;
        java.util.Arrays.sort(ordre, (a, b) -> Integer.compare(edits.get(a).start, edits.get(b).start));
        for (int k = 1; k < n; k++) {
            if (edits.get(ordre[k]).start < edits.get(ordre[k - 1]).end) {
                return false; // chevauchement — interdit par le protocole
            }
        }

        // ── Application : offset DÉCROISSANT (les offsets antérieurs
        // restent valides), un seul groupe d'annulation, sélection
        // restaurée après.
        jo.codeeditor.document.Selection selAvant = session.getSelection();
        boolean ok = true;
        session.beginBatch();
        try {
            for (int k = n - 1; k >= 0; k--) {
                Edit e = edits.get(ordre[k]);
                try {
                    session.replaceRange(e.start, e.end, e.text);
                } catch (RuntimeException ex) {
                    ok = false;
                    break;
                }
            }
        } finally {
            session.endBatch();
        }
        if (!ok) return false;
        // Restaure la sélection d'avant le correctif (clampée) : le
        // caret de l'utilisateur ne doit pas sauter au fil des éditions.
        int max = session.getText().length();
        int start = Math.min(Math.max(0, selAvant.start), max);
        int end = Math.min(Math.max(0, selAvant.end), max);
        session.setSelection(new jo.codeeditor.document.Selection(start, end));
        return true;
    }

    /** Fabrique une édition (lisibilité des appelants). */
    static Edit edit(int start, int end, String text) {
        return new Edit(start, end, text);
    }

    /** Copie défensive. */
    static List<Edit> copyOf(List<Edit> edits) {
        return edits == null ? new ArrayList<>() : new ArrayList<>(edits);
    }
}

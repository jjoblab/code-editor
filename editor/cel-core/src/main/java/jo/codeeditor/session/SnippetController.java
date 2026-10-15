package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.EditSpan;
import jo.codeeditor.snippet.SnippetSession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Contrôleur de session de snippet (L1/D-01) — collaborateur de
 * {@link EditorSession} sur le modèle de {@code RestyleEngine} /
 * {@code ImeBridge} : détient la session de snippet active, son
 * ré-ancrage sur édition, la synchronisation des miroirs et la
 * navigation des champs.
 *
 * <p>Posé par {@link #apply(int, int, String)} à l'acceptation d'un
 * candidat de complétion au format snippet : le texte est inséré APURÉ
 * (aucun marqueur {@code $} résiduel), le premier champ ({@code $1},
 * convention LSP) est sélectionné. Navigation : {@link #next()} (Tab),
 * {@link #prev()} (Shift+Tab), l'arrêt final {@code $0} clôt la
 * session. Toute édition du document ré-ancre les champs ; une édition
 * DANS le champ courant propage son texte aux miroirs de même index
 * (groupés en un seul lot d'annulation) et déclenche
 * {@link EditorSession.OnSnippetEditListener}.</p>
 *
 * <p>La session ne survit ni à undo/redo (offsets non fiables) ni à
 * {@link #end()} (perte de focus, Échap, hôte).</p>
 */
public final class SnippetController {

    private final EditorSession session;
    private SnippetSession activeSnippet;
    private EditorSession.OnSnippetEditListener onSnippetEditListener;

    SnippetController(EditorSession session) {
        this.session = session;
    }

    /** Listener de champ — invoqué sur chaque édition pendant la session. */
    void setOnSnippetEditListener(EditorSession.OnSnippetEditListener listener) {
        this.onSnippetEditListener = listener;
    }

    /**
     * Applique un snippet à la plage de remplacement : insère le texte
     * SANS les marqueurs de tab stops, pose la session de snippet et
     * sélectionne le premier champ.
     *
     * <p>Les offsets des champs sont calculés sur les coordonnées
     * POST-insertion (base {@code start}) — la session est posée
     * APRÈS l'insertion pour que le ré-ancrage de
     * {@code EditorSession.doReplaceRange} ne s'applique pas à
     * l'insertion initiale.</p>
     *
     * <p>Repli : un texte sans tab stop est inséré tel quel (retourne
     * {@code false}, aucune session posée).</p>
     *
     * @param start début de la plage de remplacement (borné)
     * @param end fin de la plage de remplacement (bornée, exclusive)
     * @param snippetText le texte du snippet (marqueurs $1/${1:ph}…)
     * @return vrai si une session de snippet est active
     */
    public boolean apply(int start, int end, String snippetText) {
        EditorDocument doc = session.getDocument();
        if (snippetText == null) snippetText = "";
        start = Math.max(0, Math.min(start, doc.length()));
        end = Math.max(start, Math.min(end, doc.length()));
        SnippetSession parsed = SnippetSession.parse(snippetText, start);
        if (parsed == null) {
            // Aucun tab stop : insertion brute, comportement repli.
            session.replaceRange(start, end, snippetText);
            return false;
        }
        session.replaceRange(start, end, parsed.plainText());
        activeSnippet = parsed;
        SnippetSession.TabStop first = parsed.current();
        if (first != null) {
            session.setSelection(Selection.range(first.start, first.end));
        }
        return true;
    }

    /**
     * Tab : passe au champ suivant. Au dernier champ, franchit
     * l'arrêt final {@code $0} — caret posé, session close.
     *
     * @return faux si aucune session n'est active
     */
    public boolean next() {
        return step(false);
    }

    /**
     * Shift+Tab : revient au champ précédent.
     *
     * @return faux si aucune session n'est active ou si l'on est déjà
     *         au premier champ (la touche retombe sur la désindentation)
     */
    public boolean prev() {
        return step(true);
    }

    private boolean step(boolean backward) {
        if (activeSnippet == null) return false;
        SnippetSession.TabStop stop = backward ? activeSnippet.prev() : activeSnippet.next();
        if (stop == null) {
            if (backward) {
                // Déjà au premier champ : laisser la touche retomber.
                return false;
            }
            // Navigation épuisée SANS arrêt $0 : terminer à la fin du
            // dernier champ.
            int caret = activeSnippet.finish();
            activeSnippet = null;
            session.setSelection(clamp(caret));
            return true;
        }
        if (stop.index == 0) {
            // Arrêt final $0 : caret posé, la session se clôt.
            activeSnippet = null;
            session.setSelection(clamp(stop.start));
            return true;
        }
        session.setSelection(Selection.range(stop.start, stop.end));
        return true;
    }

    /** Indique si une session de snippet est active (champs navigables). */
    public boolean isActive() {
        return activeSnippet != null;
    }

    /**
     * Termine la session de snippet active sans déplacer le caret
     * (hôte, perte de focus, Échap).
     */
    public void end() {
        activeSnippet = null;
    }

    /**
     * La session de snippet active, ou null (lecture hôte — p. ex.
     * surligner les champs via {@code fieldRanges()}).
     */
    public SnippetSession get() {
        return activeSnippet;
    }

    /**
     * Ré-ancrage après une édition (appelé par
     * {@code EditorSession.doReplaceRange}) : les plages suivent
     * l'édition, les miroirs du champ édité se synchronisent, le
     * listener de champ est notifié.
     */
    void onEdit(EditSpan span, String insertion) {
        if (activeSnippet == null) return;
        SnippetSession.TabStop curBefore = activeSnippet.current();
        // L'édition remplace [span.start, span.start+removed) :
        // intersecte-t-elle le champ courant (plage PRÉ-édition) ?
        boolean dansChamp = curBefore != null
                && span.start <= curBefore.end
                && span.start + span.removed >= curBefore.start;
        activeSnippet.onEdit(span);
        if (dansChamp) {
            synchroniserMiroirs();
        }
        if (onSnippetEditListener != null) {
            onSnippetEditListener.onSnippetEdit(
                    span.start, span.start + span.removed, insertion);
        }
    }

    /**
     * Synchronise les miroirs du champ courant : chaque occurrence liée
     * de l'index courant reçoit le texte du champ. Les remplacements
     * passent par {@code EditorSession.replaceRange} (pipeline complet
     * + ré-ancrage récursif des plages restantes), groupés en un seul
     * lot d'annulation. L'édition d'un miroir n'est jamais « dans le
     * champ courant » (plages disjointes) — pas de récursion infinie.
     */
    private void synchroniserMiroirs() {
        SnippetSession snippet = activeSnippet;
        if (snippet == null) return;
        SnippetSession.TabStop cur = snippet.current();
        if (cur == null) return;
        EditorDocument doc = session.getDocument();
        String curText = doc.subText(clamp(cur.start), clamp(cur.end));
        List<SnippetSession.TabStop> miroirs = new ArrayList<>();
        for (SnippetSession.TabStop s : snippet.getStops()) {
            if (s.index == cur.index && s != cur) miroirs.add(s);
        }
        if (miroirs.isEmpty()) return;
        // Ordre croissant : chaque remplacement ré-ancre les plages des
        // miroirs suivants.
        miroirs.sort(Comparator.comparingInt(s -> s.start));
        session.beginBatch();
        try {
            for (SnippetSession.TabStop m : miroirs) {
                int ms = clamp(m.start);
                int me = Math.max(ms, clamp(m.end));
                String text = doc.subText(ms, me);
                if (!text.equals(curText)) {
                    session.replaceRange(ms, me, curText);
                }
            }
        } finally {
            session.endBatch();
        }
    }

    private int clamp(int offset) {
        EditorDocument doc = session.getDocument();
        return Math.max(0, Math.min(offset, doc.length()));
    }
}

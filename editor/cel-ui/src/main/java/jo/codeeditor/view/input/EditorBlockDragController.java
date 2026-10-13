package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import jo.codeeditor.document.Selection;

/**
 * B13 (P1-2 « drag-déposer de bloc ») — contrôleur du mode
 * « déplacer par glisser-déposer », armé par la pill de sélection
 * (item ✥ Move / {@code BuiltinSelectionActions.blockDrag}).
 *
 * <p>Pourquoi un MODE armé plutôt qu'un geste direct : le drag au
 * doigt étend déjà la sélection (appui long, parité plateforme) —
 * les deux gestes ne peuvent pas cohabiter sur le même toucher. La
 * convention retenue (recommandation de l'analyse des écarts) : le
 * tap sur l'item de la pill ARME le mode, le geste SUIVANT
 * appartient au bloc — drag = déplacement, tap = annulation.</p>
 *
 * <p>Cycle de vie :</p>
 * <ul>
 *   <li>{@link #arm()} — photographie la sélection (bornes + texte,
 *       offsets AVANT tout mouvement), appelé par
 *       {@code EditorView.armBlockDrag()} depuis le perform de la
 *       pill ;</li>
 *   <li>{@link #beginGesture(float, float)} au DOWN du geste suivant
 *       (routé par EditorInputHandler — le geste est englouti par le
 *       bloc) ;</li>
 *   <li>{@link #dragTo(float, float)} à chaque MOVE au-delà du
 *       touch-slop — le caret FANTÔME ({@link #dropOffset}) suit le
 *       doigt via {@code offsetAt}, la loupe est stabilisée (Y snappé
 *       à la rangée réglée, X amorti — même sémantique que le drag de
 *       poignée B9) ; l'auto-scroll de bord est partagé avec le
 *       drag-select (le tick de EditorSelectionGestures ré-applique
 *       ici) ;</li>
 *   <li>{@link #drop()} au UP —
 *       {@code EditorSession.moveRange(sourceStart, sourceEnd,
 *       dropOffset)} : suppression + réinsertion en UNE étape d'undo,
 *       sélection ré-épinglée sur le texte déplacé ; un dépôt dans la
 *       source est un no-op propre ;</li>
 *   <li>{@link #cancel()} au CANCEL / pincement / changement de
 *       session — le document est intact, la sélection d'origine
 *       reste ;</li>
 *   <li>un tap (UP sous le slop) = annulation du mode, pas un
 *       dépôt.</li>
 * </ul>
 *
 * <p>Rendu (l'état est lu par les peintres) : la carte flottante du
 * bloc ({@code EditorChromePainter}) suit le doigt, le caret fantôme
 * d'insertion ({@code EditorRenderer}) marque le point de dépôt, la
 * loupe ({@code drawMagnifier}) agrandit la fenêtre du point de
 * dépôt.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorBlockDragController {

    private final EditorView view;

    /** Bornes de la plage source (offsets AVANT tout mouvement) ; -1 = désarmé. */
    private int sourceStart = -1;
    private int sourceEnd = -1;
    /** Texte du bloc photographié à l'armement (rendu de la carte, une seule lecture). */
    private String blockText = "";
    /** Vrai dès que le doigt a dépassé le touch-slop (le déplacement est vivant). */
    private boolean engaged = false;
    /** Offset du caret fantôme — le point de dépôt sous le doigt. */
    private int dropOffset = -1;
    /** Position du doigt (carte flottante + auto-scroll de bord). */
    private float fingerX;
    private float fingerY;

    /** Possédé par EditorView (public pour l'instanciation inter-packages, @RestrictTo garde le périmètre). */
    public EditorBlockDragController(EditorView view) {
        this.view = view;
    }

    // ── Armement / désarmement ───────────────────────────────

    /**
     * Arme le mode pour la sélection courante — appelé par
     * {@code EditorView.armBlockDrag()} (le perform de la pill ✥).
     * La pill est déjà refermée par le dispatcher du tap (contrat
     * Copy/Cut/Paste) ; les poignées sont masquées.
     *
     * @return true si armé (sélection non réduite au caret, session
     *         vivante et éditable)
     */
    boolean arm() {
        if (view.session == null) return false;
        Selection sel = view.session.getSelection();
        if (sel.isCursor() || view.session.isReadOnly()) return false;
        sourceStart = Math.min(sel.start, sel.end);
        sourceEnd = Math.max(sel.start, sel.end);
        blockText = view.session.selectedText();
        engaged = false;
        dropOffset = sourceStart;
        view.invalidate();
        return true;
    }

    /** Désarme sans rien toucher au document (sélection d'origine conservée). */
    void disarm() {
        sourceStart = -1;
        sourceEnd = -1;
        blockText = "";
        engaged = false;
        dropOffset = -1;
    }

    /** Annulation publique (changement de session, détachement, hôte) : désarme + referme la loupe. */
    public void cancel() {
        if (sourceStart < 0) return;
        disarm();
        view.dismissMagnifier();
        view.invalidate();
    }

    /** Vrai si le mode est armé (le prochain geste appartient au bloc). */
    public boolean isArmed() {
        return sourceStart >= 0;
    }

    /** Vrai si le déplacement est ENGAGÉ (doigt au-delà du slop) — loupe, carte, caret fantôme visibles. */
    public boolean isActive() {
        return sourceStart >= 0 && engaged;
    }

    // ── Geste ────────────────────────────────────────────────

    /** DOWN du geste suivant l'armement : mémorise le point de départ (rien de visible). */
    void beginGesture(float x, float y) {
        fingerX = x;
        fingerY = y;
    }

    /**
     * MOVE au-delà du touch-slop (et re-application du tick
     * d'auto-scroll après un défilement) : le caret fantôme suit le
     * doigt, la loupe est stabilisée façon B9 (Y snappé au centre de
     * la rangée portant le point de dépôt, X amorti exponentiellement).
     */
    void dragTo(float x, float y) {
        if (sourceStart < 0) return;
        engaged = true;
        fingerX = x;
        fingerY = y;
        int offset = view.offsetAt(x, y);
        dropOffset = offset;
        // Loupe stabilisée (même sémantique que le drag de poignée) :
        // Y = centre de la RANGÉE du point de dépôt, X amorti.
        float targetY = y;
        float[] row = view.caretScreenPos(offset);
        targetY = row[1] + view.metrics.getLineHeight() * 0.5f;
        view.magnifierX = view.magnifierActive
                ? view.magnifierX + (x - view.magnifierX) * 0.55f
                : x;
        view.magnifierY = targetY;
        view.magnifierActive = true;
        view.invalidate();
    }

    /**
     * UP : dépose le bloc ({@code moveRange} — une étape d'undo) si le
     * drag était engagé ; un TAP (jamais engagé) annule le mode. Le
     * dépôt dans la source est un no-op propre (le document ne change
     * pas, la sélection d'origine reste).
     *
     * @return true si le document a changé
     */
    boolean drop() {
        if (sourceStart < 0) return false;
        boolean moved = false;
        if (engaged && view.session != null) {
            moved = view.session.moveRange(sourceStart, sourceEnd, dropOffset);
            if (moved) view.onTextChanged();
        }
        disarm();
        view.dismissMagnifier();
        view.invalidate();
        return moved;
    }

    // ── État lu par les peintres ─────────────────────────────

    /** Début de la plage source (offsets d'armement) ; -1 si désarmé. */
    public int getSourceStart() { return sourceStart; }

    /** Fin (exclue) de la plage source ; -1 si désarmé. */
    public int getSourceEnd() { return sourceEnd; }

    /** Texte photographié du bloc (rendu de la carte flottante). */
    public String getBlockText() { return blockText; }

    /** Offset du caret fantôme (point de dépôt) ; -1 si non engagé. */
    public int getDropOffset() { return engaged ? dropOffset : -1; }

    /** Position du doigt (ancrage de la carte flottante). */
    public float getFingerX() { return fingerX; }

    /** Position du doigt (ancrage de la carte flottante). */
    public float getFingerY() { return fingerY; }
}

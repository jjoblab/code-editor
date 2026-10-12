package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Popup ancré de squiggle (D1) : le niveau 1 de la lecture d'un
 * diagnostic au tap. Un tap au <b>cœur</b> d'une plage soulignée (bords
 * ±1 caractère exclus) ouvre une petite fiche <b>ancée au-dessus de la
 * ligne</b> (flip en dessous si besoin, clamp X) — <b>non modale</b> :
 * aucun scrim, un tap ailleurs la referme.
 *
 * <p>Architecture à deux niveaux, rien n'est supprimé :</p>
 * <ul>
 *   <li><b>niveau 1 (cette classe)</b> — popup compact ancré au mot :
 *       en-tête sévérité + message replié (≤ 3 lignes) + boutons des
 *       quick-fixes (résolus à l'ouverture par
 *       {@code resolveCodeActionsAt} — la voie du lot 1) + bouton
 *       « See all » qui bascule vers la fiche basse existante ;</li>
 *   <li><b>niveau 2 (fiche basse existante)</b> — la vue complète :
 *       message intégral, toutes les actions.</li>
 * </ul>
 *
 * <p><b>Multi-diagnostics</b> : plusieurs diagnostics couvrant l'offset
 * tapé → mini-liste ancrée (une rangée par diagnostic : sévérité +
 * première ligne du message, tri sévérité décroissante) ; un tap sur
 * une rangée passe au popup de détail de CE diagnostic.</p>
 *
 * <p><b>Parité caret</b> (motif pendingTapDismiss : le premier tap
 * arme, le second décide) : le tap qui ouvre ne pose PAS le caret ; un
 * re-tap sur la même plage armée le referme et pose le caret (décision
 * dans {@code EditorTapResolver}). L'état vit dans EditorView
 * ({@code squigglePopupVisible} / {@code squigglePopupItem} /
 * {@code squigglePopupChoices}) ; la géométrie partagée est
 * {@link EditorPopupAnchors#squigglePopupMetrics(EditorView)}.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorSquigglePopups {

    private final EditorView view;
    /** Référence au gestionnaire pour la fermeture croisée des popups. */
    private final EditorPopupManager popups;

    EditorSquigglePopups(EditorView view, EditorPopupManager popups) {
        this.view = view;
        this.popups = popups;
    }

    /**
     * Ouvre le popup ancré pour les diagnostics <b>squigglés</b> (le
     * voile gris Unnecessary n'est pas tappable) couvrant strictement
     * {@code offset} — bords ±1 caractère exclus. Les diagnostics sont
     * triés par sévérité décroissante : un seul → popup de détail
     * direct ; plusieurs → mini-liste. Vrai si le popup s'est ouvert.
     */
    boolean squigglePopupAt(int offset, float tapX) {
        if (view.session == null) return false;
        List<DiagnosticShift.Diagnostic> covering = squiggleDiagnosticsAt(offset);
        if (covering.isEmpty()) return false;
        covering.sort(Comparator.comparingInt((DiagnosticShift.Diagnostic d) -> -d.severity)
                .thenComparingInt(d -> d.start));
        DiagnosticShift.Diagnostic primary = covering.get(0);
        view.squigglePopupItem = primary;
        view.squigglePopupChoices = covering.size() > 1 ? covering : new ArrayList<>();
        view.squigglePopupVisible = true;
        closeOthers();
        if (covering.size() == 1) {
            // Détail direct : quick-fixes résolus à l'ouverture (voie
            // « features », cache mono-ligne de resolveCodeActionsAt).
            popups.resolveCodeActionsAt(primary.start, primary.start,
                    primary.start, false);
        }
        view.invalidate();
        return true;
    }

    /** Passe la mini-liste au popup de détail du diagnostic choisi. */
    void showSquiggleDetail(DiagnosticShift.Diagnostic d) {
        if (d == null || view.session == null) return;
        view.squigglePopupItem = d;
        view.squigglePopupChoices = new ArrayList<>();
        view.squigglePopupVisible = true;
        closeOthers();
        popups.resolveCodeActionsAt(d.start, d.start, d.start, false);
        view.invalidate();
    }

    /**
     * Les diagnostics tappables à {@code offset} : soulignés (sévérité
     * Error/Warning, PAS le voile Unnecessary) et strictement
     * intérieurs à la plage — un tap sur les bords ±1 caractère reste
     * un placement de caret (l'utilisateur veut se positionner avant /
     * après la plage).
     */
    private List<DiagnosticShift.Diagnostic> squiggleDiagnosticsAt(int offset) {
        List<DiagnosticShift.Diagnostic> out = new ArrayList<>();
        for (DiagnosticShift.Diagnostic d : view.session.getDiagnostics()) {
            if (d == null || d.unnecessary) continue;
            if (d.start + 1 < offset && offset < d.end) out.add(d);
        }
        return out;
    }

    /** Fermeture croisée des popups exclusifs (le popup ancré est léger). */
    private void closeOthers() {
        if (view.completionVisible) popups.dismissCompletion();
        if (view.quickDocVisible) popups.dismissQuickDoc();
        if (view.signatureHelpVisible) popups.dismissSignatureHelp();
        if (view.codeActionsPopupVisible) popups.dismissCodeActions();
        if (view.navMenuVisible) popups.dismissNavMenu();
        if (view.diagnosticPopupVisible) popups.dismissDiagnosticPopup();
        if (view.diagnosticListSheetLine >= 0) popups.dismissDiagnosticListSheet();
    }

    /**
     * Résout un hit du popup (via {@code EditorPopupHitTester}) :
     * mini-liste → choix de rangée (passe au détail) ; détail →
     * application du quick-fix de la rangée (puis fermeture, le
     * comportement de la fiche) ou bascule « See all » vers la fiche
     * basse existante.
     */
    void handleSquiggleHit(int hit) {
        if (!view.squigglePopupVisible || view.session == null) return;
        if (hit < 0) return;
        if (!view.squigglePopupChoices.isEmpty()) {
            // Mini-liste : la rangée choisie devient le détail.
            if (hit < view.squigglePopupChoices.size()) {
                showSquiggleDetail(view.squigglePopupChoices.get(hit));
            }
            return;
        }
        EditorPopupAnchors.SquigglePopupMetrics m =
                EditorPopupAnchors.squigglePopupMetrics(view);
        if (m == null || view.squigglePopupItem == null) return;
        if (m.seeAll && hit >= m.rows) {
            // « See all » → fiche basse existante (niveau 2, vue complète).
            DiagnosticShift.Diagnostic d = view.squigglePopupItem;
            dismissSquigglePopup();
            if (d != null) popups.showDiagnosticPopup(d, d.start);
            return;
        }
        if (hit < m.rows) {
            int line = view.session.getDocument().lineForOffset(
                    view.squigglePopupItem.start);
            List<EditorView.CodeAction> actions = view.codeActionsByLine.get(line);
            if (actions != null && hit < actions.size()) {
                EditorView.CodeAction a = actions.get(hit);
                dismissSquigglePopup();
                if (a.apply != null) {
                    a.apply.run();
                    view.onTextChanged();
                }
            }
        }
    }

    /** Referme le popup ancré (l'état de plage armée part avec lui). */
    void dismissSquigglePopup() {
        view.squigglePopupVisible = false;
        view.squigglePopupItem = null;
        view.squigglePopupChoices = new ArrayList<>();
        view.invalidate();
    }
}

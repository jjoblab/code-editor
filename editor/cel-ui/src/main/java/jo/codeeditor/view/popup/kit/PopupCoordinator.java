package jo.codeeditor.view.popup.kit;

import java.util.EnumSet;

/**
 * Coordinateur des popups de l'éditeur (phase 5) : REGISTRE UNIQUE des
 * popups ouverts + règles d'exclusion — remplace les listes de
 * {@code closeOthers()} dupliquées classe par classe.
 *
 * <p>Règle d'exclusion : les popups de FRAPPE (complétion et aide de
 * signature) COEXISTENT — c'est leur placement coordonné
 * ({@link PopupPlacement}) qui les sépare ; tout AUTRE popup (tap :
 * doc rapide, code actions, nav menu, squiggle, sheets de
 * diagnostics, sélecteur de couleur, aller-au-symbole) referme tous
 * les popups ouverts, lui excepté.</p>
 *
 * <p>Le {@link DismissSink} exécute les fermetures décidées par le
 * coordinateur (l'appelant y branche ses méthodes de dismiss — elles
 * sont idempotentes). Classe pure : testable sans Android.</p>
 */
public final class PopupCoordinator {

    /** Les popups coordonnés. */
    public enum Kind {
        COMPLETION, SIGNATURE, QUICK_DOC, CODE_ACTIONS, NAV_MENU,
        COLOR_PICKER, DIAGNOSTIC_POPUP, DIAGNOSTIC_LIST, SQUIGGLE,
        GO_TO_SYMBOL
    }

    /** Exécute la fermeture d'un popup décidée par le coordinateur. */
    public interface DismissSink {
        void dismiss(Kind kind);
    }

    private final EnumSet<Kind> ouverts = EnumSet.noneOf(Kind.class);
    private final DismissSink sink;

    public PopupCoordinator(DismissSink sink) {
        this.sink = sink;
    }

    /**
     * Un popup s'ouvre : les popups exclus sont refermés (via le
     * sink), le popup s'enregistre. Les popups de frappe coexistent
     * entre eux ; tout autre popup referme tout le reste.
     */
    public synchronized void onShown(Kind kind) {
        for (Kind k : EnumSet.copyOf(ouverts)) {
            if (k == kind) continue;
            if (estDeFrappe(kind) && estDeFrappe(k)) continue;
            ouverts.remove(k);
            sink.dismiss(k);
        }
        ouverts.add(kind);
    }

    /** Un popup se referme : il sort du registre. */
    public synchronized void onDismissed(Kind kind) {
        ouverts.remove(kind);
    }

    /** Le popup est-il enregistré comme ouvert ? */
    public synchronized boolean isShown(Kind kind) {
        return ouverts.contains(kind);
    }

    /** Les popups de frappe coexistent (complétion + signature). */
    private static boolean estDeFrappe(Kind kind) {
        return kind == Kind.COMPLETION || kind == Kind.SIGNATURE;
    }
}

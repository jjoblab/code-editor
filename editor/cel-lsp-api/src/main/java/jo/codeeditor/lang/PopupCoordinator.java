package jo.codeeditor.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordonne l'ordre z (z-order) et la fermeture des popups de l'éditeur
 * (complétion, aide de signature, hover, actions de code, go-to-symbol).
 *
 * <p>Chaque popup porte une priorité : l'ouverture d'un popup de priorité
 * supérieure referme tous les popups de priorité inférieure — deux popups
 * ne se recouvrent donc jamais.</p>
 *
 * <p>Le {@link jo.codeeditor.view.EditorView} possède une instance unique,
 * exposée à l'hôte via {@code getPopupCoordinator()} ; les méthodes
 * {@link #onPopupShown} / {@link #onPopupDismissed} sont à appeler à chaque
 * changement de visibilité d'un popup.</p>
 */
public final class PopupCoordinator {

    /** Niveaux de priorité des popups (plus élevé = au-dessus). */
    public static final int PRIORITY_COMPLETION = 10;
    public static final int PRIORITY_SIGNATURE_HELP = 20;
    public static final int PRIORITY_HOVER = 30;
    public static final int PRIORITY_CODE_ACTIONS = 40;
    public static final int PRIORITY_GO_TO_SYMBOL = 50;
    public static final int PRIORITY_GO_TO_LINE = 60;
    public static final int PRIORITY_RENAME = 70;

    /** Listener notifié quand un popup est refermé de force par un popup plus prioritaire. */
    public interface DismissListener {
        void onDismissPopup(int popupType);
    }

    private final List<PopupEntry> activePopups = new ArrayList<>();
    private DismissListener dismissListener;

    /**
     * Appelée quand un popup s'ouvre. Referme tous les popups actifs de
     * priorité inférieure.
     *
     * @param popupType l'identifiant du type de popup (utiliser les constantes PRIORITY_*)
     * @param priority  la priorité du popup
     */
    public void onPopupShown(int popupType, int priority) {
        // Referme les popups de priorité inférieure.
        List<PopupEntry> toDismiss = new ArrayList<>();
        for (PopupEntry entry : activePopups) {
            if (entry.priority < priority) {
                toDismiss.add(entry);
            }
        }
        for (PopupEntry entry : toDismiss) {
            activePopups.remove(entry);
            if (dismissListener != null) {
                dismissListener.onDismissPopup(entry.popupType);
            }
        }
        // Ajoute le nouveau popup (ou met à jour sa priorité s'il est déjà présent).
        boolean found = false;
        for (PopupEntry entry : activePopups) {
            if (entry.popupType == popupType) {
                entry.priority = priority;
                found = true;
                break;
            }
        }
        if (!found) {
            activePopups.add(new PopupEntry(popupType, priority));
        }
    }

    /** Appelée quand un popup est fermé (par l'utilisateur ou programmatiquement). */
    public void onPopupDismissed(int popupType) {
        activePopups.removeIf(entry -> entry.popupType == popupType);
    }

    /** Retourne vrai si au moins un popup est actuellement actif. */
    public boolean hasActivePopups() {
        return !activePopups.isEmpty();
    }

    /** Retourne vrai si un popup du type donné est actif. */
    public boolean isPopupActive(int popupType) {
        for (PopupEntry entry : activePopups) {
            if (entry.popupType == popupType) return true;
        }
        return false;
    }

    /** Referme tous les popups actifs. */
    public void dismissAll() {
        List<PopupEntry> copy = new ArrayList<>(activePopups);
        activePopups.clear();
        if (dismissListener != null) {
            for (PopupEntry entry : copy) {
                dismissListener.onDismissPopup(entry.popupType);
            }
        }
    }

    public void setDismissListener(DismissListener listener) {
        this.dismissListener = listener;
    }

    private static final class PopupEntry {
        final int popupType;
        int priority;

        PopupEntry(int popupType, int priority) {
            this.popupType = popupType;
            this.priority = priority;
        }
    }
}

package jo.codeeditor.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the z-order and dismissal of editor popups (completion,
 * signature help, hover, code actions, go-to-symbol).
 *
 * <p>Each popup has a priority — opening a popup with higher priority
 * dismisses all popups with lower priority. This prevents the overlapping
 * popups bug that Sora Editor has (issue #725).
 *
 * <p>The {@link jo.codeeditor.view.EditorView} owns a single instance
 * and calls {@link #onPopupShown} / {@link #onPopupDismissed} whenever
 * a popup's visibility changes.
 *
 * @since v2.0.0
 */
public final class PopupCoordinator {

    /** Popup priority levels (higher = on top). */
    public static final int PRIORITY_COMPLETION = 10;
    public static final int PRIORITY_SIGNATURE_HELP = 20;
    public static final int PRIORITY_HOVER = 30;
    public static final int PRIORITY_CODE_ACTIONS = 40;
    public static final int PRIORITY_GO_TO_SYMBOL = 50;
    public static final int PRIORITY_GO_TO_LINE = 60;
    public static final int PRIORITY_RENAME = 70;

    /** Listener notified when a popup is force-dismissed by a higher-priority one. */
    public interface DismissListener {
        void onDismissPopup(int popupType);
    }

    private final List<PopupEntry> activePopups = new ArrayList<>();
    private DismissListener dismissListener;

    /**
     * Called when a popup is shown. Dismisses all active popups with lower
     * priority.
     *
     * @param popupType the popup type identifier (use the PRIORITY_* constants)
     * @param priority  the popup priority
     */
    public void onPopupShown(int popupType, int priority) {
        // Dismiss lower-priority popups.
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
        // Add the new popup (or update if already present).
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

    /** Called when a popup is dismissed (by the user or programmatically). */
    public void onPopupDismissed(int popupType) {
        activePopups.removeIf(entry -> entry.popupType == popupType);
    }

    /** Returns true if any popup is currently active. */
    public boolean hasActivePopups() {
        return !activePopups.isEmpty();
    }

    /** Returns true if a popup of the given type is active. */
    public boolean isPopupActive(int popupType) {
        for (PopupEntry entry : activePopups) {
            if (entry.popupType == popupType) return true;
        }
        return false;
    }

    /** Dismisses all active popups. */
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

package jo.codeeditor.lang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PopupCoordinator}.
 *
 * @since v2.0.0
 */
class PopupCoordinatorTest {

    @Test
    void onPopupShown_addsToActiveList() {
        PopupCoordinator pc = new PopupCoordinator();
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        assertTrue(pc.isPopupActive(PopupCoordinator.PRIORITY_COMPLETION));
        assertTrue(pc.hasActivePopups());
    }

    @Test
    void onPopupDismissed_removesFromActiveList() {
        PopupCoordinator pc = new PopupCoordinator();
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        pc.onPopupDismissed(PopupCoordinator.PRIORITY_COMPLETION);
        assertFalse(pc.isPopupActive(PopupCoordinator.PRIORITY_COMPLETION));
        assertFalse(pc.hasActivePopups());
    }

    @Test
    void higherPriority_dismissesLowerPriorityPopups() {
        PopupCoordinator pc = new PopupCoordinator();
        List<Integer> dismissed = new ArrayList<>();
        pc.setDismissListener(dismissed::add);
        // Show completion (priority 10).
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        // Show hover (priority 30) — should dismiss completion.
        pc.onPopupShown(PopupCoordinator.PRIORITY_HOVER, PopupCoordinator.PRIORITY_HOVER);
        assertFalse(pc.isPopupActive(PopupCoordinator.PRIORITY_COMPLETION));
        assertTrue(pc.isPopupActive(PopupCoordinator.PRIORITY_HOVER));
        assertEquals(1, dismissed.size());
        assertEquals(PopupCoordinator.PRIORITY_COMPLETION, dismissed.get(0));
    }

    @Test
    void lowerPriority_doesNotDismissHigherPriority() {
        PopupCoordinator pc = new PopupCoordinator();
        List<Integer> dismissed = new ArrayList<>();
        pc.setDismissListener(dismissed::add);
        // Show hover (priority 30).
        pc.onPopupShown(PopupCoordinator.PRIORITY_HOVER, PopupCoordinator.PRIORITY_HOVER);
        // Show completion (priority 10) — should NOT dismiss hover.
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        assertTrue(pc.isPopupActive(PopupCoordinator.PRIORITY_HOVER));
        assertTrue(pc.isPopupActive(PopupCoordinator.PRIORITY_COMPLETION));
        assertTrue(dismissed.isEmpty());
    }

    @Test
    void samePopupType_updatesPriority() {
        PopupCoordinator pc = new PopupCoordinator();
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        // Only one entry, not two.
        assertEquals(1, pc.hasActivePopups() ? 1 : 0);
    }

    @Test
    void dismissAll_clearsEverything() {
        PopupCoordinator pc = new PopupCoordinator();
        List<Integer> dismissed = new ArrayList<>();
        pc.setDismissListener(dismissed::add);
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        pc.onPopupShown(PopupCoordinator.PRIORITY_HOVER, PopupCoordinator.PRIORITY_HOVER);
        pc.dismissAll();
        assertFalse(pc.hasActivePopups());
        assertEquals(2, dismissed.size());
    }

    @Test
    void fullPriorityOrder() {
        // Verify the priority chain: completion < signature < hover < codeActions < goToSymbol < goToLine < rename
        PopupCoordinator pc = new PopupCoordinator();
        List<Integer> dismissed = new ArrayList<>();
        pc.setDismissListener(dismissed::add);
        // Show them in ascending priority order — each should dismiss all previous.
        pc.onPopupShown(PopupCoordinator.PRIORITY_COMPLETION, PopupCoordinator.PRIORITY_COMPLETION);
        pc.onPopupShown(PopupCoordinator.PRIORITY_SIGNATURE_HELP, PopupCoordinator.PRIORITY_SIGNATURE_HELP);
        pc.onPopupShown(PopupCoordinator.PRIORITY_HOVER, PopupCoordinator.PRIORITY_HOVER);
        pc.onPopupShown(PopupCoordinator.PRIORITY_CODE_ACTIONS, PopupCoordinator.PRIORITY_CODE_ACTIONS);
        pc.onPopupShown(PopupCoordinator.PRIORITY_GO_TO_SYMBOL, PopupCoordinator.PRIORITY_GO_TO_SYMBOL);
        pc.onPopupShown(PopupCoordinator.PRIORITY_GO_TO_LINE, PopupCoordinator.PRIORITY_GO_TO_LINE);
        pc.onPopupShown(PopupCoordinator.PRIORITY_RENAME, PopupCoordinator.PRIORITY_RENAME);
        // Only rename should be active.
        assertTrue(pc.isPopupActive(PopupCoordinator.PRIORITY_RENAME));
        assertFalse(pc.isPopupActive(PopupCoordinator.PRIORITY_COMPLETION));
        assertFalse(pc.isPopupActive(PopupCoordinator.PRIORITY_HOVER));
        // 6 popups dismissed (all except the last).
        assertEquals(6, dismissed.size());
    }
}

package jo.codeeditor.view.popup.kit;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Coordinateur des popups (phase 5) : registre unique + règles
 * d'exclusion — les popups de FRAPPE coexistent (complétion +
 * signature), tout popup de TAP referme le reste.
 */
public class PopupCoordinatorTest {

    /** Sink collectant les fermetures décidées par le coordinateur. */
    private static final class Sink implements PopupCoordinator.DismissSink {
        final List<PopupCoordinator.Kind> fermés = new ArrayList<>();

        @Override
        public void dismiss(PopupCoordinator.Kind kind) {
            fermés.add(kind);
        }
    }

    @Test
    public void frappe_complétionEtSignatureCoexistent() {
        // Le cœur de la phase 5 : pendant la frappe d'un appel, les
        // DEUX popups vivent ensemble (leur placement coordonné les
        // sépare) — l'ouverture de l'un ne referme plus l'autre.
        Sink sink = new Sink();
        PopupCoordinator c = new PopupCoordinator(sink);
        c.onShown(PopupCoordinator.Kind.COMPLETION);
        c.onShown(PopupCoordinator.Kind.SIGNATURE);
        assertTrue(c.isShown(PopupCoordinator.Kind.COMPLETION));
        assertTrue(c.isShown(PopupCoordinator.Kind.SIGNATURE));
        assertTrue("aucune fermeture croisée entre popups de frappe",
                sink.fermés.isEmpty());
    }

    @Test
    public void frappePuisTap_leTapRefermeLaFrappe() {
        // Un popup de TAP (squiggle, doc rapide…) referme les popups
        // de frappe ouverts — l'utilisateur a quitté la frappe.
        Sink sink = new Sink();
        PopupCoordinator c = new PopupCoordinator(sink);
        c.onShown(PopupCoordinator.Kind.COMPLETION);
        c.onShown(PopupCoordinator.Kind.SIGNATURE);
        c.onShown(PopupCoordinator.Kind.SQUIGGLE);
        assertFalse(c.isShown(PopupCoordinator.Kind.COMPLETION));
        assertFalse(c.isShown(PopupCoordinator.Kind.SIGNATURE));
        assertTrue(c.isShown(PopupCoordinator.Kind.SQUIGGLE));
        assertEquals(java.util.Arrays.asList(
                PopupCoordinator.Kind.COMPLETION,
                PopupCoordinator.Kind.SIGNATURE), sink.fermés);
    }

    @Test
    public void tapPuisTap_lancienTapCède() {
        // Deux popups de TAP ne coexistent pas : le nouveau referme
        // l'ancien (le registre remplace les closeOthers() dupliqués).
        Sink sink = new Sink();
        PopupCoordinator c = new PopupCoordinator(sink);
        c.onShown(PopupCoordinator.Kind.QUICK_DOC);
        c.onShown(PopupCoordinator.Kind.NAV_MENU);
        assertFalse(c.isShown(PopupCoordinator.Kind.QUICK_DOC));
        assertTrue(c.isShown(PopupCoordinator.Kind.NAV_MENU));
        assertEquals(1, sink.fermés.size());
    }

    @Test
    public void dismiss_lePopupSortDuRegistre() {
        Sink sink = new Sink();
        PopupCoordinator c = new PopupCoordinator(sink);
        c.onShown(PopupCoordinator.Kind.COMPLETION);
        c.onDismissed(PopupCoordinator.Kind.COMPLETION);
        assertFalse(c.isShown(PopupCoordinator.Kind.COMPLETION));
        // Un tap ensuite ne déclenche plus sa fermeture.
        c.onShown(PopupCoordinator.Kind.SQUIGGLE);
        assertTrue("la complétion déjà fermée n'est pas re-fermée",
                sink.fermés.isEmpty());
    }

    @Test
    public void réouverture_duMêmeKind_neRefermeRien() {
        Sink sink = new Sink();
        PopupCoordinator c = new PopupCoordinator(sink);
        c.onShown(PopupCoordinator.Kind.COMPLETION);
        c.onShown(PopupCoordinator.Kind.COMPLETION);
        c.onShown(PopupCoordinator.Kind.SIGNATURE);
        c.onShown(PopupCoordinator.Kind.SIGNATURE);
        assertTrue("les deux frappes coexistent toujours",
                c.isShown(PopupCoordinator.Kind.COMPLETION)
                        && c.isShown(PopupCoordinator.Kind.SIGNATURE));
        assertTrue(sink.fermés.isEmpty());
    }
}

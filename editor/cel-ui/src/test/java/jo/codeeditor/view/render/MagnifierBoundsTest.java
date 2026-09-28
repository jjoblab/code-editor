package jo.codeeditor.view.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Bornage de la bulle de loupe : la bulle ne doit jamais être coupée par
 * un bord de la vue, quel que soit le bord approché par le doigt.
 */
public class MagnifierBoundsTest {

    private static final float R = 60f;

    @Test
    public void cx_isClampedInsideTheView() {
        // Doigt tout à gauche → la bulle reste dans la vue.
        assertEquals(R, EditorChromePainter.clampMagnifierCx(5f, 1080f, R), 0.01f);
        // Doigt tout à droite → idem.
        assertEquals(1080f - R, EditorChromePainter.clampMagnifierCx(1075f, 1080f, R), 0.01f);
        // Doigt au centre → pas de déplacement.
        assertEquals(540f, EditorChromePainter.clampMagnifierCx(540f, 1080f, R), 0.01f);
    }

    @Test
    public void cx_viewNarrowerThanBubble_isCentered() {
        assertEquals(50f, EditorChromePainter.clampMagnifierCx(10f, 100f, R), 0.01f);
    }

    @Test
    public void cy_prefersAboveTheFinger() {
        float y = EditorChromePainter.resolveMagnifierCy(400f, 1920f, R);
        assertEquals(400f - R * 1.8f, y, 0.01f);
        assertTrue("la bulle doit être entièrement dans la vue", y - R >= 0f);
    }

    @Test
    public void cy_fingerTooHigh_fallsBelowTheFinger() {
        // Doigt à y=50 : au-dessus, il n'y a pas la place (il faudrait
        // 50 - 108 = -58 < R) → la bulle bascule SOUS le doigt.
        float y = EditorChromePainter.resolveMagnifierCy(50f, 1920f, R);
        assertEquals(50f + R * 1.8f, y, 0.01f);
        assertTrue("la bulle doit rester dans la vue", y + R <= 1920f);
    }

    @Test
    public void cy_tinyView_staysCenteredAndInside() {
        // Vue plus petite que la bulle : centre de la vue (le clip ne
        // montrera qu'une partie, mais jamais une bulle décentrée).
        float y = EditorChromePainter.resolveMagnifierCy(30f, 100f, R);
        assertEquals(50f, y, 0.01f);
    }
}

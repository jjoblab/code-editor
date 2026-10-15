package jo.codeeditor.view.state;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests JVM purs de l'état de défilement (plan 01, étape 5) — zéro
 * vue, zéro Robolectric : l'invariant « offsets dans [0, max] » et
 * les chemins de restauration sont possédés et vérifiés ici.
 */
public class ScrollStateTest {

    @Test
    public void reset_remetALOrigine() {
        ScrollState state = new ScrollState();
        state.vOffset = 512f;
        state.hOffset = 34f;
        state.reset();
        assertEquals(0f, state.vOffset, 0f);
        assertEquals(0f, state.hOffset, 0f);
    }

    @Test
    public void clampTo_borneLesDeuxAxes() {
        ScrollState state = new ScrollState();
        state.vOffset = 2000f;
        state.hOffset = 50f;
        state.clampTo(1800f, 30f);
        assertEquals(1800f, state.vOffset, 0f);
        assertEquals(30f, state.hOffset, 0f);
    }

    @Test
    public void clampTo_negatifsRamenesAZero() {
        ScrollState state = new ScrollState();
        state.vOffset = -25f;
        state.hOffset = -1f;
        state.clampTo(1000f, 100f);
        assertEquals(0f, state.vOffset, 0f);
        assertEquals(0f, state.hOffset, 0f);
    }

    @Test
    public void restore_posePuisBorne() {
        ScrollState state = new ScrollState();
        state.restore(150f, 42f, 100f, 40f);
        assertEquals(100f, state.vOffset, 0f);
        assertEquals(40f, state.hOffset, 0f);
        state.restore(50f, 10f, 100f, 40f);
        assertEquals(50f, state.vOffset, 0f);
        assertEquals(10f, state.hOffset, 0f);
    }

    @Test
    public void restoreUnclamped_gardeLesValeursBrutes() {
        ScrollState state = new ScrollState();
        // Cas « aucune session posée » : maxV()=maxH()=0 écraserait tout.
        state.restoreUnclamped(730f, 12f);
        assertEquals(730f, state.vOffset, 0f);
        assertEquals(12f, state.hOffset, 0f);
    }
}

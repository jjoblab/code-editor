package jo.codeeditor.view.chrome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.MotionEvent;

import jo.codeeditor.view.chrome.SymbolBarView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M-03 — contrat de la barre de symboles : l'API publique déclarée
 * (FEATURES) n'avait AUCUN test. Ce contrat couvre la construction
 * (orientation, touches épinglées), la pose de l'écouteur et le
 * cheminement du toucher vers l'écouteur d'action.
 */
@RunWith(RobolectricTestRunner.class)
public class SymbolBarViewContractTest {

    private SymbolBarView nouvelleBarre() {
        Context ctx = RuntimeEnvironment.getApplication();
        SymbolBarView barre = new SymbolBarView(ctx);
        barre.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(96, android.view.View.MeasureSpec.AT_MOST));
        barre.layout(0, 0, 1080, 96);
        return barre;
    }

    @Test
    public void construction_barreHorizontaleAvecTouches() {
        SymbolBarView barre = nouvelleBarre();
        assertEquals(android.widget.LinearLayout.HORIZONTAL, barre.getOrientation());
        assertTrue("la barre a des touches enfants",
                barre.getChildCount() > 5 + 1 + 10);
        assertTrue(barre.getHeight() > 0);
    }

    @Test
    public void setOnSymbolTap_toucherPremièreTouche_actionÉpingléeReçue() {
        SymbolBarView barre = nouvelleBarre();
        final StringBuilder reçu = new StringBuilder();
        barre.setOnSymbolTap(new SymbolBarView.OnSymbolTap() {
            @Override
            public void onSymbol(String symbol) {
                reçu.append("sym:").append(symbol);
            }

            @Override
            public void onAction(String actionId) {
                reçu.append("act:").append(actionId);
            }
        });

        // Taper la première touche épinglée (« Tab » → action « tab ») :
        // DOWN puis UP au début de la barre, par la voie de distribution
        // (les touches enfants consomment le toucher).
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 30f, 24f, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, 30f, 24f, 0);
        boolean consommé = barre.dispatchTouchEvent(down);
        boolean relâché = barre.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();

        assertTrue("le toucher de la touche est consommé", consommé);
        assertTrue("le relâchement est consommé", relâché);
        assertEquals("l'écouteur d'action reçoit « tab » (première touche épinglée)",
                "act:tab", reçu.toString());
    }

    @Test
    public void sansÉcouteur_leToucherNePlantePas() {
        SymbolBarView barre = nouvelleBarre();
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 30f, 24f, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, 30f, 24f, 0);
        barre.dispatchTouchEvent(down);
        barre.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        // Aucune exception : le contrat sans écouteur est sûr.
    }
}

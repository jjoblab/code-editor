package jo.codeeditor.view.render;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * La loupe ne doit JAMAIS muter le textPaint partagé du rendu principal :
 * elle s'exécute en fin de pipeline, après toutes les couches qui mutent
 * ce paint (spans sémantiques, inlays, chrome). Toute écriture (typeface,
 * taille, couleur) peut fuir vers la frame suivante.
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierPaintIsolationTest {

    @Test
    public void drawMagnifier_neverTouchesSharedTextPaint() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(
                "public class Main {\n"
                        + "    void run() {\n"
                        + "        greet(name);\n"
                        + "    }\n"
                        + "}\n")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        view.magnifierActive = true;
        view.magnifierX = 300f;
        view.magnifierY = 200f;

        // État « exotique » du paint partagé : si drawMagnifier l'écrase,
        // on le voit immédiatement (typeface/taille/couleur).
        final int colorBefore = 0xFF654321;
        final float sizeBefore = 7f;
        final Typeface faceBefore = Typeface.SERIF;
        view.textPaint.setColor(colorBefore);
        view.textPaint.setTextSize(sizeBefore);
        view.textPaint.setTypeface(faceBefore);

        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        try {
            EditorTextPainter text = new EditorTextPainter(view);
            EditorChromePainter chrome = new EditorChromePainter(view, text);
            chrome.drawMagnifier(canvas);
        } finally {
            bmp.recycle();
        }

        assertEquals("la loupe ne doit pas écrire la couleur du paint partagé",
                colorBefore, view.textPaint.getColor());
        assertEquals("la loupe ne doit pas écrire la taille du paint partagé",
                sizeBefore, view.textPaint.getTextSize(), 0.01f);
        assertSame("la loupe ne doit pas écrire le typeface du paint partagé",
                faceBefore, view.textPaint.getTypeface());
    }
}

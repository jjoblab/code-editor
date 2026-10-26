package jo.codeeditor.view.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.List;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Le paint de texte est PARTAGÉ par tout le pipeline de rendu — le chemin
 * sans ligatures de {@code drawStyledLine} pose un typeface italique/gras
 * par span : un dernier span COMMENT (italique) ou KEYWORD (gras) fuiait
 * son typeface vers les couches qui suivent (overlay sémantique, sticky
 * lines) — la ligne suivante rendait en italique fantôme.
 */
@RunWith(RobolectricTestRunner.class)
public class EditorStyledLineTypefaceLeakTest {

    @Test
    public void finDeLigneComment_neFuitPasLItaliqueVersLePaintPartagé() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("// note")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        assertFalse("précondition : chemin sans ligatures (défaut)", view.fontLigatures);

        EditorTextPainter painter = new EditorTextPainter(view);
        StyledLine styled = new StyledLine(
                List.of(new LineSpan(0, 7, TokenType.COMMENT)), 0, 0);
        Bitmap bmp = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Typeface avant = view.textPaint.getTypeface();
        painter.drawStyledLine(canvas, styled, "// note",
                0f, 0f, view.textPaint, Collections.emptyList(), null);
        assertEquals("le typeface du paint partagé est restauré en fin de ligne",
                avant, view.textPaint.getTypeface());
    }

    @Test
    public void finDeLigneKeyword_neFuitPasLeGrasVersLePaintPartagé() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("return")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        EditorTextPainter painter = new EditorTextPainter(view);
        StyledLine styled = new StyledLine(
                List.of(new LineSpan(0, 6, TokenType.KEYWORD)), 0, 0);
        Bitmap bmp = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Typeface avant = view.textPaint.getTypeface();
        painter.drawStyledLine(canvas, styled, "return",
                0f, 0f, view.textPaint, Collections.emptyList(), null);
        assertEquals("le typeface du paint partagé est restauré en fin de ligne",
                avant, view.textPaint.getTypeface());
    }
}

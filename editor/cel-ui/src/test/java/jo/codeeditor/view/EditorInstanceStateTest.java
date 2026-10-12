package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Parcel;
import android.os.Parcelable;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B17 — sauvegarde/restauration de l'état visuel de l'éditeur
 * (scroll, zoom, wrap) : sans elle, une rotation réinitialisait le
 * défilement au sommet, le zoom à ×1 et le wrap à la valeur par défaut.
 *
 * <p>Note d'environnement : sous Robolectric, {@code setFontScale}
 * recalcule lineHeight via les métriques réelles de la police (nulles
 * dans les shadows) — le scénario zoom vérifie donc la restauration du
 * zoom et du wrap, pas le scroll (dont l'invariant de bornage reste
 * vérifié). Le scénario scroll (sans zoom) teste la restauration du
 * défilement avec des métriques injectées stables.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorInstanceStateTest {

    private static final StringBuilder DOC_BUILDER = new StringBuilder();
    static {
        // 800 lignes : assez long pour défiler avec les métriques de test.
        for (int i = 0; i < 800; i++) {
            DOC_BUILDER.append("ligne ").append(i)
                    .append(" — contenu de remplissage pour le scroll\n");
        }
    }
    private static final String DOC = DOC_BUILDER.toString();

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int W = 1080;
    private static final int H = 1920;

    private EditorView newView(EditorSession session) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        if (session != null) view.setSession(session);
        view.measure(W, H);
        view.layout(0, 0, W, H);
        injectMetrics(view, LINE_H, CHAR_W);
        view.rebuildWrapModel();
        Bitmap warm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(warm));
        warm.recycle();
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /** Aller-retour Parcel complet (comme le ferait le système). */
    private static EditorView.SavedState roundtrip(EditorView.SavedState state)
            throws Exception {
        Parcel parcel = Parcel.obtain();
        try {
            state.writeToParcel(parcel, 0);
            byte[] bytes = parcel.marshall();
            Parcel in = Parcel.obtain();
            try {
                in.unmarshall(bytes, 0, bytes.length);
                in.setDataPosition(0);
                return EditorView.SavedState.CREATOR.createFromParcel(in);
            } finally {
                in.recycle();
            }
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void save_restore_keepsScrollAndWrap() throws Exception {
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        EditorView view = newView(session);

        // État à sauvegarder : wrap + scroll (PAS de zoom — voir la note
        // d'environnement ; setWordWrap(true) remet hOffset à 0, le dernier
        // écrit est celui capturé).
        view.setWordWrap(true);
        view.vOffset = 300f;

        EditorView.SavedState saved = (EditorView.SavedState) view.onSaveInstanceState();
        assertEquals("pré-condition : l'offset sauvegardé n'est pas écrasé",
                300f, saved.vOffset, 0.001f);
        EditorView.SavedState restored = roundtrip(saved);
        assertEquals("l'offset survit au aller-retour Parcel",
                300f, restored.vOffset, 0.001f);
        assertTrue("le wrap survit au aller-retour Parcel", restored.wordWrap);

        // Nouvelle vue (recreation) + même session → restauration.
        EditorView view2 = newView(session);
        view2.onRestoreInstanceState(restored);

        assertTrue("le wrap doit être restauré", view2.wordWrap);
        assertTrue("le scroll vertical doit être restauré (borné au contenu)"
                        + " — vOffset=" + view2.vOffset,
                view2.vOffset > 0 && view2.vOffset <= view2.maxV() + 0.5f);
    }

    @Test
    public void save_restore_keepsZoom() throws Exception {
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        EditorView view = newView(session);
        view.setFontScale(1.5f);

        EditorView.SavedState saved = (EditorView.SavedState) view.onSaveInstanceState();
        assertEquals("pré-condition : le zoom sauvegardé",
                1.5f, saved.fontScale, 0.001f);
        EditorView.SavedState restored = roundtrip(saved);

        EditorView view2 = newView(session);
        view2.onRestoreInstanceState(restored);
        assertEquals("le zoom doit être restauré", 1.5f, view2.zoom.fontScale, 0.001f);
        assertTrue("l'offset restauré reste borné [0, maxV] (invariant)"
                        + " — vOffset=" + view2.vOffset + " maxV=" + view2.maxV(),
                view2.vOffset >= 0 && view2.vOffset <= view2.maxV() + 0.5f);
    }

    @Test
    public void restore_withoutRealSession_keepsRawOffsets() throws Exception {
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        EditorView view = newView(session);
        view.vOffset = 500f;

        EditorView.SavedState saved = (EditorView.SavedState) view.onSaveInstanceState();
        assertEquals("pré-condition : capture de l'offset",
                500f, saved.vOffset, 0.001f);
        EditorView.SavedState restored = roundtrip(saved);

        // Vue dont la session a été détachée (setSession(null), B21e) :
        // pas de géométrie fiable → offsets conservés BRUTS pour que
        // l'hôte puisse poser sa session après la restauration sans
        // perdre la position.
        EditorView view2 = newView(null);
        view2.setSession(null);
        view2.onRestoreInstanceState(restored);
        assertEquals("offsets bruts conservés sans session",
                500f, view2.vOffset, 0.001f);
    }

    @Test
    public void restore_withForeignState_fallsBackToSuper() {
        EditorView view = newView(null);
        // État étranger (BaseSavedState générique) — pas de crash.
        android.view.View.BaseSavedState foreign =
                new android.view.View.BaseSavedState(android.os.Bundle.EMPTY);
        view.onRestoreInstanceState(foreign);
        assertEquals("état étranger → aucun changement de wrap",
                false, view.wordWrap);
    }
}

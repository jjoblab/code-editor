package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.popup.EditorPopupAnchors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B26 — sélecteur de couleur au tap d'un code couleur hexadécimal.
 *
 * <ul>
 *   <li><b>Ouverture</b> — tap au CŒUR d'un code (#RGB/#RRGGBB/
 *       #AARRGGBB, pastilles inline du renderer) → carte ancrée ;
 *       les bords ±1 caractère restent des placements de caret ;</li>
 *   <li><b>Fermeture</b> — tap hors carte (le caret suit le tap),
 *       édition du document, désactivation du sélecteur ;</li>
 *   <li><b>Application</b> — teinte de la palette ou fin d'un press
 *       champ/bande : le code est REMPLACÉ au format d'origine
 *       (3/6/8 chiffres), l'alpha d'origine est conservé, la mutation
 *       passe par le pipeline unique (annulable d'un undo) ;</li>
 *   <li><b>Formatage</b> — {@code ColorPicker.format} : quantification
 *       3 chiffres, 6 chiffres, 8 chiffres.</li>
 * </ul>
 *
 * <p>NOTE Robolectric : métriques déterministes injectées (pattern
 * {@code TestMetrics}) ; la géométrie de la carte vient de
 * {@code EditorPopupAnchors.colorPickerMetrics} (densité 1).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ColorPickerTapTest {

    private static final String DOC =
        "colors {\n"                    // 0
        + "  bg: #fff;\n"               // 1
        + "  accent: #4CAF50;\n"         // 2
        + "  veil: #80FF0000;\n"         // 3
        + "}\n";                         // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    private static void tap(EditorView view, float x, float y) {
        long t0 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t0, t0, x, y);
        send(view, MotionEvent.ACTION_UP, t0, t0 + 40, x, y);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void send(EditorView view, int action, long down, long when,
            float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    /** Position écran visant le quart gauche du caractère à l'offset donné. */
    private static float[] midCharPos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        return new float[]{pos[0] + CHAR_W * 0.25f, pos[1] + LINE_H / 2f};
    }

    /** Ouvre le sélecteur sur le code « #fff » de la ligne 1. */
    private EditorView openOnFff() {
        EditorView view = newView();
        int fff = DOC.indexOf("#fff");
        float[] pos = midCharPos(view, fff + 2); // au cœur du code
        tap(view, pos[0], pos[1]);
        assertTrue("pré-condition : le sélecteur est ouvert",
                view.colorPickerVisible);
        return view;
    }

    // ════════════════════════════════════════════════════════════════
    // Ouverture / fermeture
    // ════════════════════════════════════════════════════════════════

    @Test
    public void tapAuCoeurDuCode_ouvreLeSelecteur() {
        EditorView view = newView();
        int fff = DOC.indexOf("#fff");
        float[] pos = midCharPos(view, fff + 2);
        tap(view, pos[0], pos[1]);

        assertTrue("le sélecteur est ouvert", view.colorPickerVisible);
        assertEquals("plage = le code tapé", fff, view.colorPickerStart);
        assertEquals(fff + 4, view.colorPickerEnd);
        assertEquals("format d'origine : 3 chiffres", 3, view.colorPickerDigits);
        assertEquals("couleur d'origine", 0xFFFFFFFF, view.colorPickerOriginal);
        assertEquals("couleur courante initialisée", 0xFFFFFFFF, view.colorPickerCurrent);
        // Le tap d'ouverture ne pose PAS le caret (précédent squiggle).
        assertEquals("le caret n'a pas bougé", 0,
                view.getSession().getSelection().start);
        // La géométrie est calculable (carte ancrée sous la ligne 1).
        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        assertNotNull(m);
        assertTrue("carte sous la ligne tapée", m.y >= 2 * LINE_H);
        assertTrue("carte étroite par construction", m.w <= 260f);
    }

    @Test
    public void tapSurLesBords_duCode_placeLeCaret() {
        EditorView view = newView();
        int fff = DOC.indexOf("#fff");
        // Tap juste AVANT le '#' — placement de caret, pas de popup.
        float[] before = midCharPos(view, fff);
        tap(view, before[0], before[1]);
        assertFalse("pas de sélecteur avant le code", view.colorPickerVisible);
        assertEquals("le caret est posé", fff,
                view.getSession().getSelection().start);
    }

    @Test
    public void tapHorsCarte_refermeEtPlaceLeCaret() {
        EditorView view = openOnFff();
        // Tap sur « colors » ligne 0 — hors de la carte ancrée ligne 1.
        int colors = DOC.indexOf("colors");
        float[] pos = midCharPos(view, colors + 2);
        tap(view, pos[0], pos[1]);
        assertFalse("le tap hors carte referme le sélecteur",
                view.colorPickerVisible);
        assertEquals("le caret suit le tap (cascade inchangée)",
                colors + 2, view.getSession().getSelection().start);
    }

    @Test
    public void edition_refermeLeSelecteur() {
        EditorView view = openOnFff();
        view.getSession().replaceRange(0, 0, "x");
        view.notifyTextChanged();
        assertFalse("une édition referme le sélecteur (plage périmée)",
                view.colorPickerVisible);
    }

    @Test
    public void desactive_leTapPlaceLeCaret() {
        EditorView view = newView();
        view.setColorPickerEnabled(false);
        int fff = DOC.indexOf("#fff");
        float[] pos = midCharPos(view, fff + 2);
        tap(view, pos[0], pos[1]);
        assertFalse("aucun sélecteur quand désactivé", view.colorPickerVisible);
        assertTrue("le caret est posé",
                view.getSession().getSelection().start >= fff);
    }

    // ════════════════════════════════════════════════════════════════
    // Application — palette
    // ════════════════════════════════════════════════════════════════

    @Test
    public void pickPalette_remplaceLeCode_auFormatTroisChiffres() {
        EditorView view = openOnFff();
        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        // Première teinte de la palette (rouge F44336).
        float cx = m.palette.left + m.cell * 0.5f;
        float cy = m.palette.top + m.cell * 0.5f;
        tap(view, cx, cy);

        assertFalse("le pick referme la carte", view.colorPickerVisible);
        // F44336 quantifié 3 chiffres : F4→E, 43→4, 36→3.
        assertTrue("le code est remplacé (format 3 conservé)",
                view.getSession().getText().contains("#e43"));
        assertFalse("l'ancien code a disparu",
                view.getSession().getText().contains("#fff"));
        // Annulable d'un undo (pipeline unique).
        assertTrue(view.getSession().undo());
        assertTrue("l'undo restaure le code d'origine",
                view.getSession().getText().contains("#fff"));
    }

    @Test
    public void pickPalette_conserveLAlphaDOrigine() {
        EditorView view = newView();
        int veil = DOC.indexOf("#80FF0000");
        float[] pos = midCharPos(view, veil + 4);
        tap(view, pos[0], pos[1]);
        assertTrue("sélecteur ouvert sur le code 8 chiffres",
                view.colorPickerVisible);
        assertEquals(8, view.colorPickerDigits);

        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        // 6e teinte (bleu 2196F3) de la palette.
        int idx = 5;
        float cx = m.palette.left + (idx % m.paletteCols + 0.5f) * m.cell;
        float cy = m.palette.top + (idx / m.paletteCols + 0.5f) * m.paletteRowH;
        tap(view, cx, cy);

        // L'alpha 0x80 d'origine est conservé, RGB = 2196F3.
        assertTrue("alpha conservé + RGB remplacé (8 chiffres)",
                view.getSession().getText().contains("#802196f3"));
    }

    // ════════════════════════════════════════════════════════════════
    // Application — champ SV (press + release)
    // ════════════════════════════════════════════════════════════════

    @Test
    public void pressChampSv_sautImmediatEtApplicationAuRelache() {
        EditorView view = newView();
        int accent = DOC.indexOf("#4CAF50");
        float[] pos = midCharPos(view, accent + 4);
        tap(view, pos[0], pos[1]);
        assertTrue(view.colorPickerVisible);
        assertEquals(6, view.colorPickerDigits);

        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        // Press au centre du champ : saturation 0.5, valeur 0.5.
        float cx = m.sv.centerX();
        float cy = m.sv.centerY();
        long t0 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t0, t0, cx, cy);
        assertEquals("press du champ SV", 1, view.colorPickerPressed);
        // Saut immédiat : la couleur courante = teinte d'origine, s=v=0.5.
        float[] hsv = new float[3];
        Color.colorToHSV(0xFF4CAF50, hsv);
        int attendu = 0xFF000000 | (Color.HSVToColor(
                new float[]{hsv[0], 0.5f, 0.5f}) & 0x00FFFFFF);
        assertEquals("saut immédiat au centre du champ",
                attendu, view.colorPickerCurrent);
        // Drag vivant : coin bas-gauche exact (s=0, v=0) → noir.
        send(view, MotionEvent.ACTION_MOVE, t0, t0 + 60, m.sv.left, m.sv.bottom);
        assertEquals(0xFF000000, view.colorPickerCurrent);
        // Release → application de la couleur en cours (noir, 6 chiffres).
        send(view, MotionEvent.ACTION_UP, t0, t0 + 100, m.sv.left, m.sv.bottom);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("l'application referme la carte", view.colorPickerVisible);
        assertTrue("le code est remplacé par le noir",
                view.getSession().getText().contains("#000000"));
        assertFalse("l'ancien code a disparu",
                view.getSession().getText().contains("#4CAF50"));
    }

    @Test
    public void tapDansLaCarteHorsCommandes_neFaitRien() {
        EditorView view = openOnFff();
        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        // Tap dans l'en-tête (entre les deux swatches) — geste englouti.
        tap(view, m.x + m.w * 0.5f, m.y + 12f + m.headerH * 0.5f);
        assertTrue("le sélecteur reste ouvert (geste englouti)",
                view.colorPickerVisible);
        assertTrue("le document n'a pas changé",
                view.getSession().getText().contains("#fff"));
    }

    // ════════════════════════════════════════════════════════════════
    // Formatage pur (ColorPicker.format)
    // ════════════════════════════════════════════════════════════════

    @Test
    public void format_troisChiffres_quantifieAuNibble() {
        assertEquals("#fff", ColorPicker.format(0xFFFFFFFF, 3));
        assertEquals("#e43", ColorPicker.format(0xFFF44336, 3));
        assertEquals("#000", ColorPicker.format(0xFF050505, 3));
    }

    @Test
    public void format_sixEtHuitChiffres() {
        assertEquals("#4caf50", ColorPicker.format(0xFF4CAF50, 6));
        assertEquals("#802196f3", ColorPicker.format(0x802196F3, 8));
        assertEquals("#2196f3", ColorPicker.format(0x802196F3, 42));
    }
}

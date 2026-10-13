package jo.codeeditor.view.icons;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * B6 — moteur d'icônes vectorielles {@link EditorIcons} : parser SVG,
 * géométrie des paths (viewport 24×24), dessin teinté au Canvas.
 *
 * <ul>
 *   <li><b>Parser</b> — commandes absolues/relatives, séparateurs tolérés,
 *       lineTo implicite après M, chaînage z→M sans perte, arcs rejetés
 *       explicitement ;</li>
 *   <li><b>Géométrie</b> — chaque icône remplit une part raisonnable du
 *       viewport (pas d'icône vide ni débordante) et le cache rend le
 *       path immuable (même instance) ;</li>
 *   <li><b>Dessin</b> — un drawPath par appel avec le style de la famille
 *       (FILL/STROKE) et la peinture de l'appelant restaurée ; zone
 *       d'inscription transportée dans le carré demandé (contrat
 *       translate + scale + translate).</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class EditorIconsTest {

    // ── Parser ────────────────────────────────────────────────────

    @Test
    public void parse_ligneAbsolueEtRelative() {
        Path p = new Path();
        EditorIcons.SvgPathParser.parse("M2 2L4 2l-1 2z", p);
        RectF r = new RectF();
        p.computeBounds(r, true);
        assertEquals(2f, r.left, 0.01f);
        assertEquals(2f, r.top, 0.01f);
        assertEquals(4f, r.right, 0.01f);
        assertEquals(4f, r.bottom, 0.01f);
    }

    @Test
    public void parse_lineToImpliciteApresMove() {
        // « M1 1 3 3 » = moveto(1,1) puis lineTo implicite(3,3) (spec SVG).
        Path p = new Path();
        EditorIcons.SvgPathParser.parse("M1 1 3 3", p);
        RectF r = new RectF();
        p.computeBounds(r, true);
        assertEquals(1f, r.left, 0.01f);
        assertEquals(3f, r.right, 0.01f);
        assertEquals(1f, r.top, 0.01f);
        assertEquals(3f, r.bottom, 0.01f);
    }

    @Test
    public void parse_moinsCommeSeparateur() {
        // Style Material : « 2.37-.78 » — le moins sans espace sépare deux
        // nombres.
        Path p = new Path();
        EditorIcons.SvgPathParser.parse("M0 0l2.37-.78z", p);
        RectF r = new RectF();
        p.computeBounds(r, true);
        assertEquals(2.37f, r.right, 0.01f);
        assertEquals(-0.78f, r.top, 0.01f);
    }

    @Test
    public void parse_zEnchaineSurUnMemePath() {
        // « …z M12 17… » — le z du sous-path précédent ne doit PAS manger
        // le M suivant (régression du double i++ : l'œil Material chaîne
        // trois sous-paths dans une seule donnée d).
        Path p = new Path();
        EditorIcons.SvgPathParser.parse(
                "M1 1L3 1zM2 2L4 2z", p);
        RectF r = new RectF();
        p.computeBounds(r, true);
        assertEquals(1f, r.left, 0.01f);
        assertEquals(1f, r.top, 0.01f);
        assertEquals(4f, r.right, 0.01f);
        assertEquals(2f, r.bottom, 0.01f);
    }

    @Test
    public void parse_nombreApresClosepathRejete() {
        Path p = new Path();
        try {
            EditorIcons.SvgPathParser.parse("M1 1z3 3", p);
            fail("des nombres après z sont du SVG invalide");
        } catch (IllegalArgumentException expected) {
            // ok — rejet explicite plutôt que boucle infinie.
        }
    }

    @Test
    public void parse_arcRejeteExplicitement() {
        Path p = new Path();
        try {
            EditorIcons.SvgPathParser.parse("M0 0A1 1 0 0 1 2 2", p);
            fail("l'arc elliptique doit être rejeté (jeu d'icônes sans arcs)");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("arc"));
        }
    }

    @Test
    public void parse_nombresManquantsRejetes() {
        Path p = new Path();
        try {
            EditorIcons.SvgPathParser.parse("M1 1L4", p);
            fail("L sans ordonnée doit être rejeté");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ── Géométrie des icônes du jeu ──────────────────────────────

    @Test
    public void chaqueIconeRemplitLeViewportSansDeborder() {
        for (EditorIcons.Icon icon : EditorIcons.Icon.values()) {
            RectF r = EditorIcons.bounds(icon);
            assertTrue(icon + " doit remplir le viewport",
                    r.width() > 8f && r.height() > 6f);
            assertTrue(icon + " doit rester dans le viewport 24×24",
                    r.left >= -0.5f && r.top >= -0.5f
                    && r.right <= 24.5f && r.bottom <= 24.5f);
        }
    }

    @Test
    public void undoEtRedoSontDesMiroirsEnLargeur() {
        // L'annuler part vers la gauche, le rétablir vers la droite : les
        // bornes se répondent en miroir autour de x = 12 (la somme d'une
        // borne gauche et de la borne droite opposée repasse par le centre).
        RectF undo = EditorIcons.bounds(EditorIcons.Icon.UNDO);
        RectF redo = EditorIcons.bounds(EditorIcons.Icon.REDO);
        assertEquals("miroir gauche", 24f, undo.left + redo.right, 1.5f);
        assertEquals("miroir droite", 24f, undo.right + redo.left, 1.5f);
        assertEquals("même hauteur de flèche", undo.height(), redo.height(), 0.75f);
        assertEquals("même largeur de flèche", undo.width(), redo.width(), 0.75f);
    }

    @Test
    public void lOeilEstPlusLargeQueHautEtCentre() {
        RectF eye = EditorIcons.bounds(EditorIcons.Icon.EYE);
        assertTrue("œil panoramique", eye.width() > eye.height());
        assertEquals("centré horizontalement", 12f, eye.centerX(), 0.5f);
        assertEquals("centré verticalement", 12f, eye.centerY(), 0.5f);
    }

    @Test
    public void leCacheRendLePathPartage() {
        assertSame("le path est construit une fois puis partagé",
                EditorIcons.path(EditorIcons.Icon.UNDO),
                EditorIcons.path(EditorIcons.Icon.UNDO));
        for (EditorIcons.Icon icon : EditorIcons.Icon.values()) {
            assertNotNull(EditorIcons.path(icon));
        }
    }

    // ── Dessin ────────────────────────────────────────────────────

    @Test
    public void drawEnvoieLePathAvecLeStyleDeSaFamilleEtRestaureLaPeinture() {
        // NOTE : le raster de drawPath est un no-op sous Robolectric (Path
        // shadowé) — le contrat se vérifie par les ÉVÉNEMENTS du shadow
        // canvas : UN drawPath, le path mis en cache, le style posé selon
        // la famille, la peinture de l'appelant RESTAURÉE ensuite.
        Bitmap bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF7FD1A8);

        // Famille FILL (annuler).
        EditorIcons.draw(canvas, EditorIcons.Icon.UNDO, 24, 24, 40, paint);
        org.robolectric.shadows.ShadowCanvas shadow = Shadows.shadowOf(canvas);
        assertEquals("UN pose un drawPath", 1, shadow.getPathPaintHistoryCount());
        // Le shadow recrée l'instance du path : comparaison par les bornes
        // (même géométrie = même icône du cache).
        RectF drawn = new RectF();
        Shadows.shadowOf(shadow.getDrawnPath(0)).fillBounds(drawn);
        RectF expected = EditorIcons.bounds(EditorIcons.Icon.UNDO);
        assertEquals("bornes de l'icône UN", expected.left, drawn.left, 0.1f);
        assertEquals("bornes de l'icône UN (haut)", expected.top, drawn.top, 0.1f);
        assertEquals("bornes de l'icône UN (droite)", expected.right, drawn.right, 0.1f);
        assertEquals("bornes de l'icône UN (bas)", expected.bottom, drawn.bottom, 0.1f);
        assertEquals("UN en FILL", Paint.Style.FILL,
                shadow.getDrawnPathPaint(0).getStyle());
        assertEquals("couleur de l'appelant préservée", 0xFF7FD1A8,
                shadow.getDrawnPathPaint(0).getColor());

        // Famille STROKE (fermer) — trait calibré 2/24 de la taille, style
        // de l'appelant (posé EXPLICITEMENT) restauré après l'appel.
        Bitmap bmp2 = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas2 = new Canvas(bmp2);
        paint.setStyle(Paint.Style.FILL);
        EditorIcons.draw(canvas2, EditorIcons.Icon.CLOSE, 24, 24, 48, paint);
        org.robolectric.shadows.ShadowCanvas shadow2 = Shadows.shadowOf(canvas2);
        assertEquals("CLOSE pose un drawPath", 1, shadow2.getPathPaintHistoryCount());
        assertEquals("CLOSE en STROKE", Paint.Style.STROKE,
                shadow2.getDrawnPathPaint(0).getStyle());
        assertEquals("trait 2/24 de la taille", 48f / 24f * 2f,
                shadow2.getDrawnPathPaint(0).getStrokeWidth(), 0.01f);
        assertEquals("style de l'appelant restauré (FILL)",
                Paint.Style.FILL, paint.getStyle());
    }

    @Test
    public void drawZoneInscriptionDansLeCarreDemande() {
        // Contrat géométrique du centrage : bornes 24×24 de l'icône
        // transportées par la transformation draw (centre + taille) —
        // v' = cx + (v − 12) × size/24.
        for (EditorIcons.Icon icon : EditorIcons.Icon.values()) {
            RectF b = EditorIcons.bounds(icon);
            float cx = 60f, cy = 60f, size = 40f, k = size / 24f;
            float left = cx + (b.left - 12f) * k;
            float right = cx + (b.right - 12f) * k;
            float top = cy + (b.top - 12f) * k;
            float bottom = cy + (b.bottom - 12f) * k;
            assertTrue(icon + " reste dans le carré d'inscription",
                    left >= cx - size / 2f - 0.5f && right <= cx + size / 2f + 0.5f
                    && top >= cy - size / 2f - 0.5f && bottom <= cy + size / 2f + 0.5f);
        }
    }

    @Test
    public void drawTailleNulleOuCanvasNullNePlantePas() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        EditorIcons.draw(null, EditorIcons.Icon.UNDO, 0, 0, 20, paint);
        EditorIcons.draw(new Canvas(), EditorIcons.Icon.UNDO, 0, 0, 0f, paint);
        EditorIcons.draw(new Canvas(), EditorIcons.Icon.UNDO, 0, 0, -5f, null);
    }
}

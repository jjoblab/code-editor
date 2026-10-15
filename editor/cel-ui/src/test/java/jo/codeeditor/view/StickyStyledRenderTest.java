package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
/**
 * S2 (P1) — verrouille le <b>rendu stylé des lignes collées</b>
 * (sticky lines) :
 *
 * <ul>
 *   <li><b>Coloration syntaxique</b> — la ligne collée est LA MÊME
 *       ligne, coloration comprise : ses tokens sont dessinés via le
 *       pipeline stylé ({@code drawStyledLine}, celui de la loupe),
 *       pas le {@code guidePaint} brut monochrome d'avant ;</li>
 *   <li><b>Inlays exclus</b> — une ligne collée n'est pas le lieu des
 *       hints : les inlays de la ligne ne sont pas tissés dans le
 *       rendu collé ;</li>
 *   <li><b>Ellipse « … »</b> — une ligne plus large que le viewport
 *       est tronquée avec un marqueur « … » au lieu d'une coupe
 *       muette à 120 caractères ;</li>
 *   <li><b>Fenêtré</b> — le texte collé reste borné à la fenêtre
 *       visible (cohérent avec le rendu normal des lignes).</li>
 * </ul>
 *
 * <p>Mise en scène : la ligne 0 (en-tête de classe, sticky) est
 * scrollée au-dessus du viewport — elle n'est alors rendue QUE par la
 * passe sticky : tout drawText de son contenu prouve le rendu collé.
 * Un inlay « name: » ancré sur cette ligne ne doit JAMAIS apparaître
 * (il ne serait dessiné que si la passe sticky tissait les inlays —
 * rendu normal exclu puisque la ligne est hors écran).</p>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}). Chemin sans ligatures
 * ({@code fontLigatures} est faux par défaut) : chaque span de la
 * ligne collée produit un drawText avec SA couleur — capté par un
 * canvas d'enregistrement (le {@code TextHistoryEvent.paint} de
 * ShadowCanvas garde la RÉFÉRENCE du paint muté au fil des spans : la
 * couleur y serait celle du dernier span, pas celle du draw).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class StickyStyledRenderTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Viewport 1080 px, textAreaLeft = 70 + 5 → capacité ≈ 100 colonnes. */
    private static final int VIEWPORT_COLS = 100;
    /** Fenêtre visible du painter : viewport + 2×16 de marge = 140 colonnes. */

    /** La ligne 0 : le seul « public » du document. */
    private static final String CLASS_LINE = "public class Main {";

    private static String doc() {
        StringBuilder sb = new StringBuilder();
        sb.append(CLASS_LINE).append('\n')     // 0 — sticky (classe)
          .append("    void run() {\n")        // 1 — sticky (méthode)
          .append("        int a0 = 0;\n");    // 2+
        for (int i = 1; i <= 40; i++) {
            sb.append("        int a").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    }\n").append("}\n");
        return sb.toString();
    }

    private EditorView newView(String text) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(text));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        // Les lignes 0-1 (sticky) passent au-dessus du padding haut :
        // la ligne 0 n'est plus rendue que par la passe sticky.
        view.scroll.vOffset = 8f * LINE_H;
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    /** Événement texte d'un rendu : texte dessiné + couleur du paint À
     *  L'INSTANT du draw (copie, pas référence — le paint est muté au fil
     *  des spans). */
    private static final class TextEvent {
        final String text;
        final int color;
        TextEvent(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    /** Canvas d'enregistrement : capture chaque drawText avec la
     *  couleur courante du paint. Le rendu réel n'utilise que les
     *  variantes (String), (String, start, end) et (CharSequence,
     *  start, end) — le painter passe par (String, start, end), la
     *  plus spécifique. */
    private static final class RecordingCanvas extends Canvas {
        final List<TextEvent> events = new ArrayList<>();
        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            events.add(new TextEvent(text, paint.getColor()));
            super.drawText(text, x, y, paint);
        }
        @Override
        public void drawText(String text, int start, int end,
                float x, float y, Paint paint) {
            events.add(new TextEvent(
                    text.substring(start, end), paint.getColor()));
            super.drawText(text, start, end, x, y, paint);
        }
        @Override
        public void drawText(CharSequence text, int start, int end,
                float x, float y, Paint paint) {
            events.add(new TextEvent(
                    text.subSequence(start, end).toString(), paint.getColor()));
            super.drawText(text, start, end, x, y, paint);
        }
    }

    /** Rend la vue et capture tous les drawText (texte + couleur du
     *  paint à l'instant du draw). */
    private static List<TextEvent> drawnTexts(EditorView view) {
        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);
        return canvas.events;
    }

    @Test
    public void token_de_la_ligne_collee_peint_avec_sa_couleur_de_syntaxe() {
        EditorView view = newView(doc());

        // Sanity : la ligne 0 est bien hors écran — rendue par le
        // sticky uniquement.
        assertTrue("la ligne 0 doit être scrollée au-dessus du viewport",
                view.docLineToY(0) - view.scroll.vOffset < LINE_H * 0.5f);

        List<TextEvent> events = drawnTexts(view);
        // « class » est un span KEYWORD franc (colorForToken(KEYWORD) =
        // theme.keyword) ; « public » est KEYWORD_MODIFIER (même couleur
        // par le fallback de la registry, gardé souple ici).
        int keyword = view.theme.keyword;
        boolean classEnCouleur = false;
        for (TextEvent e : events) {
            if ("class".equals(e.text) && e.color == keyword) {
                classEnCouleur = true;
            }
        }
        assertTrue("le token « class » de la ligne collée doit être peint"
                + " avec la couleur keyword du thème (drawText stylé, plus"
                + " le guidePaint monochrome d'avant S2)",
                classEnCouleur);

        // Le monochrome d'avant : même texte, couleur du texte de base.
        boolean classMonochrome = false;
        for (TextEvent e : events) {
            if ("class".equals(e.text) && e.color == view.theme.textColor) {
                classMonochrome = true;
            }
        }
        assertTrue("le rendu collé reste monochrome — S2 non appliqué",
                !classMonochrome);
    }

    @Test
    public void inlays_exclus_du_rendu_colle() {
        EditorView view = newView(doc());
        // Inlay ancré sur la ligne 0 (hors écran — seul le sticky peut
        // le tisser). Le rendu normal des inlays n'itère que les
        // lignes visibles : s'il apparaît, c'est la passe sticky.
        int anchor = doc().indexOf("class") + 5;
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(anchor, "name:", false));
        view.getSession().setInlayHints(hints);

        for (TextEvent e : drawnTexts(view)) {
            assertNotNull(e.text);
            assertTrue("l'inlay « name: » ne doit pas être tissé dans la"
                    + " ligne collée (une ligne collée n'est pas le lieu"
                    + " des hints) — trouvé dans « " + e.text + " »",
                    !e.text.contains("name:"));
        }
    }

    @Test
    public void ellipse_apparait_pour_une_ligne_plus_large_que_le_viewport() {
        // Ligne 0 sticky de 166 caractères (< 200 : détectable), plus
        // large que les ~100 colonnes visibles.
        String longLine = "public class " + repeat('x', 150) + " {";
        assertTrue(longLine.length() <= 200);
        String text = longLine + "\n    void run() {\n        int a = 1;\n    }\n}\n";
        EditorView view = newView(text);

        List<TextEvent> events = drawnTexts(view);
        boolean ellipse = false;
        int worst = 0;
        for (TextEvent e : events) {
            assertNotNull(e.text);
            if ("\u2026".equals(e.text)) ellipse = true;
            worst = Math.max(worst, e.text.length());
        }
        assertTrue("la ligne collée plus large que le viewport doit être"
                + " tronquée avec une ellipse « … » (coupe muette d'avant S2)",
                ellipse);
        assertTrue("le texte collé doit rester fenêtré (drawText de "
                + worst + " caractères)",
                worst <= VIEWPORT_COLS + 44);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}

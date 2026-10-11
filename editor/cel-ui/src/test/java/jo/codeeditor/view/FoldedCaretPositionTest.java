package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Lot A (audit v3.41.0) — caret mal placé après une chip de pli replié.
 *
 * <p>Symptôme observé sur l'appareil : un pli replié sur les lignes 6..16
 * d'un document JSON, le caret tapé juste après la chip {@code {…}} est
 * dessiné sur la <b>ligne suivante</b> (ligne 17 dans l'exemple), à la
 * bonne abscisse. Bon X, mauvais Y.</p>
 *
 * <p>Cause identifiée : {@code EditorHitMapper.caretScreenPos} calcule
 * {@code y = view.docLineToY(line) - view.vOffset} où {@code line} est
 * la ligne de FIN du pli (puisque {@code offsetAt} retourne un offset
 * sur endLine pour la zone chip/suffixe). Et {@code docLineToY} d'une
 * ligne masquée retourne la rangée visuelle qui suit le pli.</p>
 *
 * <p>Spécification attendue : le caret placé juste après une chip repliée
 * est dessiné à droite de la chip, sur la rangée de la ligne de DÉBUT du
 * pli, jamais sur la rangée suivante.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class FoldedCaretPositionTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;

    /**
     * Document JSON similaire à la capture : racine à la ligne 0,
     * « parameters » à la ligne 6 (objet imbriqué), pli sur les
     * lignes 7..16, ligne 17 = fermant de la racine.
     */
    private static final String JSON_DOC =
            "{\n"                                              // 0
          + "  \"name\": \"project\",\n"                       // 1
          + "  \"version\": \"1.0\",\n"                        // 2
          + "  \"description\": \"sample\",\n"                 // 3
          + "  \"main\": \"index.js\",\n"                       // 4
          + "  \"scripts\": {\n"                               // 5
          + "    \"parameters\": {\n"                          // 6 ← ligne de début du pli
          + "      \"a\": 1,\n"                                 // 7
          + "      \"b\": 2,\n"                                // 8
          + "      \"c\": 3,\n"                                // 9
          + "      \"d\": 4,\n"                                // 10
          + "      \"e\": 5,\n"                                // 11
          + "      \"f\": 6,\n"                                // 12
          + "      \"g\": 7,\n"                                // 13
          + "      \"h\": 8,\n"                                // 14
          + "      \"i\": 9,\n"                                // 15
          + "    },\n"                                        // 16 ← ligne de fin du pli
          + "    \"build\": \"tsc\"\n"                         // 17
          + "  },\n"                                          // 18
          + "  \"dependencies\": {}\n"                         // 19
          + "}\n";                                            // 20

    private EditorView newViewWithFold() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(JSON_DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);

        // Pli replié sur les lignes 6..16 — la chip {...} remplace les
        // lignes 7..16 sur la ligne 6.
        EditorDocument d = session.getDocument();
        // start = début de la ligne 6 + indent + « "parameters": { » = après le {
        int foldStart = d.lineStart(6) + 4 /* "parameters": { */ + "    \"parameters\": ".length();
        // Correction : foldStart = position juste après le « { » ouvrant de la
        // ligne 6. La ligne 6 est « "parameters": { » (avec indent). Calcul
        // plus sûr : fin de la ligne 6 (après le {).
        foldStart = d.lineEnd(6) - 1; // juste avant le \n, donc après le {
        // end = début de la ligne 16 (avant le « } » fermant), pour que le
        // pli couvre les lignes 7..15 et la fin se fasse sur la ligne 16
        // (qui contient « "  }, " »). Convention: r.end = position du }
        // fermant.
        int foldEnd = d.lineEnd(16) - 2; // avant le « , » final : position du }
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(foldStart, foldEnd,
                "{…}", "block", true));
        session.setFoldRegions(folds);
        return view;
    }

    private static void injectMetrics(EditorView view) {
        try {
            setFloat(view.metrics, "lineHeight", LINE_H);
            setFloat(view.metrics, "charWidth", CHAR_W);
            setFloat(view.metrics, "padTop", PAD_TOP);
            setFloat(view.metrics, "padBottom", 0f);
            setFloat(view.metrics, "padLeft", CHAR_W * 0.5f);
            setFloat(view.metrics, "gutterWidth", CHAR_W * 7f);
            setFloat(view.metrics, "foldStripWidth", CHAR_W * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /**
     * Cas 1 : le caret à l'offset correspondant à « juste après la chip »
     * (i.e. au début du suffixe de la ligne de fin, donc sur le « } » de la
     * ligne 16) doit avoir son Y calculé à partir de la ligne de DÉBUT du
     * pli (ligne 6), pas de la ligne de fin (16) qui est masquée.
     */
    @Test
    public void caret_afterFoldChip_yIsOnFoldStartLine() {
        EditorView view = newViewWithFold();
        EditorDocument d = view.session.getDocument();

        // Position « juste après la chip » = fold.end (juste avant le
        // suffixe, i.e. sur le } fermant). C'est ce que offsetAt retourne
        // pour un tap sur la chip.
        DiagnosticShift.FoldRegion fold = view.session.getFoldRegions().get(0);
        int caretOffset = fold.end;

        float[] pos = view.caretScreenPos(caretOffset);
        float y = pos[1];

        // Y attendu : celui de la ligne de DÉBUT du pli (ligne 6), pas
        // celui de la ligne de fin (16, masquée).
        int foldStartLine = d.lineForOffset(fold.start);
        float expectedY = view.docLineToY(foldStartLine) - view.vOffset;
        float wrongY = view.docLineToY(d.lineForOffset(fold.end)) - view.vOffset;

        // Sanity : la ligne de début et la ligne de fin ne sont PAS à la
        // même rangée Y. La ligne de fin est masquée → docLineToY(ligne 16)
        // retourne la rangée de la ligne 17 (qui suit le pli).
        assertTrue("Précondition : Y ligne 6 ≠ Y ligne 16",
                Math.abs(expectedY - wrongY) > LINE_H * 0.5f);

        assertEquals("Le caret à fold.end doit avoir le Y de la ligne de"
                + " début du pli (ligne 6), pas celui de la ligne de fin"
                + " (masquée) — y=" + y + " attendu=" + expectedY
                + " (bug : y=" + wrongY + ")",
                expectedY, y, 1f);
    }

    /**
     * Cas 2 : le caret dans le suffixe de la ligne de fin (au-delà de la
     * chip mais sur la même ligne composite) doit aussi avoir le Y de la
     * ligne de début du pli.
     */
    @Test
    public void caret_inFoldSuffix_yIsOnFoldStartLine() {
        EditorView view = newViewWithFold();
        EditorDocument d = view.session.getDocument();
        DiagnosticShift.FoldRegion fold = view.session.getFoldRegions().get(0);

        // Caret 1 caractère après fold.end (dans le suffixe).
        int caretOffset = fold.end + 1;
        // Vérifier qu'on ne sort pas du document.
        if (caretOffset >= d.length()) {
            caretOffset = d.length() - 1;
        }

        float[] pos = view.caretScreenPos(caretOffset);
        float y = pos[1];

        int foldStartLine = d.lineForOffset(fold.start);
        float expectedY = view.docLineToY(foldStartLine) - view.vOffset;

        assertEquals("Le caret dans le suffixe du pli doit avoir le Y de"
                + " la ligne de début du pli, pas celui de la ligne de"
                + " fin (masquée)",
                expectedY, y, 1f);
    }

    /**
     * Cas 3 : X du caret doit rester correct (bonne abscisse) — c'est le
     * Y qui était cassé, pas le X. La position X doit correspondre à
     * « juste après la chip » : prefixEnd + placeholder.length() pixels
     * après le début de la zone de texte.
     */
    @Test
    public void caret_afterFoldChip_xIsAfterChip() {
        EditorView view = newViewWithFold();
        EditorDocument d = view.session.getDocument();
        DiagnosticShift.FoldRegion fold = view.session.getFoldRegions().get(0);

        int caretOffset = fold.end;
        float[] pos = view.caretScreenPos(caretOffset);
        float x = pos[0];

        // X attendu : prefixEnd + placeholder.length() caractères depuis
        // le début de la zone de texte.
        int foldStartLine = d.lineForOffset(fold.start);
        int prefixEnd = fold.start - d.lineStart(foldStartLine);
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float expectedX = textAreaLeft
                + (prefixEnd + fold.placeholder.length()) * CHAR_W
                - view.hOffset;

        assertEquals("Le X du caret après la chip doit être à la fin de"
                + " la chip (prefixEnd + placeholder.length())",
                expectedX, x, 1f);
    }
}

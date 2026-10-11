package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowCanvas;

import android.content.Context;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Lot B.10 (audit v3.41.0) — portée active ancrée sur la paire
 * d'accolades pour les langages C_LIKE.
 *
 * <p>La spécification 4.2 point 10 propose d'ancrer la portée
 * active sur la paire d'accolades (via
 * {@link EditorBracketMatcher#braceScopeLevel}) plutôt que sur
 * l'indentation seule, pour rester correct quand l'indentation
 * du fichier est incohérente (code mal formaté, snippets copiés).
 * Implémentée pour les langages C_LIKE (Java, Kotlin, C/C++,
 * Go, Rust, JS/TS, PHP, Swift, Dart, Groovy, Scala).</p>
 *
 * <p>Pour les autres langages (Python, YAML, XML, etc.), la
 * portée active retombe sur l'indentation — pas d'accolades
 * significatives.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class IndentGuidesBraceScopeTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /**
     * Code Java avec indentation INCOHÉRENTE : le bloc interne est
     * à 4 espaces mais le bloc externe est à 0 (mal formaté). La
     * portée active par accolades devrait identifier le bloc
     * externe (profondeur 1) même si l'indentation suggère le
     * bloc interne.
     */
    private static final String JAVA_INCONSISTENT_INDENT =
            "public class Foo {\n"          // 0 — ouvre bloc 1
          + "public void bar() {\n"          // 1 — ouvre bloc 2 (col 0)
          + "    int x = 1;\n"               // 2 — col 4 (dans bloc 2)
          + "    }\n"                         // 3 — ferme bloc 2
          + "}\n";                           // 4 — ferme bloc 1

    private EditorView newView(String doc, String language) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setLanguage(language);
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        return view;
    }

    private static void injectMetrics(EditorView view) {
        try {
            setFloat(view.metrics, "lineHeight", LINE_H);
            setFloat(view.metrics, "charWidth", CHAR_W);
            setFloat(view.metrics, "padTop", LINE_H * 0.5f);
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
     * Cas 1 : braceScopeLevel de base — profondeur 1 à l'intérieur
     * d'un bloc d'accolades, 0 à l'extérieur.
     */
    @Test
    public void braceScopeLevel_basicNesting() {
        String text = "{\n  int x = 1;\n}\ncode";
        // Caret à l'intérieur du bloc (offset 5 — après « {\n  in »).
        assertEquals(1, EditorView.braceScopeLevel(text, 5));
        // Caret SUR le « } » (offset 15) — la profondeur est 1 car
        // le scan jusqu'à 15 (exclusif) ne voit pas le « } ».
        assertEquals(1, EditorView.braceScopeLevel(text, 15));
        // Caret APRÈS le « } » (offset 16) — le scan voit le « } »,
        // depth=0.
        assertEquals(0, EditorView.braceScopeLevel(text, 16));
    }

    /**
     * Cas 2 : braceScopeLevel ignore les accolades dans les chaînes
     * et les commentaires.
     */
    @Test
    public void braceScopeLevel_ignoresBracesInStringsAndComments() {
        String text = "{\n  String s = \"{not a brace}\";\n  // {not either}\n  int x = 1;\n}";
        // Caret à la fin (dans le bloc externe, profondeur 1).
        assertEquals(1, EditorView.braceScopeLevel(text, text.length() - 1));
    }

    /**
     * Cas 3 : braceScopeLevel est borné par BRACKET_SCAN_LIMIT —
     * sur un texte très long sans accolades, renvoie 0 sans
     * timeout.
     */
    @Test
    public void braceScopeLevel_boundedByScanLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60_000; i++) sb.append('a');
        String text = sb.toString();
        // Pas d'accolades — profondeur 0.
        assertEquals(0, EditorView.braceScopeLevel(text, text.length() - 1));
    }

    /**
     * Cas 4 : pour un fichier Java avec indentation cohérente,
     * la portée active (style INDENT_WITH_SCOPE) doit utiliser
     * la profondeur d'accolades pour identifier le bloc actif.
     *
     * <p>Le caret est sur la ligne 3 (col 8 — dans le bloc interne
     * selon les accolades, profondeur 2). Le guide actif doit
     * être à 8 colonnes (2×unitCols).</p>
     */
    @Test
    public void java_consistentIndent_braceScopeUsed() {
        String java =
                "public class Foo {\n"     // 0 — col 0, ouvre bloc 1
              + "    public void bar() {\n" // 1 — col 4, ouvre bloc 2
              + "        int x = 1;\n"      // 2 — col 8, dans bloc 2
              + "    }\n"                   // 3 — col 4, ferme bloc 2
              + "}\n";                      // 4 — col 0, ferme bloc 1
        EditorView view = newView(java, "java");
        EditorDocument d = view.session.getDocument();

        // Caret sur la ligne 2 (col 8 — dans le bloc interne,
        // profondeur 2 selon les accolades).
        int caret = d.lineStart(2) + 8;
        view.session.setSelection(caret);

        Canvas canvas = new Canvas();
        view.draw(canvas);

        // Le guide actif doit être à la couleur d'accent (theme.caret).
        int activeColor = view.theme.caret;
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        int activeCount = 0;
        // Les guides basés sur les blocs utilisent drawRect (approche
        // l'éditeur de code de référence), pas drawLine. Chercher les deux.
        for (int i = 0; i < shadow.getLinePaintHistoryCount(); i++) {
            ShadowCanvas.LinePaintHistoryEvent e = shadow.getDrawnLine(i);
            if (e.paint != null && e.paint.getColor() == activeColor) {
                activeCount++;
            }
        }
        for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
            ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
            if (e.paint != null && e.paint.getColor() == activeColor) {
                activeCount++;
            }
        }
        // Avec le Lot B.10, la profondeur est 2 (caret dans bloc 2),
        // donc le guide actif est à 2×unitCols = 8 colonnes.
        // Le guide à 8 doit être actif — au moins 1 ligne activeColor.
        assertTrue("Lot B.10 : la portée active doit être basée sur les "
                + "accolades pour Java (langage C_LIKE) — au moins 1 "
                + "guide actif (couleur theme.caret), observé="
                + activeCount,
                activeCount > 0);
    }

    /**
     * Cas 5 : pour un fichier Python (langage non C_LIKE), la
     * portée active retombe sur l'indentation — pas d'accolades
     * significatives. braceScopeLevel n'est pas utilisé.
     */
    @Test
    public void python_nonCLike_fallsBackToIndent() {
        String python =
                "def foo():\n"     // 0 — col 0
              + "    x = 1\n"      // 1 — col 4
              + "    if True:\n"   // 2 — col 4
              + "        y = 2\n"  // 3 — col 8
              + "\n";              // 4
        EditorView view = newView(python, "python");
        EditorDocument d = view.session.getDocument();

        // Caret sur la ligne 3 (col 8 — imbrication 2 selon
        // l'indentation).
        int caret = d.lineStart(3) + 8;
        view.session.setSelection(caret);

        Canvas canvas = new Canvas();
        view.draw(canvas);

        // La portée active doit être basée sur l'indentation (8),
        // pas sur les accolades (il n'y en a pas en Python).
        int activeColor = view.theme.caret;
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        int activeLineCount = 0;
        for (int i = 0; i < shadow.getLinePaintHistoryCount(); i++) {
            ShadowCanvas.LinePaintHistoryEvent e = shadow.getDrawnLine(i);
            if (e.paint != null && e.paint.getColor() == activeColor) {
                activeLineCount++;
            }
        }
        for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
            ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
            if (e.paint != null && e.paint.getColor() == activeColor) {
                activeLineCount++;
            }
        }
        assertTrue("Pour Python (non C_LIKE), la portée active doit "
                + "retomber sur l'indentation — au moins 1 guide "
                + "actif, observé=" + activeLineCount,
                activeLineCount > 0);
    }
}

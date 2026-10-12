package jo.codeeditor.view;

import jo.codeeditor.view.popup.EditorPopupAnchors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.MotionEvent;
import android.os.SystemClock;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T1 (P0) — verrouille le <b>SPI d'injection d'actions de la pill de
 * sélection</b> :
 *
 * <ul>
 *   <li><b>Injection</b> — 3 actions injectées via
 *       {@code addSelectionAction} apparaissent au rendu (métriques),
 *       entre les canoniques d'édition (Copy/Cut/Paste/Select all) et
 *       les icônes Docs/Actions, ordre stable = ordre d'injection ;</li>
 *   <li><b>Tappables</b> — un tap sur une action injectée exécute son
 *       {@code perform} puis referme la pill + masque les poignées
 *       (même contrat que Copy/Cut/Paste) ;</li>
 *   <li><b>Désactivée</b> — l'action à {@code enabled = () -> false} est
 *       estompée (drapeau itemDisabled) et son tap est REJETÉ sans
 *       refermer la pill ;</li>
 *   <li><b>Collapsée</b> — sans vraie sélection (caret), les actions
 *       injectées n'apparaissent PAS ;</li>
 *   <li><b>Retrait</b> — {@code removeSelectionAction(id)} retire la
 *       bonne action, les autres survivent.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SelectionToolbarSpiInjectTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Journal des performs exécutés. */
    private static final class Journal {
        final List<String> performed = new ArrayList<>();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
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

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    /** Sélectionne « name » et affiche la pill (chrome tactile armé). */
    private static void armSelection(EditorView view) {
        int start = DOC.indexOf("name");
        view.getSession().setSelection(Selection.range(start, start + 4));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;
    }

    private static SelectionAction action(String id, Journal journal) {
        return new SelectionAction(id, id, () -> journal.performed.add(id));
    }

    @Test
    public void troisActionsInjectées_apparaissentEntreCanoniquesEtIcônes() {
        EditorView view = newView();
        Journal journal = new Journal();
        view.addSelectionAction(action("Comment", journal));
        view.addSelectionAction(new SelectionAction("Dup", "Duplicate",
                () -> journal.performed.add("Dup"), () -> false));
        view.addSelectionAction(action("Upper", journal));
        armSelection(view);

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertNotNull(m);
        // 4 textes canoniques + 3 injectées + 2 icônes.
        assertEquals(9, m.count);

        // Ordre : Copy, Cut, Paste, Select all, Comment, Dup, Upper, ⋯ Docs, Actions.
        assertEquals("Copy", m.label[0]);
        assertEquals("Cut", m.label[1]);
        assertEquals("Paste", m.label[2]);
        assertEquals("Select all", m.label[3]);
        assertEquals("Comment", m.label[4]);
        assertEquals("Duplicate", m.label[5]);
        assertEquals("Upper", m.label[6]);
        assertNull("les icônes restent en bout de pill", m.label[7]);
        assertTrue(m.isIcon[7]);
        assertTrue(m.isIcon[8]);

        // Références des actions injectées (items 4..6).
        assertNotNull(m.injected[4]);
        assertEquals("Comment", m.injected[4].id);
        assertEquals("Dup", m.injected[5].id);
        assertEquals("Upper", m.injected[6].id);
        // La désactivée porte le drapeau.
        assertFalse(m.itemDisabled[4]);
        assertTrue("l'action désactivée est estompée", m.itemDisabled[5]);
        assertFalse(m.itemDisabled[6]);
        // Aucun débordement avec 3 actions courtes sur 1080px.
        assertFalse(m.itemDisabled[8]);
        assertTrue("pas de débordement", m.overflow.isEmpty());
    }

    @Test
    public void tapSurUneActionInjectée_exécuteEtReferme() {
        EditorView view = newView();
        Journal journal = new Journal();
        view.addSelectionAction(action("Comment", journal));
        view.addSelectionAction(action("Upper", journal));
        armSelection(view);

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        float x = m.itemX[4] + m.itemW[4] * 0.5f;
        float y = m.y + m.h * 0.5f;
        tap(view, x, y);

        assertEquals("le perform de l'action tapée s'exécute",
                java.util.Collections.singletonList("Comment"), journal.performed);
        assertFalse("la pill se referme (contrat Copy/Cut/Paste)",
                view.selectionToolbarVisible);
        assertFalse("les poignées se masquent", view.handlesVisible);
    }

    @Test
    public void tapSurUneActionDésactivée_estRejetéSansRefermer() {
        EditorView view = newView();
        Journal journal = new Journal();
        view.addSelectionAction(action("Comment", journal));
        view.addSelectionAction(new SelectionAction("Dup", "Duplicate",
                () -> journal.performed.add("Dup"), () -> false));
        armSelection(view);

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        float x = m.itemX[5] + m.itemW[5] * 0.5f;
        float y = m.y + m.h * 0.5f;
        tap(view, x, y);

        assertTrue("le perform n'est PAS exécuté", journal.performed.isEmpty());
        assertTrue("la pill reste ouverte (tap rejeté)",
                view.selectionToolbarVisible);
        assertTrue(view.handlesVisible);
    }

    @Test
    public void sélectionCollapsée_actionsInjectéesAbsentes() {
        EditorView view = newView();
        Journal journal = new Journal();
        view.addSelectionAction(action("Comment", journal));
        // Re-tap caret → pill COLLAPSED (Paste/Select all + icônes).
        view.getSession().setSelection(DOC.indexOf("greet"));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertNotNull(m);
        // 2 textes (Paste/Select all — pas Copy/Cut en collapsed) +
        // 2 icônes : les injectées n'apparaissent pas sans vraie
        // sélection.
        assertEquals(4, m.count);
        assertEquals("Paste", m.label[0]);
        assertEquals("Select all", m.label[1]);
        for (int i = 0; i < m.count; i++) {
            assertNull("aucune action injectée en mode collapsed à " + i,
                    m.injected[i]);
        }
    }

    @Test
    public void removeSelectionAction_retireLaBonneAction() {
        EditorView view = newView();
        Journal journal = new Journal();
        view.addSelectionAction(action("Comment", journal));
        view.addSelectionAction(action("Upper", journal));
        view.addSelectionAction(action("Lower", journal));
        armSelection(view);

        view.removeSelectionAction("Upper");
        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertEquals("8 items : 4 canoniques + 2 injectées + 2 icônes",
                8, m.count);
        assertEquals("Comment", m.label[4]);
        assertEquals("Lower", m.label[5]);
        assertEquals("Comment", m.injected[4].id);
        assertEquals("Lower", m.injected[5].id);
        // Un id inconnu ne casse rien.
        view.removeSelectionAction("inconnu");
        assertEquals(8, view.selectionToolbarMetrics().count);
    }
}

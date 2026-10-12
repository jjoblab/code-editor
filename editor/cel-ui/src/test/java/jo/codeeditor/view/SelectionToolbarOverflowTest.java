package jo.codeeditor.view;

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
 * T1 (P0) — verrouille le <b>débordement « ⋯ » de la pill de
 * sélection</b> : au-delà de ce qui tient dans la largeur de la vue,
 * les actions injectées suivantes basculent dans le menu contextuel
 * unifié (NavMenu, section « MORE » — zéro nouveau chrome).
 *
 * <ul>
 *   <li><b>Troncature</b> — 10 actions injectées → la pill n'en montre
 *       qu'une partie + l'item « ⋯ » ; les non-affichées peuplent la
 *       liste de débordement (métriques = source unique) ;</li>
 *   <li><b>Ouverture du menu</b> — un tap sur « ⋯ » referme le chrome de
 *       sélection et ouvre le NavMenu dont les rangées listent les
 *       actions débordantes ;</li>
 *   <li><b>Exécution</b> — un tap sur une rangée du menu exécute le
 *       perform de l'action et referme le menu.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SelectionToolbarOverflowTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private static final class Journal {
        final List<String> performed = new ArrayList<>();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        // Viewport ÉTROIT (480px) : le débordement doit se produire même
        // avec la métrique Robolectric (measureText = 1px/caractère) —
        // 10 libellés de 22 caractères ne tiennent pas à côté des
        // canoniques sur une demi-largeur de téléphone.
        view.measure(480, 1920);
        view.layout(0, 0, 480, 1920);
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

    private static void armSelection(EditorView view) {
        int start = DOC.indexOf("name");
        view.getSession().setSelection(Selection.range(start, start + 4));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;
    }

    /** 10 actions injectées à libellés longs (débordement garanti). */
    private static void injectTen(EditorView view, Journal journal) {
        for (int i = 1; i <= 10; i++) {
            final String id = "Action longue numéro " + i;
            view.addSelectionAction(new SelectionAction(id, id,
                    () -> journal.performed.add(id)));
        }
    }

    @Test
    public void dixActions_pillTronquéeEtDébordementPeuplé() {
        EditorView view = newView();
        Journal journal = new Journal();
        injectTen(view, journal);
        armSelection(view);

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertNotNull(m);

        int shown = 0;
        boolean overflowItem = false;
        for (int i = 0; i < m.count; i++) {
            if (m.injected[i] != null) shown++;
            if (m.action[i] == jo.codeeditor.view.EditorSelectionActions.SEL_ACT_OVERFLOW) {
                overflowItem = true;
            }
        }
        assertTrue("la pill est tronquée : toutes les actions ne tiennent pas",
                shown < 10);
        assertTrue("l'item « ⋯ » de débordement est présent", overflowItem);
        assertEquals("les non-affichées peuplent le débordement",
                10 - shown, m.overflow.size());
        // La pill reste DANS le viewport (clamp).
        assertTrue(m.x >= 0);
        assertTrue(m.x + m.w <= view.getWidth() + 0.5f);
        // La métrique est la source unique : le débordement du NavMenu
        // lit la même liste.
        assertEquals(m.overflow,
                view.popupManager.selActions.overflow);
    }

    @Test
    public void tapSurLeDébordement_ouvreLeNavMenuAvecLesActions() {
        EditorView view = newView();
        Journal journal = new Journal();
        injectTen(view, journal);
        armSelection(view);

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int overflowIdx = -1;
        for (int i = 0; i < m.count; i++) {
            if (m.action[i] == jo.codeeditor.view.EditorSelectionActions.SEL_ACT_OVERFLOW) {
                overflowIdx = i;
                break;
            }
        }
        assertTrue(overflowIdx >= 0);
        float x = m.itemX[overflowIdx] + m.itemW[overflowIdx] * 0.5f;
        float y = m.y + m.h * 0.5f;
        tap(view, x, y);

        assertFalse("le chrome de sélection se referme (⋯ ouvre le menu)",
                view.selectionToolbarVisible);
        assertFalse(view.handlesVisible);
        assertTrue("le menu contextuel unifié est ouvert (hôte d'overflow)",
                view.navMenuVisible);

        // Les rangées du menu contiennent la section MORE avec les
        // actions débordantes.
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int actionRows = 0;
        boolean moreHeader = false;
        for (EditorView.NavMenuRow row : rows) {
            if (row.type == EditorView.NavMenuRow.TYPE_HEADER
                    && "MORE".equals(row.ref)) moreHeader = true;
            if (row.type == EditorView.NavMenuRow.TYPE_ACTION
                    && row.ref instanceof SelectionAction) actionRows++;
        }
        assertTrue("section MORE présente", moreHeader);
        assertEquals("une rangée par action débordante",
                m.overflow.size(), actionRows);

        // Le menu se referme proprement.
        view.dismissNavMenu();
        assertFalse(view.navMenuVisible);
    }

    @Test
    public void tapSurUneRangéeDuMenu_exécuteEtReferme() {
        EditorView view = newView();
        Journal journal = new Journal();
        injectTen(view, journal);
        armSelection(view);

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int overflowIdx = -1;
        for (int i = 0; i < m.count; i++) {
            if (m.action[i] == jo.codeeditor.view.EditorSelectionActions.SEL_ACT_OVERFLOW) {
                overflowIdx = i;
                break;
            }
        }
        tap(view, m.itemX[overflowIdx] + m.itemW[overflowIdx] * 0.5f,
                m.y + m.h * 0.5f);
        assertTrue(view.navMenuVisible);

        // Tape la PREMIÈRE rangée d'action débordante du menu.
        float[] menu = view.navMenuMetrics();
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = EditorView.NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = EditorView.NAV_MENU_HEADER_HEIGHT_DP * density;
        float contentY = menu[1];
        for (EditorView.NavMenuRow row : rows) {
            float rh = row.type == EditorView.NavMenuRow.TYPE_HEADER ? headerH : rowH;
            if (row.type == EditorView.NavMenuRow.TYPE_ACTION
                    && row.ref instanceof SelectionAction) {
                float rowY = contentY + rh * 0.5f;
                tap(view, menu[0] + menu[2] * 0.5f, rowY);
                assertEquals("le perform de la rangée s'exécute",
                        1, journal.performed.size());
                assertEquals(((SelectionAction) row.ref).label,
                        journal.performed.get(0));
                assertFalse("le menu se referme après exécution",
                        view.navMenuVisible);
                return;
            }
            contentY += rh;
        }
        fail("aucune rangée d'action débordante trouvée dans le menu");
    }
}

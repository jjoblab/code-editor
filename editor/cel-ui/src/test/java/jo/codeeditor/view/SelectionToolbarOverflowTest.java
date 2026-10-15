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
 * T1/UX-1 (P0 + retour terrain J3) — verrouille le <b>débordement des
 * actions injectées et l'UNICITÉ du « ⋯ » de la pill de sélection</b>.
 *
 * <p>Retour terrain (session J3) : la pill affichait DEUX boutons à trois
 * points — l'item « ⋯ » de débordement (T1) et l'icône Actions du lot 1.
 * L'icône Actions (celle qui ouvre le menu le plus riche) est désormais
 * l'UNIQUE débordement : les actions injectées non affichées peuplent la
 * section « MORE » du menu contextuel unifié qu'elle ouvre.</p>
 *
 * <ul>
 *   <li><b>Un seul trois-points</b> — la métrique ne contient AUCUN item
 *       SEL_ACT_OVERFLOW, exactement UNE icône SEL_ACT_ACTIONS ;</li>
 *   <li><b>Troncature</b> — 10 actions injectées → la pill n'en montre
 *       qu'une partie ; les non-affichées peuplent la liste de
 *       débordement (métriques = source unique) ;</li>
 *   <li><b>Ouverture du menu</b> — un tap sur l'icône Actions referme le
 *       chrome de sélection et ouvre le NavMenu unifié dont les rangées
 *       listent AUSSI les actions débordantes (section MORE) ;</li>
 *   <li><b>Exécution</b> — un tap sur une rangée du menu exécute le
 *       perform de l'action et referme le menu ;</li>
 *   <li><b>Anti-périmé</b> — un menu ouvert HORS pill (sans métrique
 *       fraîche) ne montre pas de section MORE résiduelle.</li>
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
        TestMetrics.injecter(view, lineHeight, charWidth);
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

    /** Index de l'icône Actions « ⋯ » (l'UNIQUE trois-points de la pill). */
    private static int actionsIconIndex(
            jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m) {
        for (int i = 0; i < m.count; i++) {
            if (m.action[i] == EditorView.SEL_ACT_ACTIONS) return i;
        }
        return -1;
    }

    /** Attend l'ouverture async du menu (voie « features »). */
    /** A-06 : attente bornée du menu (navMenuVisible). */
    private static boolean awaitNavMenu(EditorView view, long timeoutMs) {
        return jo.codeeditor.AttenteBornee.jusquA(timeoutMs, () -> view.navMenuVisible);
    }

    @Test
    public void dixActions_unSeulTroisPoints_débordementPeuplé() {
        EditorView view = newView();
        Journal journal = new Journal();
        injectTen(view, journal);
        armSelection(view);

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertNotNull(m);

        int shown = 0;
        int overflowItems = 0;
        int actionsIcons = 0;
        for (int i = 0; i < m.count; i++) {
            if (m.injected[i] != null) shown++;
            if (m.action[i] == jo.codeeditor.view.EditorSelectionActions.SEL_ACT_OVERFLOW) {
                overflowItems++;
            }
            if (m.action[i] == EditorView.SEL_ACT_ACTIONS) actionsIcons++;
        }
        assertTrue("la pill est tronquée : toutes les actions ne tiennent pas",
                shown < 10);
        assertEquals("PLUS D'ITEM « ⋯ » de débordement (UX-1 : deux"
                + " trois-points se ressemblaient trop)",
                0, overflowItems);
        assertEquals("exactement UNE icône Actions « ⋯ » (l'unique débordement)",
                1, actionsIcons);
        assertEquals("les non-affichées peuplent le débordement",
                10 - shown, m.overflow.size());
        // La pill reste DANS le viewport (clamp).
        assertTrue(m.x >= 0);
        assertTrue(m.x + m.w <= view.getWidth() + 0.5f);
        // La métrique est la source unique : le débordement du menu
        // lit la même liste.
        assertEquals(m.overflow,
                view.popupManager.selActions.overflow);
    }

    @Test
    public void tapSurLIcôneActions_ouvreLeMenuUnifiéAvecLaSectionMore() throws Exception {
        EditorView view = newView();
        Journal journal = new Journal();
        injectTen(view, journal);
        armSelection(view);

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int iconIdx = actionsIconIndex(m);
        assertTrue(iconIdx >= 0);
        float x = m.itemX[iconIdx] + m.itemW[iconIdx] * 0.5f;
        float y = m.y + m.h * 0.5f;
        tap(view, x, y);

        assertFalse("le chrome de sélection se referme (⋯ ouvre le menu)",
                view.selectionToolbarVisible);
        assertFalse(view.handlesVisible);
        assertTrue("le menu contextuel unifié s'ouvre (hôte du débordement, async)",
                awaitNavMenu(view, 5000));

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
    public void tapSurUneRangéeDuMenu_exécuteEtReferme() throws Exception {
        EditorView view = newView();
        Journal journal = new Journal();
        injectTen(view, journal);
        armSelection(view);

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int iconIdx = actionsIconIndex(m);
        assertTrue(iconIdx >= 0);
        tap(view, m.itemX[iconIdx] + m.itemW[iconIdx] * 0.5f,
                m.y + m.h * 0.5f);
        assertTrue(awaitNavMenu(view, 5000));

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

    @Test
    public void menuOuvertHorsPill_pasDeSectionMoreRésiduelle() throws Exception {
        EditorView view = newView();
        Journal journal = new Journal();
        injectTen(view, journal);
        armSelection(view);
        // Peuple le débordement via la métrique de la pill…
        view.selectionToolbarMetrics();
        assertFalse(view.popupManager.selActions.overflow.isEmpty());

        // … puis referme TOUT le chrome de sélection et ouvre le menu
        // comme depuis le clavier/API (pill invisible → anti-périmé).
        view.selectionToolbarVisible = false;
        view.handlesVisible = false;
        view.showNavMenu(2, DOC.indexOf("greet"));
        assertTrue(awaitNavMenu(view, 5000));

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        for (EditorView.NavMenuRow row : rows) {
            assertFalse("pas de section MORE résiduelle hors pill"
                            + " (débordement purgé à l'ouverture)",
                    row.type == EditorView.NavMenuRow.TYPE_HEADER
                            && "MORE".equals(row.ref));
        }
        view.dismissNavMenu();
    }
}

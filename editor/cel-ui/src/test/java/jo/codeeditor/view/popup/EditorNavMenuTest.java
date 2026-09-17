package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Verrouille le menu contextuel unifié, ouvert par le bouton
 * « Actions ⋯ » de la toolbar de sélection :
 *
 * <ul>
 *   <li><b>Sections</b> — GO TO (options applicables au caret) / QUICK FIXES
 *       (kind « quickfix ») / INTENTIONS (autres kinds), affichées seulement
 *       si non-vides ; « Nothing found in source. » quand tout est vide ;</li>
 *   <li><b>Résolution async</b> — Declaration via definitionResolver, Type
 *       declaration via typeDefinitionResolver, quick-fixes/intentions depuis
 *       le cache de la ligne ;</li>
 *   <li><b>Picks</b> — option GO TO mono-cible → navigation même-fichier ;
 *       multi-cibles → mode RESULTS (picker) ; action → apply + fermeture ;</li>
 *   <li><b>Geste</b> — tap sur une rangée la résout ; tap ailleurs referme ;
 *       drag = scroll du contenu.</li>
 * </ul>
 *
 * <p>Verrouille aussi les DEUX options GO TO : Implementations
 * (implementationsResolver — héritiers DIRECTS, icône layers) et Super
 * (superResolver — membre outrepassé / supertypes DIRECTS, icône pin),
 * leur ordre NavKind (Declaration → Implementations → Type declaration →
 * Super), les libellés transportés par les providers LSP
 * (« Simple  ·  pkg » / « name  ·  Super ») et le fallback
 * nom-court-de-fichier.</p>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorBracketSheetTapTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class EditorNavMenuTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;
    private static final float PAD_LEFT = 5f;
    private static final float GUTTER_W = 70f;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
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
            setFloat(view.metrics, "padTop", PAD_TOP);
            setFloat(view.metrics, "padLeft", PAD_LEFT);
            setFloat(view.metrics, "gutterWidth", GUTTER_W);
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

    /** Simule un tap complet (DOWN + UP) au même endroit. */
    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(downEvent);
        downEvent.recycle();
        MotionEvent upEvent = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(upEvent);
        upEvent.recycle();
    }

    /** Simule un drag (DOWN + MOVEs + UP). */
    private static void drag(EditorView view, float x, float y1, float y2) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y1, 0);
        view.onTouchEvent(d);
        d.recycle();
        for (int i = 1; i <= 5; i++) {
            float y = y1 + (y2 - y1) * i / 5f;
            MotionEvent m = MotionEvent.obtain(down, down + i * 20,
                    MotionEvent.ACTION_MOVE, x, y, 0);
            view.onTouchEvent(m);
            m.recycle();
        }
        MotionEvent u = MotionEvent.obtain(down, down + 140, MotionEvent.ACTION_UP, x, y2, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    /** Attend l'ouverture async du menu (FEATURE_EXECUTOR). */
    private static boolean awaitNavMenu(EditorView view, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (view.navMenuVisible) return true;
            Thread.sleep(25);
        }
        return view.navMenuVisible;
    }

    /** Ouvre le menu et attend son contenu. */
    private static EditorView openMenu(EditorView view) throws InterruptedException {
        int greet = DOC.indexOf("greet");
        view.showNavMenu(2, greet);
        assertTrue("le menu doit s'ouvrir (async)", awaitNavMenu(view, 5000));
        return view;
    }

    private static void putActions(EditorView view, int line,
            EditorView.CodeAction... actions) {
        List<EditorView.CodeAction> list = new ArrayList<>();
        for (EditorView.CodeAction a : actions) list.add(a);
        view.codeActionsByLine.put(line, list);
    }

    private static int rowOfType(List<EditorView.NavMenuRow> rows, int type, int skip) {
        int seen = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).type == type) {
                if (seen == skip) return i;
                seen++;
            }
        }
        return -1;
    }

    // ── Sections ───────────────────────────────────────────────────

    @Test
    public void rows_allEmpty_showsNothingFound() throws Exception {
        EditorView view = newView();
        openMenu(view);
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        assertEquals(1, rows.size());
        assertEquals(EditorView.NavMenuRow.TYPE_NOTHING, rows.get(0).type);
        // Render smoke : « Nothing found in source. » doit dessiner sans crash.
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
    }

    @Test
    public void rows_sectionsInOrder_withHeaders() throws Exception {
        EditorView view = newView();
        // Declaration + Type declaration + 1 quick-fix + 1 intention.
        view.setDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("", 5, "greet")));
        view.setTypeDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("", 0, "Main")));
        putActions(view, 2,
                new EditorView.CodeAction("Create method", "quickfix", () -> { }),
                new EditorView.CodeAction("Organize imports", "source", () -> { }));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        // Header GO TO + 2 options + header QUICK FIXES + 1 + header
        // INTENTIONS + 1.
        assertEquals(7, rows.size());
        assertEquals(EditorView.NavMenuRow.TYPE_HEADER, rows.get(0).type);
        assertEquals("GO TO", rows.get(0).ref);
        assertEquals(EditorView.NavMenuRow.TYPE_OPTION, rows.get(1).type);
        assertEquals("Declaration", ((NavigationMenu.NavOption) rows.get(1).ref).label);
        assertEquals("Type declaration",
                ((NavigationMenu.NavOption) rows.get(2).ref).label);
        assertEquals("QUICK FIXES", rows.get(3).ref);
        assertEquals(EditorView.NavMenuRow.TYPE_ACTION, rows.get(4).type);
        assertEquals("Create method",
                ((EditorView.CodeAction) rows.get(4).ref).title);
        assertEquals("INTENTIONS", rows.get(5).ref);
        assertEquals("Organize imports",
                ((EditorView.CodeAction) rows.get(6).ref).title);
    }

    @Test
    public void rows_quickFixVsIntention_splitByKind() throws Exception {
        EditorView view = newView();
        putActions(view, 2,
                new EditorView.CodeAction("Fix A", "quickfix", () -> { }),
                new EditorView.CodeAction("Refactor B", "refactor", () -> { }),
                new EditorView.CodeAction("Fix C", "quickfix.inline", () -> { }));
        openMenu(view);
        assertEquals(2, view.navMenuQuickFixes.size());
        assertEquals("quickfix* va dans QUICK FIXES",
                "Fix A", view.navMenuQuickFixes.get(0).title);
        assertEquals("Fix C", view.navMenuQuickFixes.get(1).title);
        assertEquals(1, view.navMenuIntentions.size());
        assertEquals("Refactor B", view.navMenuIntentions.get(0).title);
    }

    @Test
    public void rows_emptySection_isOmitted() throws Exception {
        EditorView view = newView();
        // Seulement une intention — pas de section GO TO ni QUICK FIXES.
        putActions(view, 2, new EditorView.CodeAction("Wrap", "refactor", () -> { }));
        openMenu(view);
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        assertEquals(2, rows.size());
        assertEquals("INTENTIONS", rows.get(0).ref);
        assertEquals(EditorView.NavMenuRow.TYPE_ACTION, rows.get(1).type);
    }

    // ── Navigation (option GO TO) ──────────────────────────────────

    @Test
    public void pickDeclaration_singleSameFileTarget_movesCaret() throws Exception {
        EditorView view = newView();
        int target = DOC.indexOf("greet");
        view.setDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("", target, "greet")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int declRow = rowOfType(rows, EditorView.NavMenuRow.TYPE_OPTION, 0);
        assertTrue(declRow >= 0);
        view.popupManager.navMenuPickOption((NavigationMenu.NavOption) rows.get(declRow).ref);

        assertFalse("le pick referme le menu", view.navMenuVisible);
        assertEquals("la cible mono-fichier déplace le caret",
                target, view.getSession().getSelection().start);
    }

    @Test
    public void pickOption_multipleTargets_switchesToResultsMode() throws Exception {
        EditorView view = newView();
        view.setDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("", 3, "a"),
                new DefinitionLocation("", 30, "b")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int declRow = rowOfType(rows, EditorView.NavMenuRow.TYPE_OPTION, 0);
        view.popupManager.navMenuPickOption((NavigationMenu.NavOption) rows.get(declRow).ref);

        assertTrue("multi-cibles → mode RESULTS (picker)", view.navMenuResultsMode);
        assertTrue(view.navMenuVisible);
        assertEquals(2, view.navMenuTargets.size());

        // Le picker liste les cibles comme rangées TARGET.
        List<EditorView.NavMenuRow> resultRows = view.navMenuRows();
        assertEquals(2, resultRows.size());
        assertEquals(EditorView.NavMenuRow.TYPE_TARGET, resultRows.get(0).type);
        assertEquals("a", ((NavigationMenu.NavTarget) resultRows.get(0).ref).displayName);

        // Picker → pick d'une cible → navigation même-fichier (path vide).
        view.popupManager.navMenuPickTarget(view.navMenuTargets.get(1));
        assertFalse(view.navMenuVisible);
        assertEquals(30, view.getSession().getSelection().start);
    }

    @Test
    public void pickOption_crossFileTarget_delegatesToHost() throws Exception {
        EditorView view = newView();
        final List<DefinitionLocation> received = new ArrayList<>();
        view.setOnDefinitionRequestedListener(received::addAll);
        view.setDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("file:///other/Other.java", 12, "Other")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int declRow = rowOfType(rows, EditorView.NavMenuRow.TYPE_OPTION, 0);
        view.popupManager.navMenuPickOption((NavigationMenu.NavOption) rows.get(declRow).ref);

        assertFalse(view.navMenuVisible);
        assertEquals("la cible cross-file va au listener hôte", 1, received.size());
        assertEquals("file:///other/Other.java", received.get(0).path);
    }

    // ── Actions du menu ────────────────────────────────────────────

    @Test
    public void pickAction_appliesAndDismisses() throws Exception {
        EditorView view = newView();
        final boolean[] applied = {false};
        putActions(view, 2, new EditorView.CodeAction("Fix", "quickfix", () -> applied[0] = true));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int actionRow = rowOfType(rows, EditorView.NavMenuRow.TYPE_ACTION, 0);
        view.popupManager.navMenuPickAction((EditorView.CodeAction) rows.get(actionRow).ref);

        assertFalse(view.navMenuVisible);
        assertTrue("l'action est appliquée", applied[0]);
    }

    // ── Geste : tap rangée / tap ailleurs / drag-scroll ────────────

    @Test
    public void tapOnActionRow_resolvesTheAction() throws Exception {
        EditorView view = newView();
        final boolean[] applied = {false};
        putActions(view, 2, new EditorView.CodeAction("Fix", "quickfix", () -> applied[0] = true));
        openMenu(view);

        // Centre de la rangée d'action (1re rangée ACTION après le header).
        float[] m = view.navMenuMetrics();
        assertNotNull(m);
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = view.NAV_MENU_HEADER_HEIGHT_DP * density;
        // header QUICK FIXES + 1re rangée action.
        float rowCenterY = m[1] + headerH + rowH * 0.5f;
        float rowCenterX = m[0] + m[2] * 0.5f;

        assertEquals("le hit-test résout la rangée ACTION",
                rowOfType(rows, EditorView.NavMenuRow.TYPE_ACTION, 0),
                view.inputHandler.navMenuRowIndexOf(rowCenterX, rowCenterY));

        tap(view, rowCenterX, rowCenterY);
        assertFalse("le tap referme le menu", view.navMenuVisible);
        assertTrue("le tap applique l'action", applied[0]);
    }

    @Test
    public void tapOutside_dismissesMenu() throws Exception {
        EditorView view = newView();
        putActions(view, 2, new EditorView.CodeAction("Fix", "quickfix", () -> { }));
        openMenu(view);
        assertTrue(view.navMenuVisible);

        // Un tap nettement AU-DESSUS du menu (ligne 0).
        float[] pos0 = {GUTTER_W + PAD_LEFT + CHAR_W, PAD_TOP + LINE_H * 0.5f};
        tap(view, pos0[0], pos0[1]);
        assertFalse("tap hors carte → dismiss (onDismissRequest)", view.navMenuVisible);
    }

    @Test
    public void dragScrollsContent_clamped_keepsMenuOpen() throws Exception {
        EditorView view = newView();
        // Beaucoup d'intentions → contenu plus haut que le cap 360dp.
        List<EditorView.CodeAction> many = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            final int idx = i;
            many.add(new EditorView.CodeAction("Intention " + idx, "refactor", () -> { }));
        }
        view.codeActionsByLine.put(2, many);
        openMenu(view);

        float contentH = view.navMenuContentHeight();
        float[] m = view.navMenuMetrics();
        assertNotNull(m);
        assertTrue("14 intentions dépassent le cap de hauteur",
                contentH > m[3]);
        assertEquals(0f, view.navMenuScrollY, 0.01f);

        // Drag vers le HAUT (le doigt monte) → scroll du contenu vers le bas.
        float cx = m[0] + m[2] * 0.5f;
        drag(view, cx, m[1] + m[3] * 0.6f, m[1] + m[3] * 0.2f);
        assertTrue("le drag fait défiler le contenu", view.navMenuScrollY > 0f);
        assertTrue("le menu reste ouvert après le drag", view.navMenuVisible);

        // Sur-scroll au-delà du maximum → clamp.
        drag(view, cx, m[1] + m[3] * 0.2f, m[1] + m[3] * 0.2f - 2000);
        assertTrue("scroll clampé au contenu",
                view.navMenuScrollY <= contentH - m[3] + 0.01f);
    }

    @Test
    public void metrics_anchoredBelowCaretLine_flipsAboveWhenOverflowing()
            throws Exception {
        EditorView view = newView();
        int greet = DOC.indexOf("greet");
        view.getSession().setSelection(greet);
        putActions(view, 2, new EditorView.CodeAction("Fix", "quickfix", () -> { }));
        openMenu(view);

        float[] m = view.navMenuMetrics();
        assertNotNull(m);
        float density = view.getResources().getDisplayMetrics().density;
        float lineBottomY = PAD_TOP + 2 * LINE_H + LINE_H; // ligne 2 + 1 ligne
        // Ancré SOUS la ligne du caret : y ≥ bas de ligne + gap 6dp.
        assertTrue("ancré sous la ligne du caret",
                m[1] >= lineBottomY + view.NAV_MENU_GAP_DP * density - 1f);
        assertTrue("largeur entre 240dp et 320dp",
                m[2] >= view.NAV_MENU_MIN_WIDTH_DP * density - 1f
                        && m[2] <= view.NAV_MENU_MAX_WIDTH_DP * density + 1f);
    }

    @Test
    public void edit_dismissesNavMenu() throws Exception {
        EditorView view = newView();
        putActions(view, 2, new EditorView.CodeAction("Fix", "quickfix", () -> { }));
        openMenu(view);
        assertTrue(view.navMenuVisible);

        view.getSession().commitText("x");
        view.onTextChanged();
        assertFalse("une édition referme le menu", view.navMenuVisible);
    }

    // ── GO TO Implementations / Super ─────────────────────────────

    @Test
    public void rows_gotoOptions_inNavKindOrder() throws Exception {
        EditorView view = newView();
        view.setDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("", 5, "greet")));
        view.setImplementationsResolver((text, offset) -> List.of(
                new DefinitionLocation("", 24, "ImplA  ·  demo")));
        view.setTypeDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("", 0, "Main")));
        view.setSuperResolver((text, offset) -> List.of(
                new DefinitionLocation("", 12, "run  ·  Base")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        // Header GO TO + 4 options dans l'ordre des NavKind.
        assertEquals(5, rows.size());
        assertEquals("GO TO", rows.get(0).ref);
        assertEquals("Declaration",
                ((NavigationMenu.NavOption) rows.get(1).ref).label);
        assertEquals("Implementations",
                ((NavigationMenu.NavOption) rows.get(2).ref).label);
        assertEquals("Type declaration",
                ((NavigationMenu.NavOption) rows.get(3).ref).label);
        assertEquals("Super",
                ((NavigationMenu.NavOption) rows.get(4).ref).label);
        // Render smoke : les icônes layers (Implementations) et pin (Super)
        // doivent dessiner sans crash.
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
    }

    @Test
    public void rows_implementationsAndSuper_empty_areOmitted() throws Exception {
        EditorView view = newView();
        // Resolvers présents mais SANS cibles → options omises (une
        // option n'apparaît que si ≥ 1 cible).
        view.setImplementationsResolver((text, offset) -> List.of());
        view.setSuperResolver((text, offset) -> List.of());
        view.setDefinitionResolver((text, offset) -> List.of(
                new DefinitionLocation("", 5, "greet")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        assertEquals("seul Declaration reste (header inclus)", 2, rows.size());
        assertEquals("Declaration",
                ((NavigationMenu.NavOption) rows.get(1).ref).label);
    }

    @Test
    public void pickImplementations_multipleTargets_resultsModeWithTransportedLabels()
            throws Exception {
        EditorView view = newView();
        // Les providers LSP transportent les libellés serveur
        // (« Simple  ·  pkg ») via displayName — le picker doit les afficher.
        view.setImplementationsResolver((text, offset) -> List.of(
                new DefinitionLocation("", 3, "ImplA  ·  demo"),
                new DefinitionLocation("", 30, "ImplB  ·  demo")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int implRow = rowOfType(rows, EditorView.NavMenuRow.TYPE_OPTION, 0);
        assertEquals("Implementations",
                ((NavigationMenu.NavOption) rows.get(implRow).ref).label);
        view.popupManager.navMenuPickOption(
                (NavigationMenu.NavOption) rows.get(implRow).ref);

        assertTrue("multi-cibles → mode RESULTS (picker)",
                view.navMenuResultsMode);
        List<EditorView.NavMenuRow> resultRows = view.navMenuRows();
        assertEquals(2, resultRows.size());
        assertEquals("ImplA  ·  demo",
                ((NavigationMenu.NavTarget) resultRows.get(0).ref).displayName);
        assertEquals("ImplB  ·  demo",
                ((NavigationMenu.NavTarget) resultRows.get(1).ref).displayName);

        // Picker → pick d'une cible → navigation même-fichier (path vide).
        view.popupManager.navMenuPickTarget(view.navMenuTargets.get(1));
        assertFalse(view.navMenuVisible);
        assertEquals(30, view.getSession().getSelection().start);
    }

    @Test
    public void pickSuper_crossFileTarget_delegatesToHost() throws Exception {
        EditorView view = newView();
        final List<DefinitionLocation> received = new ArrayList<>();
        view.setOnDefinitionRequestedListener(received::addAll);
        view.setSuperResolver((text, offset) -> List.of(
                new DefinitionLocation("file:///project/src/demo/Base.java",
                        42, "run  ·  Base")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int superRow = rowOfType(rows, EditorView.NavMenuRow.TYPE_OPTION, 0);
        assertEquals("Super",
                ((NavigationMenu.NavOption) rows.get(superRow).ref).label);
        view.popupManager.navMenuPickOption(
                (NavigationMenu.NavOption) rows.get(superRow).ref);

        assertFalse("le pick referme le menu", view.navMenuVisible);
        assertEquals("la cible cross-file va au listener hôte", 1, received.size());
        assertEquals("file:///project/src/demo/Base.java",
                received.get(0).path);
        assertEquals("l'offset EXACT du nom est transporté", 42,
                received.get(0).offset);
    }

    @Test
    public void picker_pathAsDisplayName_fallsBackToShortFileName() throws Exception {
        EditorView view = newView();
        // Un displayName qui EST le chemin (le provider definition l'envoie
        // parfois brut) → le libellé picker retombe sur le nom COURT du
        // fichier, plus lisible qu'un « /storage/emulated/0/… » intégral.
        // Deux cibles pour forcer le mode RESULTS (mono-cible cross-file
        // délègue directement au listener hôte).
        view.setSuperResolver((text, offset) -> List.of(
                new DefinitionLocation("/project/src/demo/Base.java", 42,
                        "/project/src/demo/Base.java"),
                new DefinitionLocation("/project/src/demo/IBase.java", 7,
                        "file:///project/src/demo/IBase.java")));
        openMenu(view);

        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        int superRow = rowOfType(rows, EditorView.NavMenuRow.TYPE_OPTION, 0);
        view.popupManager.navMenuPickOption(
                (NavigationMenu.NavOption) rows.get(superRow).ref);

        assertTrue(view.navMenuResultsMode);
        List<EditorView.NavMenuRow> resultRows = view.navMenuRows();
        assertEquals(2, resultRows.size());
        assertEquals("Base.java",
                ((NavigationMenu.NavTarget) resultRows.get(0).ref).displayName);
        assertEquals("IBase.java",
                ((NavigationMenu.NavTarget) resultRows.get(1).ref).displayName);
    }
}

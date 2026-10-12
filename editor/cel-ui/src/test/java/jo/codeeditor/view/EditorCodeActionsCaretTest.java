package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Les actions de code sont RÉSOLUES À LA DEMANDE au point d'invocation :
 *
 * <ul>
 *   <li>une invocation = UNE résolution (le balayage d'une requête par
 *       ligne visible a disparu), plage = caret/sélection transmis au
 *       résolveur ;</li>
 *   <li>le tap sur la bande de plis d'une ligne de diagnostic ouvre le
 *       popup — résolution ancrée au DÉBUT du diagnostic (avant : la
 *       porte exigeait un cache peuplé par le balayage, jamais là) ;</li>
 *   <li>le cache est mono-ligne, remplacé à chaque résolution, vidé à
 *       l'édition ;</li>
 *   <li>l'ampoule ne dépend plus d'actions pré-calculées : portée par les
 *       diagnostics, elle apparaît dès leur publication (gate
 *       resolver + ligne de diagnostic).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern EditorNavMenuTest).</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorCodeActionsCaretTest {

    private static final String DOC =
        "public class A {\n"          // 0
        + "    String s = foo;\n"     // 1  (« foo » aux offsets 32-35)
        + "}\n";                       // 2

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;
    private static final float GUTTER_W = 70f;

    /** Résolveur compteur : enregistre chaque résolution au caret. */
    private static final class Compteur implements EditorView.CodeActionsResolver {
        int appels;
        int dernierCaret = Integer.MIN_VALUE;
        int dernièreSélectionDébut = Integer.MIN_VALUE;
        int dernièreSélectionFin = Integer.MIN_VALUE;
        int réponseActions = 1;

        @Override
        public List<EditorView.CodeAction> resolve(String text, int line) {
            appels++;
            return actions();
        }

        @Override
        public List<EditorView.CodeAction> resolveAtCaret(String text, int caretOffset,
                int selectionStart, int selectionEnd) {
            appels++;
            dernierCaret = caretOffset;
            dernièreSélectionDébut = selectionStart;
            dernièreSélectionFin = selectionEnd;
            return actions();
        }

        private List<EditorView.CodeAction> actions() {
            List<EditorView.CodeAction> out = new ArrayList<>();
            for (int i = 0; i < réponseActions; i++) {
                out.add(new EditorView.CodeAction("Action " + i, "quickfix", () -> { }));
            }
            return out;
        }
    }

    private EditorView newView(Compteur compteur) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        view.setCodeActionsResolver(compteur);
        injectMetrics(view);
        return view;
    }

    private static void injectMetrics(EditorView view) {
        try {
            java.lang.reflect.Field f;
            f = view.metrics.getClass().getDeclaredField("lineHeight");
            f.setAccessible(true); f.setFloat(view.metrics, LINE_H);
            f = view.metrics.getClass().getDeclaredField("charWidth");
            f.setAccessible(true); f.setFloat(view.metrics, CHAR_W);
            f = view.metrics.getClass().getDeclaredField("padTop");
            f.setAccessible(true); f.setFloat(view.metrics, PAD_TOP);
            f = view.metrics.getClass().getDeclaredField("gutterWidth");
            f.setAccessible(true); f.setFloat(view.metrics, GUTTER_W);
            f = view.metrics.getClass().getDeclaredField("foldStripWidth");
            f.setAccessible(true); f.setFloat(view.metrics, CHAR_W * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Simule un tap complet (DOWN + UP) au même endroit. */
    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    /** Attend l'ouverture async du popup (voie « features »). */
    private static boolean awaitPopup(EditorView view, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (view.codeActionsPopupVisible) return true;
            Thread.sleep(20);
        }
        return view.codeActionsPopupVisible;
    }

    @Test
    public void invocationAuCaret_uneSeuleRésolution() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        int foo = DOC.indexOf("foo");

        view.popupManager.resolveCodeActionsAt(foo, foo, foo, true);

        assertTrue("le popup doit s'ouvrir après résolution (async)",
                awaitPopup(view, 5000));
        assertEquals("une invocation = UNE résolution", 1, compteur.appels);
        assertEquals(foo, compteur.dernierCaret);
        assertEquals(1, view.codeActionsPopupLine);
        assertEquals(1, view.codeActionsByLine.size());
        assertEquals(1, view.codeActionsByLine.get(1).size());
    }

    @Test
    public void sélectionTransmiseAuRésolveur() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        int foo = DOC.indexOf("foo");

        view.popupManager.resolveCodeActionsAt(foo, foo, foo + 7, true);
        awaitPopup(view, 5000);

        assertEquals(foo, compteur.dernièreSélectionDébut);
        assertEquals(foo + 7, compteur.dernièreSélectionFin);
    }

    @Test
    public void tapBandeDePlis_ligneDeDiagnostic_résolutionAncreAuDiagnostic()
            throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        // Diagnostic erreur couvrant « foo » (ligne 1).
        int foo = DOC.indexOf("foo");
        view.getSession().setDiagnostics(new ArrayList<>(List.of(
                new DiagnosticShift.Diagnostic(foo, foo + 3, 3, "foo inconnu"))));

        // Tap au centre de la bande de plis de la ligne 1.
        float x = GUTTER_W - CHAR_W;
        float y = PAD_TOP + LINE_H * 1.5f;
        tap(view, x, y);

        assertTrue("le tap ampoule doit ouvrir le popup (résolution à la demande)",
                awaitPopup(view, 5000));
        assertEquals(1, compteur.appels);
        assertEquals("l'ancre est le début du diagnostic de la ligne",
                foo, compteur.dernierCaret);
    }

    @Test
    public void résolutionSansActions_popupSilencieuxCacheVide() throws Exception {
        Compteur compteur = new Compteur();
        compteur.réponseActions = 0;
        EditorView view = newView(compteur);
        int foo = DOC.indexOf("foo");

        view.popupManager.resolveCodeActionsAt(foo, foo, foo, true);
        // Attends la fin de la tâche (borne), le popup ne doit PAS s'ouvrir.
        long fin = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < fin && compteur.appels == 0) {
            Thread.sleep(20);
        }
        assertEquals(1, compteur.appels);
        assertFalse(view.codeActionsPopupVisible);
        assertTrue(view.codeActionsByLine.isEmpty());
    }

    @Test
    public void cacheMonoLigne_remplacéÀChaqueRésolution() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        view.popupManager.resolveCodeActionsAt(DOC.indexOf("foo"), DOC.indexOf("foo"),
                DOC.indexOf("foo"), true);
        awaitPopup(view, 5000);
        assertEquals(1, view.codeActionsByLine.size());

        // Deuxième invocation, ligne 2 : l'entrée précédente est REMPLACÉE.
        int fin = DOC.length() - 2;  // le « } » de la ligne 2
        view.popupManager.resolveCodeActionsAt(fin, fin, fin, true);
        long borne = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < borne && compteur.appels < 2) {
            Thread.sleep(20);
        }
        assertEquals(2, compteur.appels);
        assertTrue("le cache reste mono-ligne",
                view.codeActionsByLine.size() <= 1);
        assertFalse("l'entrée ligne 1 est partie", view.codeActionsByLine.containsKey(1));
    }

    @Test
    public void éditionVideLeCache() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        view.popupManager.resolveCodeActionsAt(DOC.indexOf("foo"), DOC.indexOf("foo"),
                DOC.indexOf("foo"), true);
        awaitPopup(view, 5000);
        assertFalse(view.codeActionsByLine.isEmpty());

        view.notifyTextChanged();
        assertTrue("le cache ne survit pas à l'édition", view.codeActionsByLine.isEmpty());
    }

    @Test
    public void menuContextuel_résolutionÀLaDemandeQuandCacheAbsent() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        int foo = DOC.indexOf("foo");

        view.showNavMenu(1, foo);
        long fin = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < fin && !view.navMenuVisible) {
            Thread.sleep(20);
        }
        assertTrue("le menu doit s'ouvrir (async)", view.navMenuVisible);
        assertEquals("quick-fixes du menu résolus à la demande (cache absent)",
                1, compteur.appels);
        assertEquals(foo, compteur.dernierCaret);
        assertEquals(1, view.navMenuQuickFixes.size());
    }
}

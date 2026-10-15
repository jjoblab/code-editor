package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * S1 (P1) — verrouille le <b>cache de détection sticky</b> :
 *
 * <ul>
 *   <li><b>Une frame ne paie qu'une fois</b> — {@code detect} appelé
 *       sur N frames consécutives (le renderer l'invoque à chaque
 *       passe de dessin) ne déclenche qu'<b>un seul scan</b> du
 *       document : le scroll ne change jamais la liste des
 *       en-têtes ;</li>
 *   <li><b>Une édition invalide</b> — le document est immuable et
 *       chaque édition produit une nouvelle révision : le scan
 *       repart et reflète le nouveau contenu ;</li>
 *   <li><b>Un autre document re-scanne</b> — la clé du cache porte
 *       l'instance du document (immuable), pas seulement la paire
 *       [révision, lineCount] : deux fichiers différents ne partagent
 *       jamais une entrée de cache ;</li>
 *   <li><b>Un changement de langue re-scanne</b> — la famille
 *       (C_LIKE / PYTHON) décide de la détection : changer de langue
 *       invalide, même document ;</li>
 *   <li><b>Les yOffset sont rafraîchis sans re-scan</b> — le hit de
 *       cache recalcule les positions Y (qui dépendent des plis, du
 *       wrap et des métriques) en place, sans allouer.</li>
 * </ul>
 *
 * <p>Instrumentation : le compteur package-private
 * {@code view.stickyLineDetector.scanCount} compte les scans complets
 * (les « miss » du cache) — c'est lui qui fait foi pour « une frame
 * ne paie qu'une fois ».</p>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class StickyDetectCachedTest {

    private static final String DOC =
        "public class Main {\n"          // 0 — sticky (classe)
        + "    void run() {\n"            // 1 — sticky (méthode)
        + "        int x = 1;\n"          // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final String VOID_RUN = "    void run() {";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView(String text) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(text));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    @Test
    public void detect_sur_n_frames_ne_scanne_quune_fois() {
        EditorView view = newView(DOC);
        EditorDocument doc = view.getSession().getDocument();
        // 5 « frames » consécutives : le renderer appelle detect à
        // chaque passe de dessin, le scroll ne change pas la liste.
        for (int frame = 0; frame < 5; frame++) {
            List<int[]> sticky = view.detectStickyLines(doc);
            assertEquals("les 2 en-têtes (classe + méthode) à chaque frame",
                    2, sticky.size());
            assertEquals(0, sticky.get(0)[0]);
            assertEquals(1, sticky.get(1)[0]);
        }
        assertEquals("un seul scan pour 5 frames", 1,
                view.stickyLineDetector.scanCount);
    }

    @Test
    public void une_edition_invalide_et_rescanne() {
        EditorView view = newView(DOC);
        EditorSession session = view.getSession();
        int rev0 = session.getDocument().getRevision();
        view.detectStickyLines(session.getDocument());
        assertEquals(1, view.stickyLineDetector.scanCount);

        // La ligne 1 cesse d'être un en-tête : « void run() { » → « x = 2; ».
        int start = DOC.indexOf(VOID_RUN);
        session.replaceRange(start, start + VOID_RUN.length(), "    x = 2;");
        EditorDocument doc2 = session.getDocument();
        assertTrue("l'édition produit une nouvelle révision",
                doc2.getRevision() > rev0);

        List<int[]> sticky = view.detectStickyLines(doc2);
        assertEquals("l'édition invalide le cache → re-scan", 2,
                view.stickyLineDetector.scanCount);
        assertEquals("seule la classe reste un en-tête", 1, sticky.size());
        assertEquals(0, sticky.get(0)[0]);
    }

    @Test
    public void un_autre_document_rescanne() {
        EditorView view = newView(DOC);
        view.detectStickyLines(view.getSession().getDocument());
        assertEquals(1, view.stickyLineDetector.scanCount);

        // Même longueur de liste attendue mais AUTRE fichier : la clé
        // porte l'instance du document, le miss est obligatoire.
        String autre = "class B {\n    int m() {\n        return 0;\n    }\n}\n";
        EditorSession session2 = new EditorSession(EditorDocument.of(autre));
        session2.setLanguage("java");
        view.setSession(session2);

        List<int[]> sticky = view.detectStickyLines(session2.getDocument());
        assertEquals("autre document → re-scan", 2,
                view.stickyLineDetector.scanCount);
        assertEquals(0, sticky.get(0)[0]);
        assertEquals(1, sticky.get(1)[0]);
    }

    @Test
    public void un_changement_de_langue_rescanne() {
        EditorView view = newView(DOC);
        EditorDocument doc = view.getSession().getDocument();
        view.detectStickyLines(doc);
        assertEquals(1, view.stickyLineDetector.scanCount);

        // Le même document n'a AUCUN en-tête en famille Python
        // (aucune ligne ne se termine par « : ») : changer de langue
        // change la détection → le cache doit être invalidé.
        view.getSession().setLanguage("python");
        List<int[]> sticky = view.detectStickyLines(doc);
        assertEquals("changement de langue → re-scan", 2,
                view.stickyLineDetector.scanCount);
        assertTrue("aucun en-tête sticky en Python sur ce document",
                sticky.isEmpty());
    }

    @Test
    public void hit_de_cache_rafraichit_les_y_sans_rescan() {
        EditorView view = newView(DOC);
        EditorDocument doc = view.getSession().getDocument();
        List<int[]> avant = view.detectStickyLines(doc);
        // padTop = lineHeight * 0.5 → 20f avec LINE_H = 40f.
        assertEquals(20f, (float) avant.get(0)[1], 0.01f);

        // Nouvelles métriques (zoom) : la géométrie change, pas le
        // contenu — le hit de cache doit rafraîchir les yOffset.
        injectMetrics(view, 60f, CHAR_W);
        List<int[]> apres = view.detectStickyLines(doc);
        assertEquals("aucun re-scan sur changement de métriques", 1,
                view.stickyLineDetector.scanCount);
        assertEquals("yOffset rafraîchi (padTop = 60 * 0.5)", 30f,
                (float) apres.get(0)[1], 0.01f);
    }
}

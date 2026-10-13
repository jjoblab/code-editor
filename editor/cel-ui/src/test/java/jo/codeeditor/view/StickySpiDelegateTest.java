package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.languages.FoldStrategy;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * S5 (P2, lot 2 jalon J6) — verrouille la <b>délégation de la
 * détection sticky au SPI langue</b> : la question « cette ligne
 * ouvre-t-elle un bloc ? » appartient au {@link LanguageProfile}, pas
 * au détecteur.
 *
 * <ul>
 *   <li><b>Langue factice</b> — un profil hors familles C_LIKE/Python
 *       (MARKDOWN) déclarant SES en-têtes via le SPI les voit coller :
 *       la détection n'est plus enfermée dans les tables internes ;</li>
 *   <li><b>C_LIKE inchangé</b> — sans SPI, les tables par défaut
 *       (accolade + mot-clé de définition) continuent de s'appliquer ;</li>
 *   <li><b>Python inchangé</b> — deux-points + mot-clé de définition ;</li>
 *   <li><b>Famille sans en-têtes</b> — LOG sans SPI n'a AUCUNE ligne
 *       sticky (et ne scanne même pas : l'absence d'en-têtes est
 *       déclarée, pas constatée ligne à ligne).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code StickyDetectCachedTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class StickySpiDelegateTest {

    private static final String DOC_FAKE =
        "SECTION Alpha [\n"                // 0 — sticky (SPI factice)
        + "    line one\n"                  // 1
        + "SECTION Beta [\n"                // 2 — sticky (SPI factice)
        + "    line two\n"                  // 3
        + "]\n";                            // 4

    private static final String DOC_JAVA =
        "public class Main {\n"             // 0 — sticky (défaut C_LIKE)
        + "    void run() {\n"              // 1 — sticky (défaut C_LIKE)
        + "        int x = 1;\n"            // 2
        + "    }\n"                         // 3
        + "}\n";                            // 4

    private static final String DOC_PY =
        "def foo():\n"                      // 0 — sticky (défaut Python)
        + "    return 1\n"                  // 1
        + "class Bar:\n"                    // 2 — sticky (défaut Python)
        + "    pass\n";                     // 3

    private static final String DOC_LOG =
        "2026-10-13 INFO started\n"
        + "2026-10-13 WARN slow query\n";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView(String text, String language) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(text));
        view.setSession(session);
        session.setLanguage(language);
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

    /** Enregistre une langue factice hors C_LIKE/Python avec ses propres
     *  en-têtes (retourne le profil pour désinscription). */
    private LanguageProfile registerFakeSections() {
        LanguageProfile fake = LanguageProfile.builder("fakesections")
                .family(SyntaxFamily.MARKDOWN)
                .foldStrategy(FoldStrategy.OFF)
                // S5 : la langue décide SEULE de ses en-têtes — ici une
                // section est une ligne « SECTION … [ ».
                .stickyHeaderTest(line -> {
                    String t = line.toString().trim();
                    return t.startsWith("SECTION ") && t.endsWith("[");
                })
                .build();
        LanguageRegistry.register(fake);
        return fake;
    }

    @Test
    public void langueFactice_avecEnTêtesPropres_lesVoitColler() {
        LanguageProfile fake = registerFakeSections();
        try {
            EditorView view = newView(DOC_FAKE, "fakesections");
            List<int[]> sticky = view.detectStickyLines(
                    view.getSession().getDocument());
            assertEquals("les 2 sections déclarées par le SPI collent", 2,
                    sticky.size());
            assertEquals(0, sticky.get(0)[0]);
            assertEquals(2, sticky.get(1)[0]);
        } finally {
            LanguageRegistry.unregister("fakesections");
        }
    }

    @Test
    public void c_like_défaut_inchangé() {
        EditorView view = newView(DOC_JAVA, "java");
        List<int[]> sticky = view.detectStickyLines(
                view.getSession().getDocument());
        assertEquals("classe + méthode : les tables par défaut tiennent", 2,
                sticky.size());
        assertEquals(0, sticky.get(0)[0]);
        assertEquals(1, sticky.get(1)[0]);
    }

    @Test
    public void python_défaut_inchangé() {
        EditorView view = newView(DOC_PY, "python");
        List<int[]> sticky = view.detectStickyLines(
                view.getSession().getDocument());
        assertEquals("def + class : les tables par défaut tiennent", 2,
                sticky.size());
        assertEquals(0, sticky.get(0)[0]);
        assertEquals(2, sticky.get(1)[0]);
    }

    @Test
    public void familleSansEnTêtes_sansSPI_aucunSticky() {
        EditorView view = newView(DOC_LOG, "log");
        List<int[]> sticky = view.detectStickyLines(
                view.getSession().getDocument());
        assertTrue("LOG sans SPI : aucune ligne sticky", sticky.isEmpty());
    }

    @Test
    public void langueFactice_sansEnTêteDéclaré_neCollePas() {
        LanguageProfile fake = LanguageProfile.builder("fakesections")
                .family(SyntaxFamily.MARKDOWN)
                .foldStrategy(FoldStrategy.OFF)
                // Le SPI existe mais ne reconnaît RIEN dans ce document.
                .stickyHeaderTest(line -> false)
                .build();
        LanguageRegistry.register(fake);
        try {
            EditorView view = newView(DOC_FAKE, "fakesections");
            List<int[]> sticky = view.detectStickyLines(
                    view.getSession().getDocument());
            assertTrue("le SPI dit non → aucune ligne sticky", sticky.isEmpty());
        } finally {
            LanguageRegistry.unregister("fakesections");
        }
    }
}

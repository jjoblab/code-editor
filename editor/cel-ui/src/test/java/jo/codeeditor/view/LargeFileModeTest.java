package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.find.Match;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * MODE GROS FICHIERS EXPLICITE (4.5.0) : le régime des fonctions
 * lourdes est un tri-état public — AUTO (défaut : les seuils du
 * document décident), ON (forçage des coupures), OFF (coupures
 * fermées même au-delà des seuils). Sont coupées la complétion
 * AUTO-DÉCLENCHÉE (l'entrée explicite hôte reste ouverte), la
 * surbrillance d'occurrences, les inlays et le tir sémantique ; la
 * coloration, la frappe et l'undo continuent toujours.
 */
@RunWith(RobolectricTestRunner.class)
public class LargeFileModeTest {

    /** Document au-delà du seuil de lignes (50 000), caret posé sur un
     * mot en fin de document (préfixe non vide — un caret après un
     * retour à la ligne fermerait le popup par préfixe vide). */
    private static String grosDocument() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= 50_000; i++) {
            sb.append("ligne ").append(i).append('\n');
        }
        sb.append("motcle");
        return sb.toString();
    }

    private EditorView newView(String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setSelection(doc.length());
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /** Un item de fournisseur qui APPARIE le préfixe du gros document. */
    private static java.util.List<CompletionSession.Item> itemMotcle() {
        return Collections.singletonList(
                new CompletionSession.Item("motcleLong", "", "motcleLong", "m", 2, 0, false, false));
    }

    private static void awaitPopup(EditorView view) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (view.completion.items.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                fail("le popup n'a reçu aucun item dans le délai imparti");
            }
            Thread.sleep(20);
        }
        Thread.sleep(60);
    }

    private static void attendreAucunItem(EditorView view) throws InterruptedException {
        // Laisse le debounce + la lane s'exprimer — rien ne doit arriver.
        Thread.sleep(400);
    }

    @Test
    public void défaut_AUTO_petitDocument_inactif() {
        EditorView view = newView("petit");
        assertEquals(EditorView.LargeFileMode.AUTO, view.getLargeFileMode());
        assertFalse(view.isLargeFileModeActive());
    }

    @Test
    public void auto_grosDocument_actif_complétionCoupée() {
        EditorView view = newView(grosDocument());
        assertTrue("précondition : le document dépasse les seuils",
                view.getSession().getDocument().isLarge());
        assertTrue(view.isLargeFileModeActive());

        // La complétion auto-déclenchée ne part PAS — aucun item, le
        // popup reste fermé (l'item fourni APPARIE le préfixe : seule
        // la coupure peut l'écarter).
        view.setCompletionProvider((text, caret, tokenStart, prefix) -> itemMotcle());
        view.refreshCompletion();
        try {
            attendreAucunItem(view);
        } catch (InterruptedException e) {
            fail("interruption inattendue");
        }
        assertTrue("aucun item livré en mode gros fichiers",
                view.completion.items.isEmpty());
        assertFalse(view.isCompletionVisible());
    }

    @Test
    public void forçageON_petitDocument_coupesActives() {
        EditorView view = newView("petit");
        view.setLargeFileMode(EditorView.LargeFileMode.ON);
        assertTrue(view.isLargeFileModeActive());

        view.setCompletionProvider((text, caret, tokenStart, prefix) -> itemMotcle());
        view.refreshCompletion();
        try {
            attendreAucunItem(view);
        } catch (InterruptedException e) {
            fail("interruption inattendue");
        }
        assertTrue(view.completion.items.isEmpty());
    }

    @Test
    public void forçageOFF_grosDocument_coupesFermées() {
        EditorView view = newView(grosDocument());
        // Le document dépasse les seuils, mais l'hôte assume le coût.
        view.setLargeFileMode(EditorView.LargeFileMode.OFF);
        assertFalse(view.isLargeFileModeActive());

        view.setCompletionProvider((text, caret, tokenStart, prefix) -> itemMotcle());
        view.refreshCompletion();
        try {
            awaitPopup(view);
        } catch (InterruptedException e) {
            fail("interruption inattendue");
        }
        assertFalse(view.completion.items.isEmpty());
    }

    @Test
    public void entréeExpliciteHôte_resteOuverteEnModeON() {
        // La complétion AUTO est coupée, mais la voie EXPLICITE de
        // l'hôte (setCompletionItems + acceptation) continue de
        // fonctionner — un hôte peut offrir sa propre complétion.
        EditorView view = newView("petit");
        view.setLargeFileMode(EditorView.LargeFileMode.ON);
        view.setCompletionItems(Collections.singletonList(
                new CompletionSession.Item("alpha", "", "alpha", "m", 2, 0, false, false)),
                0, "");
        assertTrue(view.isCompletionVisible());
        assertTrue(view.completionAccept());
        assertEquals("alpha", view.session.getText());
    }

    @Test
    public void basculeON_purgeLaSurbrillanceEnCours() {
        EditorView view = newView("mot mot mot");
        List<Match> matches = new ArrayList<>();
        matches.add(new Match(0, 3));
        matches.add(new Match(4, 7));
        view.setFindHighlights(matches, 0);
        assertEquals(2, view.find.highlights.size());

        view.setLargeFileMode(EditorView.LargeFileMode.ON);
        assertTrue(view.find.highlights.isEmpty());
    }

    @Test
    public void occurrences_coupéesParLeForçage() {
        // Sur un PETIT document, les occurrences s'allument au repos de
        // frappe ; forçage ON → la voie est purgée et le producteur ne
        // repousse plus. Le looper shadow exécute le débounce de 300 ms.
        EditorView view = newView("mot mot mot");
        view.session.setSelection(1);
        view.scheduleWordOccurrences();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(450, TimeUnit.MILLISECONDS);
        assertFalse("précondition : occurrences allumées", view.find.highlights.isEmpty());

        view.setLargeFileMode(EditorView.LargeFileMode.ON);
        assertTrue(view.find.highlights.isEmpty());
    }

    @Test
    public void null_traitéCommeAUTO() {
        EditorView view = newView("petit");
        view.setLargeFileMode(null);
        assertEquals(EditorView.LargeFileMode.AUTO, view.getLargeFileMode());
        assertFalse(view.isLargeFileModeActive());
    }
}

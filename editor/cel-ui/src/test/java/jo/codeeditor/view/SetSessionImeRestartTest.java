package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowInputMethodManager;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * R1 (plan 09, E1) — verrouille la purge de l'état IME au changement de
 * session ({@code setSession}) : le miroir du clavier décrit l'ANCIEN
 * document (texte extrait, sélection, composition) — sans purge,
 * l'IME rejoue son miroir périmé ou réveille une région fantôme.
 *
 * <ul>
 *   <li>la GÉNÉRATION s'incrémente : toute InputConnection vivante
 *       devient muette ;</li>
 *   <li>le JETON D'EXTRACTION est invalidé (plus de updateExtractedText
 *       vers un moniteur mort) ;</li>
 *   <li>le MODE ANCRE est raz (CursorAnchorInfo se réarme à la
 *       demande) ;</li>
 *   <li>{@code restartInput} est demandé quand une connexion a déjà
 *       servi — jamais vers un clavier jamais attaché.</li>
 * </ul>
 *
 * <p>Le compteur de restarts vient d'un shadow maison qui enveloppe
 * celui de Robolectric (le shadow stock n'expose pas ce compteur).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SetSessionImeRestartTest {

    /** Compteur statique posé par le shadow (remis à zéro par test). */
    public static int restartCount = 0;

    /** Shadow comptant les restartInput (enveloppe le shadow stock). */
    @org.robolectric.annotation.Implements(InputMethodManager.class)
    public static class CountingImmShadow extends ShadowInputMethodManager {
        @Override
        protected void restartInput(View view) {
            restartCount++;
            super.restartInput(view);
        }
    }

    private static EditorView newViewWithSession(Context ctx, String text) {
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(text)));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    // ── Purge complète quand une connexion a servi ─────────────────

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void changementDeSession_avecConnexionVivante_purgeEtRestart() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "premier fichier");

        // Connexion vivante : le clavier s'est attaché (génération 1).
        EditorInfo info = new EditorInfo();
        InputConnection ic = view.onCreateInputConnection(info);
        int generationBefore = view.connectionGeneration;
        assertTrue("la création de connexion a servi au moins une fois",
                generationBefore >= 1);

        // État IME armé : moniteur d'extraction + mode ancre.
        view.extractedTextMonitorToken = 42;
        view.cursorAnchorMonitorMode = 3;
        assertEquals(42, view.extractedTextMonitorToken);
        assertEquals(3, view.cursorAnchorMonitorMode);

        restartCount = 0;
        // Bascule d'onglet (composition périmée du premier fichier).
        view.setSession(new EditorSession(EditorDocument.of("second fichier")));

        assertEquals("la génération s'incrémente (la vieille connexion devient muette)",
                generationBefore + 1, view.connectionGeneration);
        assertEquals("le jeton d'extraction est invalidé",
                -1, view.extractedTextMonitorToken);
        assertEquals("le mode ancre est raz",
                0, view.cursorAnchorMonitorMode);
        assertEquals("restartInput est demandé (le clavier reconstruit son miroir)",
                1, restartCount);
    }

    // ── Pas d'appel fantôme vers un clavier jamais attaché ─────────

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void changementDeSession_sansJamaisDeConnexion_pasDeRestart() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "premier fichier");
        // Aucun onCreateInputConnection : génération 0, pas de clavier.

        restartCount = 0;
        view.setSession(new EditorSession(EditorDocument.of("second fichier")));

        assertEquals("génération inchangée : aucune connexion n'a jamais servi",
                0, view.connectionGeneration);
        assertEquals("aucun restartInput vers un clavier jamais attaché",
                0, restartCount);
    }

    // ── Miroir de l'ancienne connexion : muette après la bascule ───

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void closeConnectionDeLaVieilleConnexion_neRessuscitePasLeJeton() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "premier fichier");

        EditorInfo info = new EditorInfo();
        InputConnection ic = view.onCreateInputConnection(info);
        view.extractedTextMonitorToken = 7;

        view.setSession(new EditorSession(EditorDocument.of("second fichier")));
        assertEquals(-1, view.extractedTextMonitorToken);

        // La vieille connexion se referme APRÈS la bascule : sa garde de
        // génération (connectionGeneration == generation) est fausse —
        // elle ne doit PAS ressusciter un jeton sur le nouveau document.
        ic.closeConnection();
        assertEquals("la vieille connexion ne remet aucun jeton",
                -1, view.extractedTextMonitorToken);
    }
}

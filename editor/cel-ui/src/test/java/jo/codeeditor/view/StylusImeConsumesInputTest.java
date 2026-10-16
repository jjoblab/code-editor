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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R5 (plan 09, E5) — le protocole d'écriture manuscrite au stylet
 * ({@code setImeConsumesInput}, API 34) : sans retour positif, la saisie
 * au stylet sur la zone d'édition restait un simple geste.
 *
 * <p>Contrat de la plateforme : pendant que l'IME consomme l'entrée
 * (l'utilisateur écrit au stylet), l'éditeur CACHE son caret (il ne
 * reçoit plus les frappes) ; au RETOUR, le caret revit — à sa visibilité
 * d'avant-consommation — et l'état IME est ré-invalidé via
 * {@code InputMethodManager.invalidateInput} (le protocole léger API 33
 * appairé à {@code takeSnapshot} : le système re-capture l'état d'édition
 * à la demande, sans restartInput complet).</p>
 *
 * <p>Le compteur d'invalidations vient d'un shadow maison qui enveloppe
 * celui de Robolectric (le shadow stock n'expose pas ce compteur).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StylusImeConsumesInputTest {

    /** Compteur statique posé par le shadow (remis à zéro par test). */
    public static int invalidateCount = 0;

    /** Shadow comptant les invalidateInput (méthode API 33 non shadowée en stock). */
    @org.robolectric.annotation.Implements(InputMethodManager.class)
    public static class InvalidateCountingImmShadow extends ShadowInputMethodManager {
        @org.robolectric.annotation.Implementation
        protected void invalidateInput(View view) {
            invalidateCount++;
        }
    }

    private EditorView newViewWithSession(Context ctx, String text) {
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(text)));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test
    @Config(shadows = InvalidateCountingImmShadow.class, sdk = 34)
    public void consommation_requiseEtAcceptee_caretCache() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "abc");
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());
        assertTrue(view.caretVisible);

        assertTrue("la requête du protocole stylet est acceptée (retour positif)",
                ic.setImeConsumesInput(true));
        assertFalse("le caret est caché pendant la consommation (contrat API 34)",
                view.caretVisible);
    }

    @Test
    @Config(shadows = InvalidateCountingImmShadow.class, sdk = 34)
    public void retourDeConsommation_caretRevitEtEtatInvalide() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "abc");
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        ic.setImeConsumesInput(true);
        invalidateCount = 0;
        ic.setImeConsumesInput(false);

        assertTrue("le caret revit au retour de l'IME", view.caretVisible);
        assertEquals("l'état IME est ré-invalidé (protocole léger takeSnapshot)",
                1, invalidateCount);
    }

    @Test
    @Config(shadows = InvalidateCountingImmShadow.class, sdk = 34)
    public void retour_visibiliteDAvantConsommationRestauree() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "abc");
        // L'hôte avait caché le caret AVANT l'écriture au stylet (ex : vue
        // quasi decorative) — le retour ne doit pas le réveiller.
        view.setCaretVisible(false);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        ic.setImeConsumesInput(true);
        assertFalse(view.caretVisible);
        ic.setImeConsumesInput(false);

        assertFalse("la visibilité d'avant-consommation (faux) est restaurée",
                view.caretVisible);
    }

    @Test
    @Config(shadows = InvalidateCountingImmShadow.class, sdk = 34)
    public void requeteIdempotente_neReinvalidePasEnBoucle() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "abc");
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        invalidateCount = 0;
        ic.setImeConsumesInput(true);
        ic.setImeConsumesInput(true); // re-requête identique
        assertEquals("aucune invalidation pendant la consommation", 0, invalidateCount);
        ic.setImeConsumesInput(false);
        ic.setImeConsumesInput(false); // re-requête identique
        assertEquals("une seule invalidation au premier retour", 1, invalidateCount);
    }
}

package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;

/**
 * R6 (plan 09, E6) — la fenêtre d'extraction honore le
 * {@code hintMaxChars} de la requête : certains IME demandent exprès de
 * petites fenêtres ({@code getExtractedText(ExtractedTextRequest)}) et
 * payaient sinon le prix du peloton complet (100 000 caractères copiés à
 * chaque mise à jour du moniteur).
 *
 * <p>La fenêtre demandée est mémorisée : les mises à jour ULTÉRIEURES du
 * moniteur ({@code updateExtractedText}) gardent la même taille — le
 * miroir de l'IME reste à la taille qu'il a demandée. La purge de
 * changement de session la réinitialise (le moniteur meurt avec le
 * jeton, R1).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ExtractedTextHintMaxCharsTest {

    private static String bigDoc() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3_000; i++) {
            sb.append("the quick brown fox jumps over the lazy dog\n"); // 45 car.
        }
        return sb.toString(); // 135 000 caractères > plafond 100 000
    }

    private EditorView newViewAt(Context ctx, String text, int caret) {
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(text));
        session.setSelection(caret);
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test
    public void hintMaxCharsPetit_fenetreDeLaTailleDemandee() {
        Context ctx = RuntimeEnvironment.getApplication();
        String doc = bigDoc();
        int caret = doc.length() / 2; // 67 500 — loin des bords
        EditorView view = newViewAt(ctx, doc, caret);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        ExtractedTextRequest req = new ExtractedTextRequest();
        req.token = 7;
        req.hintMaxChars = 100;
        ExtractedText et = ic.getExtractedText(req, 0);

        assertEquals("la fenêtre répond au hint de l'IME (100 caractères)",
                100, et.text.length());
        assertEquals("fenêtre centrée sur le caret",
                caret - 50, et.startOffset);
        assertEquals("sélection relative à la fenêtre",
                50, et.selectionStart);
    }

    @Test
    public void hintNul_fenetreAuPlafondMaison() {
        Context ctx = RuntimeEnvironment.getApplication();
        String doc = bigDoc();
        EditorView view = newViewAt(ctx, doc, doc.length() / 2);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        ExtractedTextRequest req = new ExtractedTextRequest();
        req.token = 8; // hintMaxChars = 0 : pas d'indication
        ExtractedText et = ic.getExtractedText(req, 0);

        assertEquals("sans hint : plafond maison (100 000, sûr pour le Binder)",
                EditorView.MAX_EXTRACT_CHARS, et.text.length());
    }

    @Test
    public void hintDeborde_lePlafondMaisonBorneLaRequete() {
        Context ctx = RuntimeEnvironment.getApplication();
        String doc = bigDoc();
        EditorView view = newViewAt(ctx, doc, doc.length() / 2);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        ExtractedTextRequest req = new ExtractedTextRequest();
        req.token = 9;
        req.hintMaxChars = 10_000_000; // un IME glouton
        ExtractedText et = ic.getExtractedText(req, 0);

        assertEquals("le plafond maison borne le hint délirant",
                EditorView.MAX_EXTRACT_CHARS, et.text.length());
    }

    @Test
    public void leMoniteurGardeLaTailleDemandee_apresEdition() {
        Context ctx = RuntimeEnvironment.getApplication();
        String doc = bigDoc();
        int caret = doc.length() / 2;
        EditorView view = newViewAt(ctx, doc, caret);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        ExtractedTextRequest req = new ExtractedTextRequest();
        req.token = 10;
        req.hintMaxChars = 200;
        ic.getExtractedText(req, 0);

        // Une édition arrive (frappe) : le push updateExtractedText
        // reconstruit la fenêtre via buildExtractedText — même taille.
        ExtractedText et = view.imeBridge.buildExtractedText();
        assertEquals("les mises à jour du moniteur gardent la taille demandée",
                200, et.text.length());
        assertEquals(200, view.extractedTextMaxChars);
    }

    @Test
    public void purgeDeSession_laFenetreDemandeeMeurtAvecLeMoniteur() {
        Context ctx = RuntimeEnvironment.getApplication();
        String doc = bigDoc();
        EditorView view = newViewAt(ctx, doc, doc.length() / 2);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.token = 11;
        req.hintMaxChars = 300;
        ic.getExtractedText(req, 0);
        assertEquals(300, view.extractedTextMaxChars);

        // Bascule d'onglet (R1) : le moniteur ET la fenêtre demandée meurent.
        view.setSession(new EditorSession(EditorDocument.of("second fichier")));

        assertEquals("la purge réinitialise la fenêtre au plafond maison",
                EditorView.MAX_EXTRACT_CHARS, view.extractedTextMaxChars);
    }
}

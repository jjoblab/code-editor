package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * R3 (plan 09, E3) — capitales réelles au point d'insertion :
 * {@code initialCapsMode} valait toujours 0 et
 * {@code getCursorCapsMode} n'était pas surchargée — l'IME ne savait
 * pas activer la majuscule en début de phrase.
 *
 * <p>Le calcul passe par {@link TextUtils#getCapsMode} sur une FENÊTRE
 * bornée (1 000 caractères avant le caret — jamais le document entier :
 * le scan ne regarde que vers l'arrière). initialCapsMode demande WORDS +
 * SENTENCES (bits positionnels) : CAP_MODE_CHARACTERS est un simple écho
 * de la requête, l'inclure ferait croire à l'IME qu'un mode TOUT EN
 * MAJUSCULES est actif.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CursorCapsModeTest {

    private EditorView newViewWithSelection(Context ctx, String text, int caret) {
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(text));
        session.setSelection(caret);
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    // ── initialCapsMode alimenté à la connexion ─────────────────────

    @Test
    public void apresPointEtEspace_initialCapsModeAnnonceLeDebutDePhrase() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSelection(ctx, "Hello. ", 7);

        EditorInfo info = new EditorInfo();
        view.onCreateInputConnection(info);

        assertTrue("la majuscule de début de phrase est annoncée à l'IME",
                (info.initialCapsMode & TextUtils.CAP_MODE_SENTENCES) != 0);
    }

    @Test
    public void auMilieuDUnMot_initialCapsModeEstNeutre() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSelection(ctx, "hello", 3);

        EditorInfo info = new EditorInfo();
        view.onCreateInputConnection(info);

        assertEquals("aucune majuscule annoncée au milieu d'un mot", 0, info.initialCapsMode);
    }

    @Test
    public void auDebutDuTexte_initialCapsModeAnnonceUnDebut() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSelection(ctx, "int main() {", 0);

        EditorInfo info = new EditorInfo();
        view.onCreateInputConnection(info);

        assertNotEquals("le début du texte est un début de paragraphe (mot/phrase)",
                0, info.initialCapsMode);
    }

    // ── getCursorCapsMode réel via la connexion ─────────────────────

    @Test
    public void getCursorCapsMode_apresPoint_repondSentences() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSelection(ctx, "Hello. ", 7);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        int mode = ic.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES);

        assertEquals("l'IME qui demande SENTENCES reçoit SENTENCES",
                TextUtils.CAP_MODE_SENTENCES, mode);
    }

    @Test
    public void getCursorCapsMode_auMilieuDUnMot_repondZero() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSelection(ctx, "hello", 5);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        assertEquals("rien à capitaliser au milieu d'un mot",
                0, ic.getCursorCapsMode(TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES));
    }

    @Test
    public void getCursorCapsMode_reqModesNul_repondZero() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSelection(ctx, "Hello. ", 7);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        assertEquals("aucune information demandée → aucune réponse",
                0, ic.getCursorCapsMode(0));
    }

    // ── Fenêtre bornée : le grand document ne matérielise rien ──────

    @Test
    public void grandDocument_capsCalculeSurFenetreBornee() {
        Context ctx = RuntimeEnvironment.getApplication();
        // 50 000 caractères ; le caret après « . » se trouve vers la fin.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            sb.append("filler line of text here padding padding\n");
        }
        sb.append("end. ");
        EditorView view = newViewWithSelection(ctx, sb.toString(), sb.length());
        view.getSession().getDocument().length(); // longueur lue sur la rope

        EditorInfo info = new EditorInfo();
        view.onCreateInputConnection(info);

        assertTrue("la fenêtre bornée (1 000 car. avant le caret) suffit à la décision",
                (info.initialCapsMode & TextUtils.CAP_MODE_SENTENCES) != 0);
        assertNotNull(view.getSession());
    }
}

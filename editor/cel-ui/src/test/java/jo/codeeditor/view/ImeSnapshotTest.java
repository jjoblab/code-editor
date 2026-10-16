package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.TextSnapshot;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;

/**
 * R4 (plan 09, E4) — l'instantané atomique {@code takeSnapshot} (API 33) :
 * le système sauvegarde l'état IME quand l'application est tuée en
 * arrière-plan — sans lui, la composition en cours mourait avec le
 * processus.
 *
 * <p>Le snapshot porte : le texte environnant (2 048 caractères de chaque
 * côté du caret, bornés au document), la sélection ABSOLUE (via l'offset
 * du SurroundingText), la région de composition en offsets ABSOLUS
 * (miroir des candidats d'updateSelection, -1/-1 sans composition) et
 * les capitales au caret.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ImeSnapshotTest {

    private EditorView newViewWithSession(Context ctx, String text, int caret) {
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(text));
        session.setSelection(caret);
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test
    public void snapshotAvecComposition_regionAbsoluteEtSelectionCoherentes() {
        Context ctx = RuntimeEnvironment.getApplication();
        //          0123456
        // Caret à 7 (juste après « Hello. »), composition « wor ».
        EditorView view = newViewWithSession(ctx, "Hello. world", 7);
        view.getSession().imeSetComposingText("wor", 1);
        // Le document devient « Hello. worworld » — la composition a été
        // insérée (écart minimal) à la position 7, devant « world ».
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());
        TextSnapshot snap = ic instanceof EditorImeBridge.Api33InputConnection
                ? ((EditorImeBridge.Api33InputConnection) ic).takeSnapshot() : null;

        assertEquals("la composition est armée", 3,
                view.getSession().getComposingRegion()[1]
                    - view.getSession().getComposingRegion()[0]);
        org.junit.Assert.assertNotNull("la connexion API 33+ fournit un instantané", snap);
        assertEquals("composition en offsets ABSOLUS (miroir updateSelection)",
                7, snap.getCompositionStart());
        assertEquals(10, snap.getCompositionEnd());
        // Le caret de composition (newCursorPosition == 1 → fin de région).
        assertEquals(10, snap.getSelectionStart());
        assertEquals(10, snap.getSelectionEnd());
        // Le texte environnant couvre tout le (petit) document, offset 0.
        assertEquals(0, snap.getSurroundingText().getOffset());
        assertEquals("Hello. worworld",
                snap.getSurroundingText().getText().toString());
    }

    @Test
    public void snapshotSansComposition_compositionMoinsUn() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newViewWithSession(ctx, "int main() {}", 4);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        TextSnapshot snap = ((EditorImeBridge.Api33InputConnection) ic).takeSnapshot();

        org.junit.Assert.assertNotNull(snap);
        assertEquals("sans composition : -1/-1 (contrat TextSnapshot)",
                -1, snap.getCompositionStart());
        assertEquals(-1, snap.getCompositionEnd());
        assertEquals(4, snap.getSelectionStart());
    }

    @Test
    public void snapshotGrandDocument_fenetreBorneeEtOffsetExact() {
        Context ctx = RuntimeEnvironment.getApplication();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            sb.append("abcdefghijklmnopqrstuvwxyz0123456789"); // 36 car./ligne
        }
        String doc = sb.toString(); // 36 000 caractères
        int caret = doc.length() / 2; // 18 000
        EditorView view = newViewWithSession(ctx, doc, caret);
        view.getSession().setSelection(caret);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        TextSnapshot snap = ((EditorImeBridge.Api33InputConnection) ic).takeSnapshot();

        org.junit.Assert.assertNotNull(snap);
        // Fenêtre 2 048 + 2 048 autour du caret, décalage = début de fenêtre.
        assertEquals(2 * EditorImeBridge.SNAPSHOT_CONTEXT_CHARS,
                snap.getSurroundingText().getText().length());
        assertEquals(caret - EditorImeBridge.SNAPSHOT_CONTEXT_CHARS,
                snap.getSurroundingText().getOffset());
        // Sélection absolue reconstruite = sélection relative + offset.
        assertEquals(caret, snap.getSelectionStart());
        assertEquals(caret, snap.getSelectionEnd());
    }
}

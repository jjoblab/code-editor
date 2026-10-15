package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrat de la notification d'édition ENRICHIE (P-02, audit 3.69.0) :
 * {@code OnTextEditListener} 4-arg porte le texte RETIRÉ — les ponts qui
 * calculent des positions dans l'état PRÉ-édition en ont besoin.
 *
 * <p>La variante 3-arg (héritage) doit continuer de fonctionner pour les
 * implémentations existantes, y compris les lambdas.</p>
 */
class OnTextEditRemovedContractTest {

    private static final String DOC = "alpha beta\n";

    /** Un remplacement notifie (début, fin, inséré, retiré) exacts. */
    @Test
    void remplacement_porteLeTexteRetiré() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<String> events = new ArrayList<>();
        s.addOnTextEditListener(new EditorSession.OnTextEditListener() {
            @Override
            public void onTextEdit(int start, int end, String inserted) {}
            @Override
            public void onTextEdit(int start, int end, String inserted, String removed) {
                events.add(start + "|" + end + "|" + inserted + "|" + removed);
            }
        });
        s.replaceRange(6, 10, "gamma");
        assertEquals(1, events.size());
        assertEquals("6|10|gamma|beta", events.get(0));
    }

    /** Une insertion pure notifie un retrait vide. */
    @Test
    void insertionPure_retraitVide() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<String> events = new ArrayList<>();
        s.addOnTextEditListener(new EditorSession.OnTextEditListener() {
            @Override
            public void onTextEdit(int start, int end, String inserted) {}
            @Override
            public void onTextEdit(int start, int end, String inserted, String removed) {
                events.add(start + "|" + end + "|" + inserted + "|" + removed);
            }
        });
        s.replaceRange(5, 5, "X");
        assertEquals(1, events.size());
        assertEquals("5|5|X|", events.get(0));
    }

    /** Une suppression multi-lignes porte le texte retiré AVEC ses sauts de ligne. */
    @Test
    void suppressionMultiLignes_retraitAvecSautsDeLigne() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<String> events = new ArrayList<>();
        s.addOnTextEditListener(new EditorSession.OnTextEditListener() {
            @Override
            public void onTextEdit(int start, int end, String inserted) {}
            @Override
            public void onTextEdit(int start, int end, String inserted, String removed) {
                events.add(start + "|" + end + "|" + inserted + "|" + removed);
            }
        });
        s.replaceRange(4, 11, "");
        assertEquals(1, events.size());
        assertEquals("4|11||a beta\n", events.get(0));
    }

    /** L'undo notifie l'opération inverse (bornes post-insertion, texte d'origine). */
    @Test
    void undo_notifieLOpérationInverse() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<String> events = new ArrayList<>();
        s.addOnTextEditListener(new EditorSession.OnTextEditListener() {
            @Override
            public void onTextEdit(int start, int end, String inserted) {}
            @Override
            public void onTextEdit(int start, int end, String inserted, String removed) {
                events.add(start + "|" + end + "|" + inserted + "|" + removed);
            }
        });
        s.replaceRange(6, 10, "gamma");
        s.undo();
        assertEquals(2, events.size());
        assertEquals("6|10|gamma|beta", events.get(0));
        // Inverse : [6, 11) (gamma en place) remplacé par « beta ».
        assertEquals("6|11|beta|gamma", events.get(1));
        assertEquals(DOC, s.getText().toString());
    }

    /**
     * La variante 3-arg héritée (lambda) continue de recevoir les
     * notifications — délégation par la méthode par défaut.
     */
    @Test
    void variante3Arg_héritéeFonctionToujours() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<String> legacy = new ArrayList<>();
        s.addOnTextEditListener((start, end, inserted) ->
                legacy.add(start + "|" + end + "|" + inserted));
        s.replaceRange(0, 5, "ALPHA");
        assertEquals(1, legacy.size());
        assertEquals("0|5|ALPHA", legacy.get(0));
    }
}

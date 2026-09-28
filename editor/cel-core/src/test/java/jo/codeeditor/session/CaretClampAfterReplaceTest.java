package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le caret après une édition doit être borné à la longueur FINALE du
 * document : le clamp historique (doc.length() + insertion.length())
 * oubliait de soustraire la longueur de la plage remplacée — la
 * rétro-correction IME d'un long passage par un texte court laissait un
 * caret hors bornes (sélection invalide poussée à l'IME).
 */
class CaretClampAfterReplaceTest {

    @Test
    void replaceRangeWithCaret_clampsToFinalDocumentLength() {
        EditorSession s = new EditorSession(EditorDocument.of("0123456789"));
        // Remplace tout le document (10 chars) par "abc", caret demandé 999.
        s.replaceRangeWithCaret(0, 10, "abc", 999);
        assertEquals("abc", s.getDocument().getText());
        assertEquals(3, s.getSelection().start,
                "le caret doit être borné à la longueur finale (3), pas à 10+3");
    }

    @Test
    void imeReplaceText_clampsToFinalDocumentLength() throws Exception {
        EditorSession s = new EditorSession(EditorDocument.of("0123456789"));
        Field imeField = EditorSession.class.getDeclaredField("ime");
        imeField.setAccessible(true);
        ImeBridge ime = (ImeBridge) imeField.get(s);

        // Rétro-correction API 34+ : replaceText(0, 10, "abc", newCaret=50).
        ime.replaceText(0, 10, "abc", 50);
        assertEquals("abc", s.getDocument().getText());
        assertTrue(s.getSelection().start <= 3,
                "le caret doit être dans les bornes du document final (3), était "
                        + s.getSelection().start);
    }
}

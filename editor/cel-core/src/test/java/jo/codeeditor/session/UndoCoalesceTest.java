package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La coalescence des frappes consécutives doit réellement se déclencher :
 * avant l'amorçage de la chaîne (armNextCoalesce), la condition
 * {@code edit.start == lastEditEnd} de tryCoalesce était inatteignable
 * (pushStep remettait lastEditEnd à -1) — chaque caractère tapé créait
 * une étape d'annulation séparée, contrairement à la javadoc.
 */
class UndoCoalesceTest {

    @Test
    void consecutiveTyping_coalescesIntoOneUndoStep() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        for (char c : "hello world".toCharArray()) {
            s.commitText(String.valueOf(c));
        }
        assertEquals("hello world", s.getDocument().getText());
        // UN SEUL undo doit annuler toute la frappe consécutive.
        assertTrue(s.undo());
        assertEquals("", s.getDocument().getText(),
                "les frappes consécutives doivent fusionner en une étape");
    }

    @Test
    void replaceBreaksTheCoalesceChain() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.commitText("h");
        s.commitText("i");
        // Un remplacement (multi-caractères) coupe la chaîne.
        s.replaceRange(0, 2, "HI!");
        s.commitText("x"); // frappe APRÈS le remplacement
        assertEquals("HI!x", s.getDocument().getText());
        // 3 étapes attendues : "hi" coalescé, le remplacement, "x".
        assertTrue(s.undo());
        assertEquals("HI!", s.getDocument().getText());
        assertTrue(s.undo());
        assertEquals("hi", s.getDocument().getText(),
                "le remplacement est une étape distincte de la frappe coalescée");
        assertTrue(s.undo());
        assertEquals("", s.getDocument().getText());
        assertFalse(s.undo(), "exactement 3 étapes");
    }

    @Test
    void undoBreaksTheChain() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.replaceRange(0, 0, "AB"); // étape A (remplacement)
        s.commitText("c");          // étape B
        s.commitText("d");          // coalescée dans B
        assertEquals("ABcd", s.getDocument().getText());
        // Undo de B : "AB" reste, et la chaîne est COUPÉE.
        assertTrue(s.undo());
        assertEquals("AB", s.getDocument().getText());
        // Une frappe à la position adjacente NE doit PAS fusionner avec
        // l'étape A restée au sommet de la pile (l'undo coupe la chaîne).
        s.commitText("e");
        assertEquals("ABe", s.getDocument().getText());
        assertTrue(s.undo());
        assertEquals("AB", s.getDocument().getText(),
                "la frappe post-undo ne doit pas fusionner avec l'étape précédente");
        assertTrue(s.undo());
        assertEquals("", s.getDocument().getText());
        assertFalse(s.undo());
    }
}

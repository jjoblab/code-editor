package jo.codeeditor.session;

import org.junit.jupiter.api.Test;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.snippet.SnippetMacroContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Le contexte de macros du contrôleur de snippets (4.5.0) : posé par
 * l'hôte, lu à chaque expansion — les macros {@code $FILE$}/
 * {@code $DATE$}/{@code $USER$} d'un gabarit accepté se matérialisent
 * dans le document, et les champs de choix survivent à l'application.
 */
class SnippetControllerMacrosTest {

    private EditorSession sessionAvecTexte(String texte) {
        EditorSession session = new EditorSession(EditorDocument.of(texte));
        return session;
    }

    @Test
    void apply_sansContexte_macrosLittérales() {
        EditorSession session = sessionAvecTexte("");
        session.snippets.apply(0, 0, "// $FILE$\n$1");
        assertEquals("// $FILE$\n", session.getText());
    }

    @Test
    void apply_avecContexte_macrosRésolues() {
        EditorSession session = sessionAvecTexte("");
        session.snippets.setMacroContext(new SnippetMacroContext(
                "Main.java", "Jeanne", () -> "2026-10-27"));
        session.snippets.apply(0, 0, "// $USER$, $DATE$ — $FILE$\n$1");
        assertEquals("// Jeanne, 2026-10-27 — Main.java\n", session.getText());
    }

    @Test
    void contexteChangé_prisAuSnippetSuivant() {
        EditorSession session = sessionAvecTexte("");
        session.snippets.setMacroContext(SnippetMacroContext.of("A.kt", "u"));
        session.snippets.apply(0, 0, "$FILE$ $1");
        assertEquals("A.kt ", session.getText());
        session.snippets.setMacroContext(SnippetMacroContext.of("B.kt", "u"));
        // Insertion À LA FIN (plage vide), pas un remplacement du
        // document entier.
        int fin = session.getText().length();
        session.snippets.apply(fin, fin, "$FILE$ $1");
        assertEquals("A.kt B.kt ", session.getText());
    }

    @Test
    void apply_champDeChoix_premièreOptionSélectionnée() {
        EditorSession session = sessionAvecTexte("const  = 1;");
        // Le champ de choix remplace le second mot vide.
        boolean active = session.snippets.apply(6, 6, "${1|val,var|}");
        assertTrue(active);
        assertEquals("const val = 1;", session.getText());
        // Le champ « val » est sélectionné, options disponibles.
        assertNotNull(session.snippets.get());
        assertFalse(session.snippets.get().current().choices.isEmpty());
        assertEquals(6, session.getSelection().start);
        assertEquals(9, session.getSelection().end);
    }
}

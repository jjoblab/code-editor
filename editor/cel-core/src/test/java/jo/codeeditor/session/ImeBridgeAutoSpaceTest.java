package jo.codeeditor.session;

import org.junit.jupiter.api.Test;

import jo.codeeditor.document.EditorDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B21a — l'« auto-espace » IME n'avale plus une VRAIE espace tapée après
 * ponctuation : l'offset de lot du dernier symbole commité était avalé
 * sans validation et jamais réinitialisé par les autres mutations IME
 * (déplacement du caret, backspace, composition) — l'espace suivante
 * disparaissait même quand elle était légitime.
 */
public class ImeBridgeAutoSpaceTest {

    private EditorSession newSession(String content) {
        return new EditorSession(EditorDocument.of(content));
    }

    @Test
    public void phantomSpace_afterSymbol_isStillSwallowed() {
        EditorSession s = newSession("");
        // Le clavier Gboard commit « ; » puis une « » nue (auto-espace).
        s.imeCommitText(";");
        s.imeCommitText(" ");
        assertEquals(";", s.getDocument().getText(), "l'auto-espace du clavier reste avalée");
    }

    @Test
    public void space_afterNonSymbolCommit_isCommitted() {
        EditorSession s = newSession("");
        // Le clavier commit « ; » puis l'auto-espace (avalée — les deux
        // commits sont indiscernables à ce stade).
        s.imeCommitText(";");
        s.imeCommitText(" ");
        assertEquals(";", s.getDocument().getText(), "l'auto-espace reste avalée");
        // L'utilisateur tape « x » : le commit non-symbole TERMINE le lot —
        // l'espace suivante doit être committée, pas avalée.
        s.imeCommitText("x");
        s.imeCommitText(" ");
        assertEquals(";x ", s.getDocument().getText(),
                "le suivi du symbole ne survit pas au commit suivant");
    }

    @Test
    public void space_afterCaretMove_isNotSwallowed() {
        EditorSession s = newSession("");
        s.imeCommitText(";");
        // L'utilisateur (ou l'IME) déplace le caret — fin du lot.
        s.setSelection(0);
        s.imeCommitText(" ");
        assertEquals(" ;", s.getDocument().getText(), "l'espace tapée après un déplacement est committée");
    }

    @Test
    public void space_afterBackspaceOfSymbol_isNotSwallowed() {
        EditorSession s = newSession("");
        s.imeCommitText(";");
        // L'utilisateur efface le symbole (backspace IME) puis tape une espace.
        s.imeDeleteSurrounding(1, 0);
        s.imeCommitText(" ");
        assertEquals(" ", s.getDocument().getText(), "l'espace tapée après backspace du symbole est committée");
    }

    @Test
    public void space_afterComposition_isNotSwallowed() {
        EditorSession s = newSession("");
        s.imeCommitText(";");
        // Une composition démarre (setComposingText) — fin du lot.
        s.imeSetComposingText("a", 1);
        s.imeFinishComposing();
        s.imeCommitText(" ");
        assertEquals(";a ", s.getDocument().getText(), "l'espace tapée après une composition est committée");
    }

    @Test
    public void groupedAutoSpace_stillStripped() {
        EditorSession s = newSession("");
        // Auto-espace GROUPÉE : « p » commité avec l'espace finale fantôme.
        s.imeCommitText("; ");
        assertEquals(";", s.getDocument().getText(), "l'espace fantôme groupée reste retirée");
    }
}

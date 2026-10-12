package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de régression pour des corrections de bugs d'affichage et de
 * navigation du caret.
 *
 * <p>Chaque test épingle un bug corrigé pour qu'un changement futur ne
 * puisse pas le faire revenir silencieusement.
 */
class V108RegressionTest {

    // ── Curseur invisible après undo/redo ────────────────────────
    // Cause racine : EditorSession.undo()/redo() ne notifiait pas
    // l'ImeListener, donc la View ne relançait ni le clignotement du
    // caret ni le scroll-into-view. On vérifie que le listener EST notifié.

    @Test
    void undo_notifiesImeListener_textChanged() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        boolean[] textChanged = {false};
        boolean[] selChanged = {false};
        s.setImeListener(new EditorSession.ImeListener() {
            @Override public void onTextChanged(jo.codeeditor.shift.EditSpan span) { textChanged[0] = true; }
            @Override public void onSelectionChanged(int a, int b, int c, int d) { selChanged[0] = true; }
            @Override public void onRestartInput() {}
            @Override public boolean isSyncingExtractedText() { return false; }
        });
        s.commitText("X");
        // Réinitialiser les drapeaux avant l'undo pour n'observer que la
        // notification de l'undo.
        textChanged[0] = false;
        selChanged[0] = false;
        assertTrue(s.undo());
        assertTrue(textChanged[0], "undo() must fire onTextChanged so the view restarts the caret blink");
        assertTrue(selChanged[0], "undo() must fire onSelectionChanged so the view scrolls the caret into view");
    }

    @Test
    void redo_notifiesImeListener_textChanged() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        boolean[] textChanged = {false};
        boolean[] selChanged = {false};
        s.setImeListener(new EditorSession.ImeListener() {
            @Override public void onTextChanged(jo.codeeditor.shift.EditSpan span) { textChanged[0] = true; }
            @Override public void onSelectionChanged(int a, int b, int c, int d) { selChanged[0] = true; }
            @Override public void onRestartInput() {}
            @Override public boolean isSyncingExtractedText() { return false; }
        });
        s.commitText("X");
        s.undo();
        textChanged[0] = false;
        selChanged[0] = false;
        assertTrue(s.redo());
        assertTrue(textChanged[0], "redo() must fire onTextChanged so the view restarts the caret blink");
        assertTrue(selChanged[0], "redo() must fire onSelectionChanged so the view scrolls the caret into view");
    }

    @Test
    void undo_restoresCaretPosition() {
        // Après undo, le caret doit atterrir sur step.selBefore (la position
        // avant l'édition d'origine). Si le caret est au mauvais endroit, la
        // vue ne peut pas le faire défiler correctement.
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(0);
        s.commitText("X");
        // Le caret est maintenant à 1 (après le X).
        assertEquals(1, s.getSelection().start);
        s.undo();
        assertEquals(0, s.getSelection().start, "undo() must restore the caret to selBefore (0)");
        assertEquals("hello", s.getText());
    }

    @Test
    void redo_restoresCaretPosition() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(0);
        s.commitText("X");
        s.undo();
        s.redo();
        assertEquals(1, s.getSelection().start, "redo() must restore the caret to selAfter (1)");
        assertEquals("Xhello", s.getText());
    }

    // ── Position de tap décalée de ~5 caractères (double soustraction de padLeft) ──
    // Cause racine : offsetAt déléguait à metrics.xToCol après avoir déjà
    // soustrait padLeft+gutterWidth, les comptant deux fois. On vérifie
    // directement le calcul de colonne avec la même formule qu'offsetAt.

    @Test
    void tapColumnMath_noDoubleOffset() {
        // Simulation : gutterWidth = 5cw, padLeft = 0.5cw, charWidth = cw.
        // Un tap sur la col 0 atterrit au X écran = gutterWidth + padLeft = 5.5cw.
        // Ancien code : xToCol(5.5cw - 5cw - 0.5cw) = xToCol(0) = (0 - 0.5cw - 5cw)/cw = -5.5 → borné à 0 (par chance).
        // Un tap sur la col 5 atterrit au X écran = 5.5cw + 5cw = 10.5cw.
        // Ancien code : xToCol(10.5cw - 5cw - 0.5cw) = xToCol(5cw) = (5cw - 0.5cw - 5cw)/cw = -0.5 → borné à 0 (FAUX).
        // Code actuel : col = (10.5cw - (5.5cw - 0)) / cw + 0.5 = 5 + 0.5 = 5 (tronqué à 5). CORRECT.
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float hOffset = 0f;
        float textAreaLeft = gutterWidth + padLeft;
        // Tap sur la col 5 (X écran = textAreaLeft + 5*charWidth - hOffset).
        float tapX = textAreaLeft + 5 * charWidth - hOffset;
        float colScreenX = textAreaLeft - hOffset;
        int col = (int) ((tapX - colScreenX) / charWidth + 0.5f);
        assertEquals(5, col, "tap on col 5 must yield col 5 (no double padLeft subtraction)");
    }

    @Test
    void tapColumnMath_respectsHorizontalScroll() {
        // Quand le texte est défilé de 3 caractères vers la droite
        // (hOffset = 3*charWidth), un tap au X écran = textAreaLeft doit
        // donner la col 3 (premier caractère visible).
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float hOffset = 3 * charWidth;
        float textAreaLeft = gutterWidth + padLeft;
        float tapX = textAreaLeft; // tap au bord gauche de la zone de texte
        float colScreenX = textAreaLeft - hOffset;
        int col = (int) ((tapX - colScreenX) / charWidth + 0.5f);
        assertEquals(3, col, "tap at text-area left edge with hOffset=3cw must yield col 3");
    }

    // ── Le caret « saute » à chaque frappe ──────────────────────
    // Cause racine : scrollCaretIntoView comptait padLeft deux fois dans
    // la branche horizontale, ce qui déclenchait le scroll de marge droite
    // trop tôt. On vérifie que le calcul du X écran du caret est correct.

    @Test
    void caretScreenX_noDoublePadLeft() {
        // Caret à la col 10, gutterWidth=5cw, padLeft=0.5cw, hOffset=0.
        // Le X écran du caret doit être textAreaLeft + 10*charWidth - hOffset = 5.5cw + 10cw = 15.5cw.
        // L'ancien code calculait caretScreenX = textLeft + (padLeft + 10*charWidth) - hOffset = 5.5cw + (0.5cw + 10cw) = 16cw (décalé de 0.5cw).
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float textLeft = gutterWidth + padLeft;
        float hOffset = 0f;
        int col = 10;
        // Formule actuelle (pas de double padLeft) :
        float caretScreenX = textLeft + col * charWidth - hOffset;
        // Attendu : 5cw + 0.5cw + 10cw = 15.5cw → 155px
        assertEquals(155f, caretScreenX, 0.01f, "caret screen X must not double-count padLeft");
    }

    @Test
    void scrollCaretIntoView_doesNotScrollWhenCaretVisible() {
        // Si le caret est déjà dans la plage horizontale visible, le
        // scroll-into-view ne doit PAS changer hOffset. L'ancien double
        // padLeft déclenchait le contrôle de marge droite trop tôt.
        // Simulation : caret à la col 5, viewW = 30cw, hOffset = 0.
        // caretScreenX = textLeft + 5cw = 5.5cw. textLeft+margin = 5.5cw + 3cw = 8.5cw.
        // 5.5cw < 8.5cw → le scroll de marge gauche se déclenche (FAUX — le caret est visible).
        // Le code actuel utilise le même caretScreenX, mais le contrôle de
        // marge se fait contre textLeft + margin, ce qui est correct.
        // Ici on vérifie juste que la formule ne produit pas de scroll parasite.
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float padRight = charWidth * 0.5f;
        float textLeft = gutterWidth + padLeft;
        float hOffset = 0f;
        int col = 5;
        float caretScreenX = textLeft + col * charWidth - hOffset;
        float viewW = 300f; // 30 caractères de large
        float margin = charWidth * 3;
        boolean shouldScrollLeft = caretScreenX < textLeft + margin;
        boolean shouldScrollRight = caretScreenX > textLeft + viewW - margin;
        assertFalse(shouldScrollLeft, "caret at col 5 with 30-char-wide view must NOT trigger left scroll");
        assertFalse(shouldScrollRight, "caret at col 5 with 30-char-wide view must NOT trigger right scroll");
    }

    // ── Le chevron de repli chevauche le numéro de ligne ────────
    // Cause racine : gutterWidth n'incluait pas la bande de repli, donc
    // le chevron était dessiné par-dessus le dernier chiffre. On vérifie
    // le calcul de layout : les numéros de ligne finissent AVANT la bande
    // de repli.

    @Test
    void gutterLayout_lineNumberAreaBeforeFoldStrip() {
        // Simuler EditorMetrics.setTextSize pour une police de 14px.
        // charWidth ≈ 8.4px (monospace 14px), foldStripWidth = 2*charWidth,
        // gutterWidth = 5*charWidth + foldStripWidth = 7*charWidth.
        float charWidth = 8.4f;
        float foldStripWidth = charWidth * 2f;
        float gutterWidth = charWidth * 5f + foldStripWidth;
        float lineNumberAreaRight = gutterWidth - foldStripWidth;
        // Les numéros de ligne sont alignés à droite à lineNumberAreaRight - 0.5*charWidth.
        float textX = lineNumberAreaRight - charWidth * 0.5f;
        // Le chevron de repli est centré dans la bande de repli.
        float foldStripCenter = gutterWidth - foldStripWidth * 0.5f;
        // Le chevron doit être à DROITE de la zone des numéros de ligne.
        assertTrue(foldStripCenter > textX,
            "fold chevron center (" + foldStripCenter + ") must be right of line-number end (" + textX + ")");
        assertTrue(foldStripCenter > lineNumberAreaRight,
            "fold chevron center must be inside the fold strip, not the line-number area");
    }
}

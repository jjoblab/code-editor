package jo.codeeditor.lsp;

import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1/D-01 — conversion LSP → SPI des items de complétion : champs
 * {@code textEdit} (cas limites), {@code insertTextFormat},
 * {@code filterText}, {@code sortText}.
 *
 * <p>Rouge avant : la conversion historique ne lisait que
 * label/detail/insertText/kind/data — l'édition, le format et les deux
 * champs de tri/filtrage étaient ignorés ; un snippet insérait ses
 * marqueurs littéralement.</p>
 *
 * <p>La conversion est testée en fonction pure : la fonction
 * Position→offset est injectée et calquée sur le document de test
 * (2 lignes : la première de 12 caractères).</p>
 */
class LspCompletionTextEditTest {

    private static final String DOC = "int foo;\nint bar;\n";
    // Ligne 0 : "int foo;\n" (9 caractères), ligne 1 : "int bar;\n" (9).
    private static final int L0 = 0;   // début ligne 0
    private static final int L1 = 9;   // début ligne 1

    private static final ToIntFunction<Position> TO_OFFSET = pos ->
            (pos.getLine() == 0 ? L0 : L1) + pos.getCharacter();

    private static Range range(int line, int startCol, int endLine, int endCol) {
        return new Range(new Position(line, startCol), new Position(endLine, endCol));
    }

    // ── Cas limites de la plage de remplacement ────────────────────

    @Test
    void textEditPlaine_plageConvertieEnOffsets() {
        TextEdit edit = new TextEdit();
        edit.setRange(range(0, 4, 0, 7));          // "foo" de "int foo;"
        edit.setNewText("foobar");
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, 7, DOC.length());
        assertNotNull(r, "plage nominale acceptée");
        assertEquals(4, r[0]);
        assertEquals(7, r[1]);
    }

    @Test
    void chevauchementDePréfixe_plageAvantTokenPréservée() {
        // Le serveur remplace "foo." + le token — la plage DÉMARRE avant
        // le token courant : elle est transportée telle quelle (la
        // double application token ET plage est la cause des
        // « console.console.log »).
        TextEdit edit = new TextEdit();
        edit.setRange(range(0, 4, 0, 8));
        edit.setNewText("foobar(");
        int caret = 7;
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, caret, DOC.length());
        assertNotNull(r);
        assertEquals(4, r[0], "le début AVANT le token est préservé");
        assertEquals(8, r[1]);
    }

    @Test
    void clampÀLaFinDeDocument() {
        // Plage qui déborde au-delà du document (serveur bogué).
        TextEdit edit = new TextEdit();
        edit.setRange(range(1, 4, 1, 9999));
        edit.setNewText("qux");
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, 17, DOC.length());
        assertNotNull(r);
        assertEquals(13, r[0]);
        assertEquals(DOC.length(), r[1], "la fin est bornée à la longueur du document");
    }

    @Test
    void clampAuDébutDeDocument() {
        TextEdit edit = new TextEdit();
        edit.setRange(range(0, -5, 0, 3));   // bornes négatives (serveur bogué)
        edit.setNewText("x");
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, 9, DOC.length());
        assertNotNull(r);
        assertEquals(0, r[0], "le début est borné à 0");
        assertEquals(3, r[1]);
    }

    @Test
    void plageInversée_réordonnée() {
        TextEdit edit = new TextEdit();
        edit.setRange(range(0, 7, 0, 4));    // end < start
        edit.setNewText("qux");
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, 7, DOC.length());
        assertNotNull(r, "plage inversée acceptée après réordonnancement");
        assertEquals(4, r[0]);
        assertEquals(7, r[1]);
    }

    @Test
    void plageAprèsLeCaret_rejetée() {
        TextEdit edit = new TextEdit();
        edit.setRange(range(1, 0, 1, 3));    // entièrement après le caret (7)
        edit.setNewText("qux");
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, 7, DOC.length());
        assertNull(r, "plage démarrant strictement après le caret → rejet, repli token");
    }

    @Test
    void plageAuCaretAcceptée_insertionPure() {
        TextEdit edit = new TextEdit();
        edit.setRange(range(0, 7, 0, 7));    // start == caret : insertion pure
        edit.setNewText("qux");
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, 7, DOC.length());
        assertNotNull(r);
        assertEquals(7, r[0]);
        assertEquals(7, r[1]);
    }

    @Test
    void insertReplaceEdit_plageReplacePrivilégiée() {
        InsertReplaceEdit ire = new InsertReplaceEdit();
        ire.setNewText("log($0)");
        ire.setInsert(range(0, 7, 0, 7));    // insertion au caret
        ire.setReplace(range(0, 4, 0, 7));   // remplace "foo"
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forRight(ire), TO_OFFSET, 7, DOC.length());
        assertNotNull(r);
        assertEquals(4, r[0], "sémantique RETENUE : la plage replace est privilégiée");
        assertEquals(7, r[1]);
    }

    @Test
    void insertReplaceEdit_sansPlageReplace_repliSurInsert() {
        InsertReplaceEdit ire = new InsertReplaceEdit();
        ire.setNewText("log($0)");
        ire.setInsert(range(0, 7, 0, 7));
        int[] r = LspCompletionProvider.normalizedReplaceRange(
                Either.forRight(ire), TO_OFFSET, 7, DOC.length());
        assertNotNull(r, "repli sur la plage insert quand replace est absente");
        assertEquals(7, r[0]);
        assertEquals(7, r[1]);
    }

    @Test
    void éditionSansBornes_rejetée() {
        TextEdit edit = new TextEdit();
        edit.setNewText("x");            // range null
        assertNull(LspCompletionProvider.normalizedReplaceRange(
                Either.forLeft(edit), TO_OFFSET, 7, DOC.length()));
        assertNull(LspCompletionProvider.normalizedReplaceRange(
                null, TO_OFFSET, 7, DOC.length()), "édition absente → null");
    }

    // ── Champs transportés par convertItem ────────────────────────

    @Test
    void convertItem_snippetFormatEtChampsTransportés() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("sout");
        lsp.setDetail("affiche sur la sortie standard");
        lsp.setInsertText("System.out.println(${1:msg});$0");
        lsp.setInsertTextFormat(InsertTextFormat.Snippet);
        lsp.setFilterText("sout");
        lsp.setSortText("01");
        jo.codeeditor.lang.model.CompletionItem out = LspCompletionProvider.convertItem(
                lsp, 5, TO_OFFSET, 7, DOC.length());
        assertTrue(out.isSnippet, "insertTextFormat=Snippet → isSnippet");
        assertEquals("System.out.println(${1:msg});$0", out.insertText,
                "le texte de snippet voyage brut");
        assertEquals("sout", out.filterText);
        assertEquals("01", out.sortText);
        assertEquals(-1, out.replaceStart, "pas d'édition → plage non spécifiée");
        assertEquals(-1, out.replaceEnd);
    }

    @Test
    void convertItem_édition_newTextPrioritaire_etPlagePosée() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("foobar");
        lsp.setInsertText("IGNORE");
        lsp.setTextEdit(Either.forLeft(new TextEdit(range(0, 4, 0, 7), "foobar(")));
        jo.codeeditor.lang.model.CompletionItem out = LspCompletionProvider.convertItem(
                lsp, 5, TO_OFFSET, 7, DOC.length());
        assertEquals("foobar(", out.insertText, "le newText de l'édition prime sur insertText");
        assertEquals(4, out.replaceStart);
        assertEquals(7, out.replaceEnd);
        assertFalse(out.isSnippet, "pas de format snippet déclaré");
    }

    @Test
    void convertItem_sansChampsÉtendus_comportementHistorique() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("foo");
        jo.codeeditor.lang.model.CompletionItem out = LspCompletionProvider.convertItem(
                lsp, 3, TO_OFFSET, 7, DOC.length());
        assertFalse(out.isSnippet);
        assertNull(out.filterText);
        assertNull(out.sortText);
        assertEquals(-1, out.replaceStart);
        assertEquals(-1, out.replaceEnd);
        assertEquals("foo", out.insertText, "repli insertText → label");
    }
}

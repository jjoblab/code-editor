package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.shift.DiagnosticShift;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de régression — cycle de vie/disposal, buckets par ligne et
 * re-planification du restyle asynchrone.
 *
 * <p>Chaque test épingle un correctif pour qu'un changement futur ne
 * puisse pas faire revenir le bug silencieusement.</p>
 */
class V3340RegressionTest {

    // ── dispose() : libère l'exécuteur de restyle ───────────────

    @Test
    void dispose_isDisposed_flagAndPendingCancel() throws Exception {
        EditorSession s = new EditorSession(EditorDocument.of("line1\nline2\nline3"));
        assertFalse(s.isDisposed(), "fresh session must not be disposed");
        // Déclencher un restyle asynchrone pour qu'il y ait bien du pending
        // à annuler.
        s.setLanguage("kotlin");
        s.dispose();
        assertTrue(s.isDisposed(), "dispose() must set the flag");
        // Aucun restyle pending ne survit au dispose.
        assertFalse(s.isAsyncRestylePending(), "dispose() must cancel pending restyles");
    }

    @Test
    void dispose_fallsBackToSyncRestyle() {
        EditorSession s = new EditorSession(EditorDocument.of("public class A {}"));
        s.dispose();
        // setLanguage après dispose ne doit PAS lever
        // RejectedExecutionException — il retombe sur le chemin de restyle
        // synchrone.
        assertDoesNotThrow(() -> s.setLanguage("xml"));
        assertEquals(1, s.getStyledLines().size(), "one-line document → one styled line");
        // Et le doc reste entièrement éditable ensuite ("public class A {}"
        // fait 17 caractères — ajouter X à la fin donne 18).
        s.setSelection(17);
        s.commitText("X");
        assertEquals(18, s.getText().length());
    }

    // ── Per-line buckets (getInlayHintsForLine / getSemanticTokensForLine) ──

    private static DiagnosticShift.InlayHint hint(int offset, String text) {
        return new DiagnosticShift.InlayHint(offset, text, false);
    }

    private static DiagnosticShift.SemanticToken token(int start, int length, int type) {
        return new DiagnosticShift.SemanticToken(start, length, type);
    }

    @Test
    void inlayHintsForLine_bucketsByOffset() {
        EditorSession s = new EditorSession(EditorDocument.of("aa\nbbbb\ncc\ndd"));
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(hint(0, "h0"));    // ligne 0, col 0
        hints.add(hint(3, "h3"));    // ligne 1, col 0
        hints.add(hint(6, "h6"));    // ligne 1, col 3
        hints.add(hint(11, "h11"));  // ligne 3, col 0 (doc de 12, ligne 3 = [11,12))
        s.setInlayHints(hints);
        assertEquals(1, s.getInlayHintsForLine(0).size());
        assertEquals(2, s.getInlayHintsForLine(1).size());
        assertEquals(0, s.getInlayHintsForLine(2).size(), "line 2 has no hints");
        assertEquals(1, s.getInlayHintsForLine(3).size());
        // Ligne bien au-delà de tout hint → vide, pas null, pas d'exception.
        assertTrue(s.getInlayHintsForLine(999).isEmpty());
    }

    @Test
    void inlayHintsForLine_indexRebuiltAfterEdit() {
        EditorSession s = new EditorSession(EditorDocument.of("aaaa\nbbbb"));
        s.setInlayHints(new ArrayList<>(List.of(hint(5, "x")))); // ligne 1
        assertEquals(1, s.getInlayHintsForLine(1).size());
        // Insérer un saut de ligne à l'offset 2 — le hint à l'ancien
        // offset 5 passe à l'offset 6 et vit désormais sur la ligne 2.
        s.setSelection(2);
        s.commitText("\n");
        // Le décalage a déplacé le hint ; l'index de buckets doit être
        // reconstruit à partir de la NOUVELLE liste (référence différente)
        // et refléter les NOUVEAUX offsets.
        List<DiagnosticShift.InlayHint> shifted = s.getInlayHints();
        assertEquals(6, shifted.get(0).offset, "DiagnosticShift must move the hint past the edit");
        assertEquals(1, s.getInlayHintsForLine(2).size(), "hint now on line 2");
        assertEquals(0, s.getInlayHintsForLine(1).size());
    }

    @Test
    void semanticTokensForLine_multiLineTokenAppearsInEveryBucket() {
        // Layout du doc : line0=[0,4] line1=[5,9] line2=[10,14] line3=[15,18].
        // Le token [0,14) couvre les lignes 0..2 — il doit être visible dans
        // chacun de ces buckets, et nulle part ailleurs.
        EditorSession s = new EditorSession(EditorDocument.of("aaaa\nbbbb\ncccc\ndddd"));
        s.setSemanticTokens(new ArrayList<>(List.of(token(0, 14, 1))));
        for (int line = 0; line <= 2; line++) {
            assertEquals(1, s.getSemanticTokensForLine(line).size(),
                    "multi-line token must appear in line " + line + "'s bucket");
        }
        assertEquals(0, s.getSemanticTokensForLine(3).size(), "token ends before line 3");
    }

    @Test
    void semanticTokensForLine_clampedToDocument() {
        EditorSession s = new EditorSession(EditorDocument.of("ab\ncd"));
        // Token pathologique au-delà de la fin — les buckets ne doivent pas
        // lever d'exception.
        s.setSemanticTokens(new ArrayList<>(List.of(token(5, 100, 1))));
        assertDoesNotThrow(() -> s.getSemanticTokensForLine(0));
        assertDoesNotThrow(() -> s.getSemanticTokensForLine(1));
    }

    // ── Getters : les vues sans copie restent cohérentes avec l'état vivant ──

    @Test
    void diagnosticsGetter_returnsLiveSnapshotView() {
        EditorSession s = new EditorSession(EditorDocument.of("x"));
        List<DiagnosticShift.Diagnostic> d1 = s.getDiagnostics();
        List<DiagnosticShift.Diagnostic> d2 = s.getDiagnostics();
        // Même liste sous-jacente (immuable après publication) — pas de
        // copie défensive. Les deux vues sont égales et en lecture seule.
        assertEquals(d1, d2);
        assertThrows(UnsupportedOperationException.class, () -> d1.add(null));
    }

    // ── Restyle asynchrone : une édition pendant le gap ne doit PAS laisser de tokens périmés ──

    @Test
    void editDuringAsyncRestyleGap_eventuallyRestylesWholeDocument() throws Exception {
        // Régression : une édition atterrissant dans le gap du restyle
        // asynchrone était détectée (doc != docAtStart) mais le résultat
        // était jeté SANS re-planification — le document gardait les
        // tokens de l'ANCIEN langage pour chaque ligne sauf celle éditée.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("int x").append(i).append(" = 0;\n");
        }
        EditorSession s = new EditorSession(EditorDocument.of(sb.toString()));
        // Changer de langage (déclenche le restyle asynchrone) et éditer
        // IMMÉDIATEMENT — l'édition atterrit dans le gap async quasi
        // certainement.
        s.setLanguage("xml");
        int end = s.getText().length();
        s.setSelection(end - 1);
        s.commitText("y");
        // Attendre que tous les restyles pending (et re-planifiés) se soient
        // vidés.
        long deadline = System.currentTimeMillis() + 10_000;
        while (s.isAsyncRestylePending() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        s.awaitPendingRestyle();
        // Les styledLines finaux doivent être tokenisés dans le NOUVEAU
        // langage pour CHAQUE ligne — contrôle par sondage de la cohérence
        // des entry states.
        List<StyledLine> styled = s.getStyledLines();
        assertEquals(s.getDocument().lineCount(), styled.size(),
                "styledLines must cover the whole document after the gap edit");
        // L'entryState de chaque ligne doit égaliser l'exitState de la ligne
        // précédente — un état mixte de langages casserait cette chaîne
        // quelque part.
        for (int i = 1; i < styled.size(); i++) {
            assertEquals(styled.get(i - 1).exitState, styled.get(i).entryState,
                    "tokenization chain broken at line " + i + " — mixed-language state");
        }
    }
}

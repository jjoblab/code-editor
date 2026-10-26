package jo.codeeditor.shift;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Modificateurs sémantiques (4.4.0) : le 5ᵉ élément du quintuplet LSP
 * voyage jusqu'au modèle — constructeur étendu, repli du constructeur
 * historique sur masque 0, conservation à travers le décalage d'édition.
 */
class SemanticTokenModifiersTest {

    @Test
    void constructeurEtend_transporteLeMasque() {
        DiagnosticShift.SemanticToken t = new DiagnosticShift.SemanticToken(
                3, 5, 9, DiagnosticShift.SemanticToken.MOD_DEPRECATED);
        assertEquals(DiagnosticShift.SemanticToken.MOD_DEPRECATED, t.modifiers);
        assertEquals(3, t.start);
        assertEquals(5, t.length);
        assertEquals(9, t.type);
        assertEquals(8, t.end());
    }

    @Test
    void constructeurHistorique_masqueNul() {
        DiagnosticShift.SemanticToken t = new DiagnosticShift.SemanticToken(0, 4, 2);
        assertEquals(0, t.modifiers,
                "le constructeur historique ne transporte aucun modificateur");
    }

    @Test
    void shift_conserveLesModificateurs() {
        // Édition qui insère 2 caractères avant le jeton (décale à droite).
        EditSpan span = new EditSpan(0, 0, 2);
        DiagnosticShift.SemanticToken t = new DiagnosticShift.SemanticToken(
                5, 4, 7,
                DiagnosticShift.SemanticToken.MOD_STATIC
                        | DiagnosticShift.SemanticToken.MOD_ABSTRACT);
        List<DiagnosticShift.SemanticToken> décalés =
                DiagnosticShift.shiftSemanticTokens(List.of(t), span);
        assertEquals(1, décalés.size());
        assertEquals(7, décalés.get(0).start);
        assertEquals(DiagnosticShift.SemanticToken.MOD_STATIC
                        | DiagnosticShift.SemanticToken.MOD_ABSTRACT,
                décalés.get(0).modifiers,
                "le masque décrit le symbole, pas la position");
    }

    @Test
    void masqueCanonique_bitsIndépendants() {
        assertEquals(1, DiagnosticShift.SemanticToken.MOD_DEPRECATED);
        assertEquals(2, DiagnosticShift.SemanticToken.MOD_STATIC);
        assertEquals(4, DiagnosticShift.SemanticToken.MOD_ABSTRACT);
    }
}

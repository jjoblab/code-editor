package jo.codeeditor.lang.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrat du modèle {@link CompletionItem} — L1/D-01 : transport
 * rétrocompatible de {@code filterText}, {@code sortText} et de la
 * plage de remplacement optionnelle ({@code textEdit} LSP, sémantique
 * remplacement).
 *
 * <p>Rouge avant : les champs n'existaient pas — un serveur renvoyant
 * {@code insertTextFormat=Snippet}, un {@code textEdit}, un
 * {@code filterText} ou un {@code sortText} était réduit au libellé et
 * au texte brut ; les marqueurs de tab stops étaient insérés
 * littéralement.</p>
 */
class CompletionItemFieldsTest {

    @Test
    void anciensConstructeurs_champsÉtendusAbsents() {
        CompletionItem simple = new CompletionItem("foo", "foo");
        assertNull(simple.filterText, "filterText absent par défaut");
        assertNull(simple.sortText, "sortText absent par défaut");
        assertEquals(-1, simple.replaceStart, "plage non spécifiée par défaut");
        assertEquals(-1, simple.replaceEnd, "plage non spécifiée par défaut");
        assertFalse(simple.isSnippet, "mot-clé simple : pas un snippet");

        CompletionItem complet = new CompletionItem("foo", "détail", "foo", "v",
                2, null, 50, false);
        assertNull(complet.filterText);
        assertNull(complet.sortText);
        assertEquals(-1, complet.replaceStart);
        assertEquals(-1, complet.replaceEnd);
    }

    @Test
    void constructeurÉtendu_transporteTousLesChamps() {
        CompletionItem item = new CompletionItem("println", "affiche", "println(${1:msg})$0",
                "m", 2, null, 10, true,
                "println", "11.0",
                4, 9);
        assertTrue(item.isSnippet, "format snippet transporté");
        assertEquals("println", item.filterText);
        assertEquals("11.0", item.sortText);
        assertEquals(4, item.replaceStart, "début de plage de remplacement");
        assertEquals(9, item.replaceEnd, "fin de plage de remplacement (exclusive)");
        assertEquals("println(${1:msg})$0", item.insertText,
                "le texte de snippet voyage BRUT — l'expansion est du ressort de la session");
    }

    @Test
    void nullsNeutralisés_commeHistoriquement() {
        CompletionItem item = new CompletionItem(null, null, null, null,
                0, null, 1, false, null, null, -1, -1);
        assertEquals("", item.label, "label neutralisé");
        assertEquals("", item.detail, "détail neutralisé");
        assertEquals("", item.insertText, "insertText retombe sur le label neutralisé");
        assertEquals("v", item.kind, "kind par défaut");
    }

    @Test
    void plageUniquementPortéeParLeConstructeurÉtendu() {
        // La plage voyage en couple : les deux à -1 (absente), ou les
        // deux renseignées par le pont après normalisation.
        CompletionItem sansPlage = new CompletionItem("a", "a", "a", "v",
                1, null, 1, false, "a", "z", -1, -1);
        assertEquals(-1, sansPlage.replaceStart);
        assertEquals(-1, sansPlage.replaceEnd);
    }
}

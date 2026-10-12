package jo.codeeditor.lang;

import jo.codeeditor.lang.model.CodeAction;
import jo.codeeditor.lang.provider.CodeActionsProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verrouille les ajouts additifs du SPI actions de code :
 *
 * <ul>
 *   <li>la variante « au caret » de
 *       {@link CodeActionsProvider#codeActions} délègue par défaut à la
 *       méthode ligne (compatibilité binaire : une implémentation qui ne
 *       surcharge que la méthode ligne continue de fonctionner) ;</li>
 *   <li>le repli par défaut calcule la LIGNE contenant le caret ;</li>
 *   <li>{@link CodeAction} transporte le motif de désactivation, avec
 *       rétro-compatibilité des anciens constructeurs (motif null).</li>
 * </ul>
 */
class CodeActionDefaultsTest {

    private static final String DOC = "alpha\nbeta\ngamma\n";

    /** Captor : implémentation qui ne surcharge QUE la méthode ligne. */
    private static final class OnlyLine implements CodeActionsProvider {
        int lastLine = Integer.MIN_VALUE;
        int calls;

        @Override
        public List<CodeAction> codeActions(CharSequence text, int line) {
            calls++;
            lastLine = line;
            return new ArrayList<>();
        }
    }

    @Test
    void varianteAuCaret_delègueALaMéthodeLigne() {
        OnlyLine provider = new OnlyLine();
        // « beta » commence à l'offset 6, ligne 1.
        List<CodeAction> out = provider.codeActions(DOC, 6, 6, 6, null);
        assertNotNull(out);
        assertEquals(1, provider.calls);
        assertEquals(1, provider.lastLine);
    }

    @Test
    void repliParDéfaut_ligneDuCaretBornée() {
        OnlyLine provider = new OnlyLine();
        provider.codeActions(DOC, 2, 2, 2, null);   // ligne 0
        assertEquals(0, provider.lastLine);
        // Offset borné à la fin : après les 3 '\n' → ligne 3 (ligne vide
        // finale du texte se terminant par un saut de ligne).
        provider.codeActions(DOC, DOC.length() + 50, DOC.length(), DOC.length(), null);
        assertEquals(3, provider.lastLine);
        provider.codeActions(null, 5, 5, 5, null);  // texte null → ligne 0
        assertEquals(0, provider.lastLine);
    }

    @Test
    void constructeurQuatreArguments_disabledReasonNull() {
        CodeAction a = new CodeAction("Corriger", "quickfix", true, () -> { });
        assertTrue(a.isPreferred);
        assertNull(a.disabledReason);
        assertEquals("quickfix", a.kind);
    }

    @Test
    void constructeurComplet_porteLeMotif() {
        CodeAction a = new CodeAction("Indisponible", "refactor", false,
                "sélection trop courte", () -> { });
        assertEquals("Indisponible", a.title);
        assertEquals("sélection trop courte", a.disabledReason);
        assertFalse(a.isPreferred);
    }

    @Test
    void constructeurDeCommodité_quickfixNonPréféré() {
        CodeAction a = new CodeAction("Ajouter ;", () -> { });
        assertEquals("quickfix", a.kind);
        assertFalse(a.isPreferred);
        assertNull(a.disabledReason);
    }
}

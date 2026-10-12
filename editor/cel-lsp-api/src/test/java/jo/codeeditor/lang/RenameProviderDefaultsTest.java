package jo.codeeditor.lang;

import jo.codeeditor.lang.provider.RenameProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verrouille l'ajout additif {@link RenameProvider#prepareRename} :
 * implémentation par défaut {@code null} (non supporté), sans impact sur
 * les implémentations existantes qui ne la surchargent pas.
 */
class RenameProviderDefaultsTest {

    private static final class SansPrepare implements RenameProvider {
        @Override
        public jo.codeeditor.lang.model.RenameResult rename(
                CharSequence text, int offset, String newName) {
            return null;
        }
    }

    @Test
    void prepareRename_parDéfaut_retourneNull() {
        assertNull(new SansPrepare().prepareRename("int foo = 0;", 4));
    }
}

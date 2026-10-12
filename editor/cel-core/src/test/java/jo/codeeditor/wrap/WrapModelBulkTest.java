package jo.codeeditor.wrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La reconstruction complète du modèle de wrap doit rester LINÉAIRE dans le
 * nombre de lignes : la passe historique (setRows par ligne, chacun
 * reconstruisant la somme préfixe entière) coûtait O(n²) temps et ~O(n²)
 * octets alloués — chaque frappe avec word-wrap actif gelait l'UI sur un
 * gros fichier.
 */
class WrapModelBulkTest {

    @Test
    @Timeout(10)
    void bulkRebuild_of100kLines_staysFastAndCorrect() {
        int lines = 100_000;
        WrapModel m = new WrapModel(lines);
        m.beginBulkSetRows();
        try {
            for (int i = 0; i < lines; i++) {
                // Alterne 1 et 3 rangées pour couvrir le cumul.
                m.setRows(i, (i % 2 == 0) ? 1 : 3);
            }
        } finally {
            m.endBulkSetRows();
        }
        // Une ligne paire occupe les rangées paires cumulées : la ligne 2
        // commence après 1+3 = 4 rangées.
        assertEquals(0L, m.topRow(0));
        assertEquals(4L, m.topRow(2));
        assertEquals(2 * lines, m.totalRows());
        // Le mapping inverse reste cohérent.
        assertEquals(1, m.docLineForRow(1));
        assertEquals(2, m.docLineForRow(4));
        // Le setter ponctuel continue de reconstruire immédiatement.
        m.setRows(0, 5);
        assertEquals(5L, m.topRow(1));
    }
}

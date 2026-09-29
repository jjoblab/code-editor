package jo.codeeditor.rope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contrats de la visite feuille par feuille (port amont c4bec0cf7) :
 * couverture exacte de [from, to), offsets globaux justes, ordres avant/
 * arrière symétriques, arrêt anticipé honnête, plages vides ne visitent
 * RIEN, bornées tronquées clipées.
 */
public class RopeVisitLeavesTest {

    private static final String TEXT = buildText();

    private static String buildText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("abc{def[(ghi)]} "); // 4160 chars — 9 feuilles
        return sb.toString();
    }

    @Test
    public void laVisiteAvantCouvreExactementLaPlage() {
        Rope rope = Rope.fromString(TEXT);
        StringBuilder joined = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        rope.visitLeaves(1000, 2500, (data, start) -> {
            starts.add(start);
            int lo = Math.max(0, 1000 - start);
            int hi = Math.min(data.length(), 2500 - start);
            joined.append(data, lo, hi);
            return true;
        });
        assertEquals(TEXT.substring(1000, 2500), joined.toString(),
                "Fenêtre reconstituée identique à la sous-séquence");
        // Offsets globaux croissants et couvrant la plage.
        List<Integer> sorted = new ArrayList<>(starts);
        Collections.sort(sorted);
        assertEquals(sorted, starts, "Feuilles visitées en ordre croissant");
        assertTrue(starts.get(0) <= 1000, "La 1re feuille couvre le début de plage");
        assertTrue(starts.get(starts.size() - 1) + Rope.MAX_LEAF >= 2500,
                "La dernière feuille couvre la fin de plage");
    }

    @Test
    public void laVisiteArriereEstLeMiroirDeLAavant() {
        Rope rope = Rope.fromString(TEXT);
        List<String> forward = new ArrayList<>();
        List<String> backward = new ArrayList<>();
        List<Integer> fStarts = new ArrayList<>();
        List<Integer> bStarts = new ArrayList<>();
        rope.visitLeaves(300, 1600, (data, start) -> {
            forward.add(data);
            fStarts.add(start);
            return true;
        });
        rope.visitLeavesBackward(300, 1600, (data, start) -> {
            backward.add(data);
            bStarts.add(start);
            return true;
        });
        assertEquals(forward.size(), backward.size(), "Mêmes feuilles visitées");
        Collections.reverse(backward);
        Collections.reverse(bStarts);
        assertEquals(forward, backward, "Contenus identiques en miroir");
        assertEquals(fStarts, bStarts, "Offsets identiques en miroir");
    }

    @Test
    public void lArretAnticipeCoupeLaVisite() {
        Rope rope = Rope.fromString(TEXT);
        List<Integer> visited = new ArrayList<>();
        rope.visitLeaves(0, rope.length(), (data, start) -> {
            visited.add(start);
            return visited.size() < 3; // stop après 3 feuilles
        });
        assertEquals(3, visited.size(), "Exactement 3 feuilles visitées");
    }

    @Test
    public void lesPlagesVidesOuHorsBornesNeVisitentRien() {
        Rope rope = Rope.fromString(TEXT);
        int[] count = {0};
        rope.visitLeaves(0, 0, (data, start) -> { count[0]++; return true; });
        rope.visitLeaves(5, 5, (data, start) -> { count[0]++; return true; });
        rope.visitLeaves(7, 3, (data, start) -> { count[0]++; return true; });
        rope.visitLeavesBackward(5, 5, (data, start) -> { count[0]++; return true; });
        assertEquals(0, count[0], "Aucune visite sur plage vide/inversée");

        // Bornées tronquées : clip à [0, length).
        List<Integer> clipped = new ArrayList<>();
        rope.visitLeaves(-50, rope.length() + 50, (data, start) -> {
            clipped.add(start);
            return true;
        });
        assertTrue(clipped.get(0) == 0, "Clip bas : commence à la 1re feuille");
        assertTrue(clipped.get(clipped.size() - 1) + Rope.MAX_LEAF >= rope.length(),
                "Clip haut : finit sur la dernière feuille");
    }

    @Test
    public void leScanFeuilleParFeuilleEgaleLeScanParCaractere() {
        // Équivalence du port : compter via la visite feuille par feuille
        // donne EXACTEMENT le résultat du scan charAt (comportement de
        // BracketPairs.docBalance sur Rope vs CharSequence générique).
        Rope rope = Rope.fromString(TEXT);
        int[] ropeBalance = {0};
        rope.visitLeaves(0, rope.length(), (data, start) -> {
            for (int i = 0; i < data.length(); i++) {
                char ch = data.charAt(i);
                if (ch == '{') ropeBalance[0]++;
                else if (ch == '}') ropeBalance[0]--;
            }
            return true;
        });
        int stringBalance = 0;
        for (int i = 0; i < TEXT.length(); i++) {
            char ch = TEXT.charAt(i);
            if (ch == '{') stringBalance++;
            else if (ch == '}') stringBalance--;
        }
        assertEquals(stringBalance, ropeBalance[0], "Équilibre identique rope/String");
        assertEquals(0, stringBalance, "Texte équilibré → 0");
        assertTrue(!(rope instanceof Rope.Leaf), "La rope est bien ramifiée (9 feuilles)");
    }
}

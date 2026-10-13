package jo.codeeditor.session;

import jo.codeeditor.document.Selection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 « mouvements de lignes » (lot 2 jalon J6) — le moteur
 * {@link EditorSession#moveSelectedLines(boolean)} : la décision
 * multi-lignes retenue est le comportement IntelliJ/VS Code — le BLOC
 * ENTIER de lignes couvert par la sélection bouge, la sélection est
 * ré-épinglée sur le bloc déplacé.
 *
 * <ul>
 *   <li><b>Bloc multi-lignes</b> — une sélection couvrant 2 lignes
 *       déplace les 2 lignes d'un coup ;</li>
 *   <li><b>Caret seul</b> — déplace SA ligne (et le caret suit) ;</li>
 *   <li><b>Bord droit exclus</b> — une sélection finissant exactement
 *       au début de la ligne suivante ne l'inclut PAS ;</li>
 *   <li><b>No-op aux bords</b> — première ligne en haut, dernière
 *       ligne RÉELLE en bas (la queue vide après le dernier '\\n' ne
 *       compte pas — l'échanger insérerait une ligne blanche) ;</li>
 *   <li><b>Sélection ré-épinglée</b> — les offsets suivent le bloc
 *       (relatifs conservés, aller-retour stable) ;</li>
 *   <li><b>Undo</b> — un déplacement = UNE étape d'undo.</li>
 * </ul>
 *
 * @author jo@Dev
 */
public class MoveSelectedLinesTest {

    @Test
    public void blocMultiLignes_bougeEntier_versLeBas() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\nddd\n").build();
        // Sélection couvrant « bbb\ncc » (lignes 1-2).
        s.setSelection(Selection.range(4, 10));
        assertTrue(s.moveSelectedLines(true));
        assertEquals("aaa\nddd\nbbb\nccc\n", s.getText());
    }

    @Test
    public void blocMultiLignes_versLeHaut() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\nddd\n").build();
        s.setSelection(Selection.range(4, 10));
        assertTrue(s.moveSelectedLines(false));
        assertEquals("bbb\nccc\naaa\nddd\n", s.getText());
    }

    @Test
    public void caretSeul_bougeSaLigne_etLeCaretSuit() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\n").build();
        s.setSelection(5); // milieu de « bbb »
        assertTrue(s.moveSelectedLines(false));
        assertEquals("bbb\naaa\nccc\n", s.getText());
        assertEquals(1, s.getSelection().start,
                "le caret suit la ligne déplacée (ré-épinglé en relatif)");
    }

    @Test
    public void sélectionFinissantAuDébutDeLigneExclutCetteLigne() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\nddd\n").build();
        // Sélection « bbb\n » — fin EXACTEMENT au début de la ligne 2 :
        // la ligne 2 (« ccc ») n'est PAS couverte.
        s.setSelection(Selection.range(4, 8));
        assertTrue(s.moveSelectedLines(true));
        assertEquals("aaa\nccc\nbbb\nddd\n", s.getText());
    }

    @Test
    public void noOpEnPremièreLigneEtDernièreLigneRéelle() {
        EditorSession s = new SessionBuilder("aaa\nbbb\n").build();
        s.setSelection(0);
        assertFalse(s.moveSelectedLines(false),
                "première ligne : rien à faire au-dessus");
        assertEquals("aaa\nbbb\n", s.getText());

        s.setSelection(5); // ligne 1 = dernière ligne réelle (queue vide en 2)
        assertFalse(s.moveSelectedLines(true),
                "dernière ligne réelle : rien à faire en-dessous "
                + "(la queue vide ne compte pas)");
        assertEquals("aaa\nbbb\n", s.getText());
    }

    @Test
    public void queueVide_aucunDéplacementNiLigneBlancheInsérée() {
        // Caret à la toute fin (sur la queue vide) : rien à déplacer —
        // et JAMAIS de ligne blanche surprise.
        EditorSession s = new SessionBuilder("aaa\nbbb\n").build();
        s.setSelection(8);
        assertFalse(s.moveSelectedLines(false));
        assertFalse(s.moveSelectedLines(true));
        assertEquals("aaa\nbbb\n", s.getText());
    }

    @Test
    public void sélectionRéépinglée_surLeBlocDéplacé() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\nddd\n").build();
        s.setSelection(Selection.range(5, 7)); // « bb » dans la ligne 1
        s.moveSelectedLines(true);
        // La ligne « bbb » est désormais en ligne 2 (offset 8) — la
        // sélection couvre toujours « bb » dans le bloc déplacé.
        assertEquals(Selection.range(9, 11), s.getSelection());
        assertEquals("bb", s.getText().substring(9, 11));
    }

    @Test
    public void unDéplacement_uneÉtapeDUndo() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\n").build();
        s.setSelection(4);
        assertTrue(s.moveSelectedLines(true));
        assertEquals("aaa\nccc\nbbb\n", s.getText());
        // La ligne déplacée est désormais la dernière réelle : second
        // déplacement = no-op (rien à désaler).
        assertFalse(s.moveSelectedLines(true));
        s.undo();
        assertEquals("aaa\nbbb\nccc\n", s.getText());
    }

    @Test
    public void blocDéplacéSurLigneVraieVide_leTraverse() {
        // Une ligne vide AU MILIEU du document est une vraie cible.
        EditorSession s = new SessionBuilder("aaa\n\nbbb\n").build();
        s.setSelection(0); // « aaa »
        assertTrue(s.moveSelectedLines(true));
        assertEquals("\naaa\nbbb\n", s.getText());
    }

    @Test
    public void dernièreLigneSansRetourÀLaLigne_noOpVersLeBas() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc").build();
        s.setSelection(Selection.range(4, 7));
        assertTrue(s.moveSelectedLines(true),
                "un cran reste possible (au-dessus de « ccc »)");
        assertEquals("aaa\nccc\nbbb", s.getText());
        assertFalse(s.moveSelectedLines(true),
                "au plancher : plus rien en-dessous");
        assertEquals("aaa\nccc\nbbb", s.getText());
    }
}

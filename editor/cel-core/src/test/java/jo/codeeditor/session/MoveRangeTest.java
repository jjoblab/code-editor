package jo.codeeditor.session;

import jo.codeeditor.document.Selection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B13 (P1-2 « drag-déposer de bloc ») — le moteur
 * {@link EditorSession#moveRange(int, int, int)} : la plage de
 * caractères [start, end) déménage à l'offset target, suppression +
 * réinsertion en UNE étape d'annulation, sélection ré-épinglée sur
 * le texte déplacé.
 *
 * <p><b>Sémantique CARACTÈRE exacte</b> (parité TextView drag &amp;
 * drop) : target est l'offset du caret fantôme sous le doigt AVANT
 * suppression, le trou de la source garde sa longueur, l'insertion
 * n'aucune intelligence de ligne — contrairement à
 * {@code moveSelectedLines} qui est le moteur des mouvements de
 * LIGNES.</p>
 *
 * <ul>
 *   <li><b>Déplacement vers le bas</b> — target au-delà de la source
 *       est corrigé de la longueur retirée ;</li>
 *   <li><b>Déplacement vers le haut</b> — target avant la source est
 *       pris tel quel ;</li>
 *   <li><b>Dépôt dans la source</b> — [start, end] : no-op, la pile
 *       d'annulation n'est PAS polluée ;</li>
 *   <li><b>Lecture-seule / caret / bornes inversées</b> — no-op
 *       (false) ;</li>
 *   <li><b>Undo</b> — un déplacement = UNE étape (le texte revient
 *       exactement) ;</li>
 *   <li><b>Sélection ré-épinglée</b> — sur le texte déplacé, dans
 *       les DEUX sens.</li>
 * </ul>
 *
 * @author jo@Dev
 */
public class MoveRangeTest {

    // Document de référence "abcdef" : a0 b1 c2 d3 e4 f5 (len 6).

    @Test
    public void déplacementVersLeBas_texteEtSélection() {
        EditorSession s = new SessionBuilder("abcdef").build();
        // Déplace « bc » (1-3) après « d » (offset 4) : le trou garde
        // sa place, l'insertion atterrit à 4-2=2 dans « adef » →
        // « ad|bc|ef ».
        assertTrue(s.moveRange(1, 3, 4));
        assertEquals("adbcef", s.getText());
        // La sélection est ré-épinglée sur le texte déplacé.
        assertEquals(Selection.range(2, 4), s.getSelection());
        assertEquals("bc", s.getText().substring(
                s.getSelection().start, s.getSelection().end));
    }

    @Test
    public void déplacementVersLeHaut_texteEtSélection() {
        EditorSession s = new SessionBuilder("abcdef").build();
        // Déplace « ef » (4-6) au tout début (0) : insertAt = 0.
        assertTrue(s.moveRange(4, 6, 0));
        assertEquals("efabcd", s.getText());
        assertEquals(Selection.range(0, 2), s.getSelection());
        assertEquals("ef", s.getText().substring(
                s.getSelection().start, s.getSelection().end));
    }

    @Test
    public void sémantiqueCaractère_avecNouvelleLigne() {
        // « aaa\nbbb\nccc\n » — déplacer « bbb » (4-7) juste avant
        // « ccc » (8) : le trou de bbb reste (ses \n l'entourent),
        // l'insertion atterrit à 8-3=5 dans « aaa\n\nccc\n ».
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\n").build();
        assertTrue(s.moveRange(4, 7, 8));
        assertEquals("aaa\n\nbbbccc\n", s.getText(),
                "sémantique caractère : le trou garde ses séparateurs");
        assertEquals(Selection.range(5, 8), s.getSelection());
    }

    @Test
    public void blocMultiLignes_sélectionEntière() {
        // « aaa\nbbb\nccc\nddd\n » : bloc « bbb\nccc\n » (4-12, 8
        // caractères, séparateurs INCLUS) déposé à la fin (16) :
        // insertAt = 16-8 = 8 dans « aaa\nddd\n » →
        // « aaa\nddd\nbbb\nccc\n ».
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc\nddd\n").build();
        assertTrue(s.moveRange(4, 12, 16));
        assertEquals("aaa\nddd\nbbb\nccc\n", s.getText());
        assertEquals(Selection.range(8, 16), s.getSelection());
        assertEquals("bbb\nccc\n", s.getText().substring(8, 16));
    }

    @Test
    public void dépôtDansLaSource_noOp() {
        EditorSession s = new SessionBuilder("abcdef").build();
        assertFalse(s.moveRange(1, 3, 2), "dépôt strictement dans la source");
        assertFalse(s.moveRange(1, 3, 1), "dépôt au début de la source");
        assertFalse(s.moveRange(1, 3, 3), "dépôt à la fin de la source (texte identique)");
        assertEquals("abcdef", s.getText());
    }

    @Test
    public void caretEtPlageInversée_noOp() {
        EditorSession s = new SessionBuilder("aaa\nbbb\n").build();
        assertFalse(s.moveRange(4, 4, 0), "plage vide (caret) : rien à déplacer");
        assertFalse(s.moveRange(5, 4, 0), "bornes inversées : bornées puis vides");
    }

    @Test
    public void lectureSeule_noOp() {
        EditorSession s = new SessionBuilder("abcdef").build();
        s.setReadOnly(true);
        assertFalse(s.moveRange(0, 3, 5));
        assertEquals("abcdef", s.getText());
    }

    @Test
    public void bornesHorsDocument_sontBornées() {
        // « abcdef » (len 6) : end=999→6, target=999→6 → dépôt à la
        // FIN de la source clampée → no-op propre (texte identique).
        EditorSession s = new SessionBuilder("abcdef").build();
        assertFalse(s.moveRange(0, 999, 999),
                "target borné à 6 = fin de la source bornée : no-op");
        // Vrai clamp : « abc » (0-3) déposé à 999 → 6 : insertAt = 3
        // dans « def » → « defabc ».
        EditorSession s2 = new SessionBuilder("abcdef").build();
        assertTrue(s2.moveRange(0, 3, 999));
        assertEquals("defabc", s2.getText());
        assertEquals(Selection.range(3, 6), s2.getSelection());
    }

    @Test
    public void unDéplacement_uneÉtapeDUndo() {
        EditorSession s = new SessionBuilder("abcdef").build();
        assertTrue(s.moveRange(1, 3, 4));
        assertEquals("adbcef", s.getText());
        s.undo();
        assertEquals("abcdef", s.getText(),
                "un seul Ctrl+Z ramène le document exact d'origine");
        // Un SEUL undo de plus ne doit rien changer (une étape, pas deux).
        s.undo();
        assertEquals("abcdef", s.getText());
    }
}

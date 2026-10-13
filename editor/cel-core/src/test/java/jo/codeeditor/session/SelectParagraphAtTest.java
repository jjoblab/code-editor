package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B10 — la sélection de PARAGRAPHE (4e tap de la cascade
 * caret → mot → ligne → paragraphe) : le bloc maximal de lignes
 * contiguës NON VIDES autour de l'offset, borné par les lignes vides
 * ou blanches et les extrémités du document — la fin EXCLUT le saut
 * de ligne (parité selectLineAt : lineEnd est l'offset du '\n').
 */
class SelectParagraphAtTest {

    // Lines (index: contenu) et débuts d'offset :
    // 0 "package jo;"   @0    ┐ paragraphe A (lignes 0)
    // 1 ""             @12   ┘ borne
    // 2 "public class A {" @13 ┐
    // 3 "    int x = 0;" @30  │ paragraphe B (lignes 2-4)
    // 4 "}"            @45   ┘
    // 5 ""             @47   borne
    // 6 ""             @48   ligne vide (tap = caret, parité ligne)
    // 7 "   "          @49   ligne blanche (borne)
    // 8 ""             @53   borne
    // 9 "fin"          @54   paragraphe C (ligne 9, fin de doc)
    private static final String DOC =
        "package jo;\n"
        + "\n"
        + "public class A {\n"
        + "    int x = 0;\n"
        + "}\n"
        + "\n"
        + "\n"
        + "   \n"
        + "\n"
        + "fin\n";

    @Test
    void paragrapheMilieuBornéParLesLignesVides() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        // Tape dans « int x = 0; » (ligne 3) : le bloc non vide = lignes 2-4.
        s.selectParagraphAt(30);
        assertEquals(13, s.getSelection().start,
                "début = début de la ligne 2 (13)");
        assertEquals(46, s.getSelection().end,
                "fin = lineEnd(4) = 46 (offset du saut, exclu)");
    }

    @Test
    void paragrapheAuDébutDuDocument() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        // Tape dans « package jo; » (ligne 0) : la ligne 1 vide borne.
        s.selectParagraphAt(2);
        assertEquals(0, s.getSelection().start);
        assertEquals(11, s.getSelection().end,
                "fin = lineEnd(0) = 11 (le saut de ligne est EXCLU)");
    }

    @Test
    void paragrapheEnFinDeDocument() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        // Tape dans « fin » (ligne 9) : ligne 8 vide devant, doc finit là.
        s.selectParagraphAt(54);
        assertEquals(54, s.getSelection().start);
        assertEquals(57, s.getSelection().end,
                "fin = lineEnd(9) = 57 : « fin » sans son saut");
    }

    @Test
    void tapSurLigneVide_seRéduitAuCaret_paritéLigne() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        // Ligne 6 vide : le bloc « non vide » la contenant est elle-même,
        // vide → sélection dégénérée au caret (même dégénérescence que
        // selectLineAt sur une ligne vide : les deux bornes = 48).
        s.selectParagraphAt(48);
        assertEquals(48, s.getSelection().start);
        assertEquals(48, s.getSelection().end);
    }

    @Test
    void lignesBlanchesEspacesSontDesBornes() {
        // « a\n \nb » : la ligne blanche «   » sépare les paragraphes.
        EditorSession s = new EditorSession(EditorDocument.of("a\n \nb"));
        s.selectParagraphAt(0);
        assertEquals(0, s.getSelection().start);
        assertEquals(1, s.getSelection().end,
                "« a » seul : la ligne blanche est une borne");
    }

    @Test
    void documentDuneLigne_paragrapheEntier() {
        EditorSession s = new EditorSession(EditorDocument.of("seule ligne"));
        s.selectParagraphAt(4);
        assertEquals(0, s.getSelection().start);
        assertEquals("seule ligne".length(), s.getSelection().end);
    }

    @Test
    void cascadeLignePuisParagrapheBornesDistinctes() {
        // La ligne = UNE ligne sans son saut ; le paragraphe = TOUTES les
        // lignes du bloc sans le saut FINAL seulement.
        EditorSession s = new EditorSession(EditorDocument.of("l1\nl2\nl3"));
        s.selectLineAt(3);
        assertEquals(3, s.getSelection().start, "ligne seule : début de l2");
        assertEquals(5, s.getSelection().end, "ligne seule : fin de l2 (sans saut)");
        s.selectParagraphAt(3);
        assertEquals(0, s.getSelection().start, "paragraphe : début de l1");
        assertEquals(8, s.getSelection().end, "paragraphe : fin de l3 (sans saut)");
    }

    @Test
    void offsetHorsBornes_estClampé() {
        EditorSession s = new EditorSession(EditorDocument.of("l1\nl2"));
        // lineForOffset borne l'index de ligne — pas d'exception attendue.
        // Le clamp ramène à la dernière ligne ; le PARAGRAPHE couvre le
        // bloc (les deux lignes ici).
        s.selectParagraphAt(9999);
        assertEquals(0, s.getSelection().start,
                "clamp : le paragraphe couvre tout le bloc");
        assertEquals(5, s.getSelection().end);
        s.selectParagraphAt(-5);
        assertEquals(0, s.getSelection().start, "clamp : même bloc");
        assertEquals(5, s.getSelection().end);
    }
}

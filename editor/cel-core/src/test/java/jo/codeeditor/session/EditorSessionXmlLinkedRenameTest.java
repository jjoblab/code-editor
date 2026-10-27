package jo.codeeditor.session;

import org.junit.jupiter.api.Test;

import jo.codeeditor.document.EditorDocument;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renommage lié des balises XML (4.5.0) : la frappe et le backspace
 * DANS le nom d'une balise ouvrante se reflètent sur la fermante
 * appariée — les deux éditions en UNE SEULE étape d'annulation,
 * l'appariement respecte les balises homonymes imbriquées, les
 * auto-fermantes, les commentaires/CDATA, et la liaison ne s'applique
 * qu'aux langages de la famille XML (HTML/SVG via alias).
 */
class EditorSessionXmlLinkedRenameTest {

    private EditorSession sessionXml(String texte) {
        EditorSession session = new EditorSession(EditorDocument.of(texte));
        session.setLanguage("xml");
        return session;
    }

    private static void caretPosé(EditorSession session, int offset) {
        session.setSelection(offset);
    }

    // ── Frappe liée ──────────────────────────────────────────────

    @Test
    void frappeDansLeNomOuvert_fermanteSuit() {
        EditorSession s = sessionXml("<di></di>");
        // Caret après « di » : frappe de « v » complète les DEUX noms.
        caretPosé(s, 3);
        s.typeChar('v');
        assertEquals("<div></div>", s.getText());
    }

    @Test
    void frappeEnFinDeNom_fermanteSuit() {
        EditorSession s = sessionXml("<dat></dat>");
        // Caret à la fin du nom ouvrant : « a » s'ajoute aux deux noms.
        caretPosé(s, 4);
        s.typeChar('a');
        assertEquals("<data></data>", s.getText());
    }

    @Test
    void frappeAuMilieuDuNom_alignéeDansLaFermante() {
        EditorSession s = sessionXml("<dv></dv>");
        // Caret entre « d » et « v » : « i » s'insère au même rang des
        // deux noms.
        caretPosé(s, 2);
        s.typeChar('i');
        assertEquals("<div></div>", s.getText());
    }

    @Test
    void caretDansUnAttribut_pasDeLiaison() {
        EditorSession s = sessionXml("<div class=\"x\"></div>");
        // Caret après le « x » de la valeur d'attribut (offset 13) :
        // frappe standard, la fermante ne suit pas.
        caretPosé(s, 13);
        s.typeChar('y');
        assertEquals("<div class=\"xy\"></div>", s.getText());
    }

    @Test
    void caretHorsBalise_pasDeLiaison() {
        EditorSession s = sessionXml("<div>txt</div>");
        caretPosé(s, 7);
        s.typeChar('a');
        assertEquals("<div>txat</div>", s.getText());
    }

    @Test
    void editionDuNomFermant_pasDeLiaisonInverse() {
        // Éditer la FERMANTE ne touche pas l'ouvrante : caret à la fin
        // du nom fermant (offset 10), insertion simple.
        EditorSession s = sessionXml("<div></div>");
        caretPosé(s, 10);
        s.typeChar('x');
        assertEquals("<div></divx>", s.getText());
    }

    // ── Backspace lié ────────────────────────────────────────────

    @Test
    void backspaceDansLeNom_fermanteSuit() {
        EditorSession s = sessionXml("<div></div>");
        // Effacer le « v » final des deux noms.
        caretPosé(s, 4);
        s.backspace();
        assertEquals("<di></di>", s.getText());
    }

    @Test
    void backspaceAuDébutDuNom_pasDeLiaison() {
        // Caret juste après « < » : rien à supprimer DANS le nom —
        // chemin standard (suppression du « < » par le backspace malin
        // ou ordinaire selon SmartTyping).
        EditorSession s = sessionXml("<div></div>");
        caretPosé(s, 1);
        s.backspace();
        assertNotEquals("<div></div>", s.getText());
    }

    // ── Appariement ──────────────────────────────────────────────

    @Test
    void balisesHomonymesImbriquées_laBonneFermanteSuit() {
        EditorSession s = sessionXml("<div><div></div></div>");
        // Édition du PREMIER « div » : sa fermante est la DERNIÈRE.
        caretPosé(s, 4);
        s.typeChar('x');
        assertEquals("<divx><div></div></divx>", s.getText());
    }

    @Test
    void autoFermante_pasDeLiaison() {
        EditorSession s = sessionXml("<br/><br/>");
        caretPosé(s, 3);
        s.typeChar('x');
        // « brx/ » — pas de fermante à lier, insertion simple.
        assertEquals("<brx/><br/>", s.getText());
    }

    @Test
    void fermanteAprèsCommentaireEtCdata_trouvée() {
        EditorSession s = sessionXml(
                "<item><!-- <item> --> <![CDATA[ <item> ]]> <item/></item>");
        // Le commentaire, la section CDATA et l'auto-fermante ne
        // comptent pas : la fermante réelle suit.
        caretPosé(s, 5);
        s.typeChar('2');
        assertEquals(
                "<item2><!-- <item> --> <![CDATA[ <item> ]]> <item/></item2>",
                s.getText());
    }

    // ── Une étape d'annulation ───────────────────────────────────

    @Test
    void frappeLiée_uneSeuleÉtapeDundo() {
        EditorSession s = sessionXml("<div></div>");
        caretPosé(s, 4);
        s.typeChar('x');       // → <divx></divx>
        caretPosé(s, 5);
        s.typeChar('y');       // → <divxy></divxy>
        assertEquals("<divxy></divxy>", s.getText());

        // UNE annulation retire le « y » des DEUX noms.
        assertTrue(s.undo());
        assertEquals("<divx></divx>", s.getText());
        // La suivante retire le « x » des deux.
        assertTrue(s.undo());
        assertEquals("<div></div>", s.getText());
    }

    @Test
    void backspaceLié_uneSeuleÉtapeDundo() {
        EditorSession s = sessionXml("<div></div>");
        caretPosé(s, 4);
        s.backspace();         // → <di></di>
        assertEquals("<di></di>", s.getText());
        assertTrue(s.undo());
        assertEquals("<div></div>", s.getText());
    }

    // ── Famille de langage ───────────────────────────────────────

    @Test
    void htmlAlias_bénéficieDeLaLiaison() {
        EditorSession s = new EditorSession(EditorDocument.of("<div></div>"));
        s.setLanguage("html");
        caretPosé(s, 4);
        s.typeChar('x');
        assertEquals("<divx></divx>", s.getText());
    }

    @Test
    void java_pasDeLiaison() {
        EditorSession s = new EditorSession(EditorDocument.of("<div></div>"));
        s.setLanguage("java");
        caretPosé(s, 4);
        s.typeChar('x');
        // Insertion simple : la fermante ne suit pas hors famille XML.
        assertEquals("<divx></div>", s.getText());
    }

    // ── Bascule publique (4.6.0) ────────────────────────────────

    @Test
    void bascule_activeParDéfaut() {
        EditorSession s = sessionXml("<div></div>");
        assertTrue(s.isLinkedTagEditsEnabled(),
                "le miroir est actif sans configuration, comme en 4.5.0");
    }

    @Test
    void basculeFermee_frappeSansMiroir() {
        EditorSession s = sessionXml("<di></di>");
        s.setLinkedTagEditsEnabled(false);
        caretPosé(s, 3);
        s.typeChar('v');
        // Chemin standard : seule l'ouvrante reçoit le « v ».
        assertEquals("<div></di>", s.getText());
    }

    @Test
    void basculeFermee_backspaceSansMiroir() {
        EditorSession s = sessionXml("<div></div>");
        s.setLinkedTagEditsEnabled(false);
        caretPosé(s, 4);
        s.backspace();
        // Chemin standard : seul le « v » de l'ouvrante part.
        assertEquals("<di></div>", s.getText());
    }

    @Test
    void réactivation_leMiroirRevient() {
        EditorSession s = sessionXml("<di></di>");
        s.setLinkedTagEditsEnabled(false);
        s.setLinkedTagEditsEnabled(true);
        caretPosé(s, 3);
        s.typeChar('v');
        assertEquals("<div></div>", s.getText());
    }

    @Test
    void basculeFermee_uneSeuleÉtapeDundoStandard() {
        // Fermée, chaque édition du nom est UNE insertion ordinaire —
        // un undo retire le caractère unique, pas une paire.
        EditorSession s = sessionXml("<di></di>");
        s.setLinkedTagEditsEnabled(false);
        caretPosé(s, 3);
        s.typeChar('v');
        assertTrue(s.undo());
        assertEquals("<di></di>", s.getText());
    }
}

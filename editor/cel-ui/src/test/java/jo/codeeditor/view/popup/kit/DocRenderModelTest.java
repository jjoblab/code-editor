package jo.codeeditor.view.popup.kit;

import java.util.List;

import org.junit.Test;

import jo.codeeditor.doc.QuickDoc;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Modèle de rendu unifié de la documentation (Phase 3) : blocs typés
 * [SIGNATURE, SUMMARY, PARAM*, RETURNS, THROWS, SEE, AUTRE*] issus
 * d'un {@code QuickDocContent}, et LANGUE d'analyse prise dans la
 * session — jamais un identifiant de langage en dur (« java »).
 */
public class DocRenderModelTest {

    /** Blocs d'une documentation Java complète (fence + tags). */
    private static List<DocRenderModel.Block> blocsJava() {
        String doc = "```java\nString format(String pattern, Object... args)\n```\n"
                + "Retourne une chaîne formatée selon le motif.\n"
                + "@param pattern le motif avec spécificateurs\n"
                + "@param args les valeurs substituées\n"
                + "@return la chaîne formatée\n"
                + "@throws NullPointerException si pattern est null\n"
                + "@see Locale\n"
                + "@since 1.5";
        return DocRenderModel.blocks(DocRenderModel.parse(doc, null));
    }

    @Test
    public void structure_lesBlocsDansLordreDuDocument() {
        List<DocRenderModel.Block> b = blocsJava();
        assertEquals(8, b.size());
        assertEquals(DocRenderModel.KIND_SIGNATURE, b.get(0).kind);
        assertEquals(DocRenderModel.KIND_SUMMARY, b.get(1).kind);
        assertEquals(DocRenderModel.KIND_PARAM, b.get(2).kind);
        assertEquals(DocRenderModel.KIND_PARAM, b.get(3).kind);
        assertEquals(DocRenderModel.KIND_RETURNS, b.get(4).kind);
        assertEquals(DocRenderModel.KIND_THROWS, b.get(5).kind);
        assertEquals(DocRenderModel.KIND_SEE, b.get(6).kind);
        // @since n'est pas typé : il survit en bloc AUTRE.
        assertEquals(DocRenderModel.KIND_OTHER, b.get(7).kind);
        assertEquals("@since", b.get(7).title);
    }

    @Test
    public void signatureEtRésumé_portésTelsQuels() {
        List<DocRenderModel.Block> b = blocsJava();
        assertTrue("le fence devient le bloc signature",
                b.get(0).text.contains("String format("));
        assertEquals("Retourne une chaîne formatée selon le motif.",
                b.get(1).text);
        assertEquals("", b.get(1).title);
    }

    @Test
    public void param_libelléEtTexteSéparés() {
        List<DocRenderModel.Block> b = blocsJava();
        DocRenderModel.Block p = b.get(2);
        assertEquals("pattern", p.label);
        assertEquals("le motif avec spécificateurs", p.text);
        assertEquals("@param", p.title);
        // Compatibilité : la ligne plate recompose l'item historique.
        assertEquals("@param pattern le motif avec spécificateurs", p.ligne());
    }

    @Test
    public void throws_libelléEtTexteSéparés() {
        List<DocRenderModel.Block> b = blocsJava();
        DocRenderModel.Block t = b.get(5);
        assertEquals("NullPointerException", t.label);
        assertEquals("si pattern est null", t.text);
    }

    @Test
    public void sectionsNonTypées_jamaisPerdues() {
        // @since n'est ni param/return/throws/see : le contenu survit
        // en bloc AUTRE (aucune information perdue par le modèle).
        String doc = "Une description.\n@since 1.5\n@author Olson";
        List<DocRenderModel.Block> b = DocRenderModel.blocks(
                QuickDoc.parseQuickDoc(doc, "java"));
        assertEquals(3, b.size());
        assertEquals(DocRenderModel.KIND_OTHER, b.get(1).kind);
        assertEquals("@since", b.get(1).title);
        assertEquals("1.5", b.get(1).text);
        assertEquals(DocRenderModel.KIND_OTHER, b.get(2).kind);
        assertEquals("Olson", b.get(2).text);
    }

    @Test
    public void exception_kotlin_lesAliasSontReconnus() {
        // Même grammaire de tags côté Kotlin (@throws, @param) et
        // alias @exception : le modèle est indifférent au langage —
        // c'est la SESSION qui porte la langue d'analyse.
        String doc = "Ajoute un élément.\n"
                + "@param élément l'élément à ajouter\n"
                + "@exception IllegalArgumentException si l'élément est vide\n"
                + "@returns true si la liste a changé";
        List<DocRenderModel.Block> b = DocRenderModel.blocks(
                DocRenderModel.parse(doc, null));
        assertEquals(4, b.size());
        assertEquals("élément", b.get(1).label);
        assertEquals(DocRenderModel.KIND_THROWS, b.get(2).kind);
        assertEquals("IllegalArgumentException", b.get(2).label);
        assertEquals(DocRenderModel.KIND_RETURNS, b.get(3).kind);
        assertEquals("true si la liste a changé", b.get(3).text);
    }

    @Test
    public void param_sansDescription_leNomReste() {
        String doc = "Fait quelque chose.\n@param seul";
        List<DocRenderModel.Block> b = DocRenderModel.blocks(
                QuickDoc.parseQuickDoc(doc, "java"));
        // Sans texte après le nom, le libellé peut rester vide et le
        // nom survit dans le texte — aucun bloc perdu.
        assertEquals(2, b.size());
        assertEquals(DocRenderModel.KIND_PARAM, b.get(1).kind);
        assertTrue("le nom survit", b.get(1).ligne().contains("seul"));
    }

    @Test
    public void contenuVide_ouNull_aucunBloc() {
        assertTrue(DocRenderModel.blocks(null).isEmpty());
        assertTrue(DocRenderModel.blocks(
                QuickDoc.parseQuickDoc("", "java")).isEmpty());
        assertTrue(DocRenderModel.blocks(
                QuickDoc.parseQuickDoc(null, "kotlin")).isEmpty());
    }

    // ── Langue : la session commande, jamais de « java » en dur ───

    @Test
    public void langueDe_laSessionFaitFoi() {
        EditorSession s = new EditorSession(
                jo.codeeditor.document.EditorDocument.of("x = 1"));
        s.setLanguage("python");
        assertEquals("python", DocRenderModel.langueDe(s));
        assertEquals("", DocRenderModel.langueDe(null));
    }

    @Test
    public void langueDe_sessionSansLangue_chaîneVide() {
        EditorSession s = new EditorSession(
                jo.codeeditor.document.EditorDocument.of("texte"));
        // Langue non posée → chaîne vide, jamais un identifiant en dur.
        String langue = DocRenderModel.langueDe(s);
        // Le contrat exact : ce que dit la session, rien d'autre.
        assertEquals(s.getLanguage() == null ? "" : s.getLanguage(), langue);
    }
}

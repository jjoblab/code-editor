package jo.codeeditor.blocks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M-03 — contrat de l'éditeur par blocs : l'API publique déclarée
 * (FEATURES §« Édition par blocs ») n'avait AUCUN test. Ce contrat
 * couvre la partie MODELÉE (arbre de blocs) : parse d'une source
 * structurée, structure (profondeur, comptage, localisation par
 * offset), cycle setSource/getRoot.
 */
public class BlockEditorContractTest {

    private static final String SRC =
            "class A {\n"
            + "    int x = 1;\n"
            + "    void m() {\n"
            + "        x = 2;\n"
            + "    }\n"
            + "}\n";

    @Test
    public void parse_sourceStructurée_arbreNonVide() {
        BlockEditor.BlockNode racine = BlockEditor.BlockParser.parse(SRC);
        assertNotNull(racine);
        assertEquals(BlockEditor.BlockType.BLOCK, racine.type);
        assertEquals("la racine couvre la source entière",
                SRC.length(), racine.endOffset - racine.startOffset);
        assertTrue("la classe est découpée en enfants", racine.hasChildren());
        assertTrue("au moins un niveau d'imbrication", racine.depth() >= 2);
        assertTrue("la racine + au moins la déclaration de classe",
                racine.nodeCount() >= 2);
    }

    @Test
    public void parse_sourceVide_arbreVideSansException() {
        BlockEditor.BlockNode racine = BlockEditor.BlockParser.parse("");
        assertNotNull("le contrat historique : arbre vide, pas null", racine);
        assertEquals("la racine existe même vide (elle EST le nœud fichier)", 1, racine.nodeCount());
    }

    @Test
    public void nodeAt_offsetDansLaMéthode_trouveLeNœud() {
        BlockEditor.BlockNode racine = BlockEditor.BlockParser.parse(SRC);
        int offsetMéthode = SRC.indexOf("void m()");
        BlockEditor.BlockNode n = racine.nodeAt(offsetMéthode + 2);
        assertNotNull("un nœud couvre l'offset de la méthode", n);
        assertTrue("le nœud trouvé couvre bien l'offset demandé",
                n.startOffset <= offsetMéthode && offsetMéthode < n.endOffset);
        // Hors document : null.
        assertNull(racine.nodeAt(SRC.length() + 100));
    }

    @Test
    public void setSource_getRoot_cycleComplet() {
        BlockEditor editor = new BlockEditor();
        editor.setSource(SRC);
        BlockEditor.BlockNode racine = editor.getRoot();
        assertNotNull("setSource pose l'arbre racine", racine);
        assertTrue(racine.nodeCount() >= 2);
        // Re-set : l'arbre est remplacé — la source courte produit un
        // arbre plus petit ou égal, jamais une accumulation.
        editor.setSource("int a;");
        assertTrue(editor.getRoot().nodeCount() <= racine.nodeCount());
        assertTrue("mais jamais vide", editor.getRoot().nodeCount() >= 1);
    }

    @Test
    public void toString_lisibleSansException() {
        BlockEditor.BlockNode racine = BlockEditor.BlockParser.parse(SRC);
        String s = racine.toString();
        assertNotNull(s);
        assertTrue(!s.isEmpty());
    }
}

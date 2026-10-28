package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Les symboles du mini Java (4.14.0) — références et renommage avec
 * des PORTÉES honnêtes : le local ne compte que dans SA méthode, le
 * champ cède la place à l'ombre de ses locaux, la méthode surchargée
 * ne se renomme pas, la constante d'enum se qualifie, et l'inconnu ne
 * sert rien.
 */
public class MiniJavaSymbolesTest {

    /** Le caret AU MILIEU de la première occurrence d'un fragment. */
    private static int dans(String texte, String fragment) {
        int i = texte.indexOf(fragment);
        assertTrue("fragment absent : " + fragment, i >= 0);
        return i + Math.max(1, fragment.length() / 2);
    }

    /** Le caret AU MILIEU du DERNIER segment d'un nom qualifié
     *  (« Couleur.ROUGE » → dans « ROUGE », jamais dans « Couleur »). */
    private static int dansQualifié(String texte, String qualifié) {
        int i = texte.indexOf(qualifié);
        assertTrue("fragment absent : " + qualifié, i >= 0);
        int aprèsPoint = qualifié.indexOf('.') + 1;
        int longueur = qualifié.length() - aprèsPoint;
        return i + aprèsPoint + Math.max(1, longueur / 2);
    }

    /** Une occurrence vit-elle DANS le fragment (l'usage du mot à
     *  l'intérieur de la zone citée) ? */
    private static boolean couvre(List<int[]> occurrences, String texte,
            String fragment) {
        int i = texte.indexOf(fragment);
        assertTrue("fragment absent : " + fragment, i >= 0);
        int fin = i + fragment.length();
        for (int[] o : occurrences) {
            if (o[0] >= i && o[1] <= fin) return true;
        }
        return false;
    }

    private static final String ATELIER = """
            class Atelier {
                private final List<String> history = new ArrayList<>();

                public int add(int a, int b) {
                    history.add(a + " + " + b);
                    int total = a + b;
                    return total;
                }

                public int autre(int total) {
                    return total * 2;
                }
            }
            """;

    // ── Locaux et paramètres : la portée est la MÉTHODE ─────────

    @Test
    public void local_compteDansSaMéthodeSeulement() {
        // « total » local de add(…), paramètre de autre(…) : les
        // occurrences de add ne regardent que add.
        List<int[]> refs = MiniJavaSymboles.références(
                ATELIER, dans(ATELIER, "int total = a + b;"));
        // Usages DANS add : « return total; » — la déclaration exclue,
        // le paramètre de autre(…) ignoré.
        assertEquals(1, refs.size());
        assertTrue(couvre(refs, ATELIER, "return total;"));
        // Le paramètre de autre(…) n'est PAS le nôtre.
        assertTrue(!couvre(refs, ATELIER, "return total * 2;"));
    }

    @Test
    public void local_leRenommageCouvreDéclarationEtUsages() {
        List<int[]> occurrences = MiniJavaSymboles.occurrencesDuRenommage(
                ATELIER, dans(ATELIER, "int total = a + b;"));
        assertEquals(2, occurrences.size());
        assertTrue(couvre(occurrences, ATELIER, "int total = a + b;"));
        assertTrue(couvre(occurrences, ATELIER, "return total;"));
        // Le paramètre « total » de autre(…) n'est PAS le nôtre.
        assertTrue(!couvre(occurrences, ATELIER, "autre(int total)"));
    }

    @Test
    public void paramètre_leRenommageCouvreLaMéthode() {
        List<int[]> occurrences = MiniJavaSymboles.occurrencesDuRenommage(
                ATELIER, dans(ATELIER, "int total)"));
        assertTrue(occurrences != null && occurrences.size() >= 2);
        assertTrue(couvre(occurrences, ATELIER, "autre(int total)"));
        assertTrue(couvre(occurrences, ATELIER, "return total * 2;"));
        // … et jamais le local de add(…).
        assertTrue(!couvre(occurrences, ATELIER, "int total = a + b;"));
    }

    // ── Champs : l'ombre des locaux ─────────────────────────────

    @Test
    public void champ_compteDansLeTypeHorsOmbres() {
        String code = "class A {\n"
                + "    List<String> journal = new ArrayList<>();\n"
                + "    void un() { journal.add(\"x\"); }\n"
                + "    void deux() {\n"
                + "        int journal = 3;\n"
                + "        return journal;\n"
                + "    }\n"
                + "}\n";
        List<int[]> refs = MiniJavaSymboles.références(
                code, dans(code, "journal = new"));
        // L'usage de un() compte ; le local journal de deux() le
        // MASQUE — ses occurrences sont à lui.
        assertEquals(1, refs.size());
        assertTrue(couvre(refs, code, "journal.add(\"x\")"));
    }

    // ── Méthodes : appels, surcharges, qualification ────────────

    @Test
    public void méthode_lesRéférencesSontLesAppels() {
        String code = "class A {\n"
                + "    int calculer(int base) { return base; }\n"
                + "    void un() { int x = calculer(2); }\n"
                + "    void deux() { int y = this.calculer(3); }\n"
                + "    void trois() { int z = A.calculer(4); }\n"
                + "}\n";
        List<int[]> refs = MiniJavaSymboles.références(
                code, dans(code, "int calculer(int base)"));
        // Les trois appels — nu, this-qualifié, type-qualifié ; la
        // déclaration exclue.
        assertEquals(3, refs.size());
        assertTrue(couvre(refs, code, "calculer(2)"));
        assertTrue(couvre(refs, code, "this.calculer(3)"));
        assertTrue(couvre(refs, code, "A.calculer(4)"));
        List<int[]> tout = MiniJavaSymboles.occurrencesDuRenommage(
                code, dans(code, "int calculer(int base)"));
        assertEquals(4, tout.size());
    }

    @Test
    public void méthodeSurchargée_leRenommageEstRefusé() {
        String code = "class A {\n"
                + "    int dessiner(int x) { return x; }\n"
                + "    int dessiner(int x, int y) { return x + y; }\n"
                + "    void t() { dessiner(1); dessiner(1, 2); }\n"
                + "}\n";
        // Les références se servent (les appels du nom)…
        List<int[]> refs = MiniJavaSymboles.références(
                code, dans(code, "dessiner(int x)"));
        assertEquals(2, refs.size());
        // … mais le renommage d'un nom SURCHARGÉ est REFUSÉ.
        assertNull(MiniJavaSymboles.préparation(
                code, dans(code, "dessiner(int x)")));
        assertNull(MiniJavaSymboles.occurrencesDuRenommage(
                code, dans(code, "dessiner(1);")));
    }

    @Test
    public void appelDunAutreType_neComptePas() {
        // « append » n'est pas déclaré dans le fichier : rien.
        String code = "class A { void t() { StringBuilder sb = new StringBuilder();"
                + " sb.append(\"x\"); } }";
        assertTrue(MiniJavaSymboles.références(
                code, dans(code, "sb.append(")).isEmpty());
        assertNull(MiniJavaSymboles.occurrencesDuRenommage(
                code, dans(code, "sb.append(")));
    }

    // ── Types ───────────────────────────────────────────────────

    @Test
    public void type_lesRéférencesSontLesUsages() {
        String code = "class Voiture {\n"
                + "    static Voiture créer() { return new Voiture(); }\n"
                + "}\n"
                + "class Essai { Voiture v = new Voiture(); }\n";
        List<int[]> refs = MiniJavaSymboles.références(
                code, dans(code, "class Voiture"));
        // La déclaration exclue ; les QUATRE usages comptent (retour,
        // new, champ, new).
        assertEquals(4, refs.size());
        List<int[]> tout = MiniJavaSymboles.occurrencesDuRenommage(
                code, dans(code, "class Voiture"));
        assertEquals(5, tout.size());
        assertTrue(couvre(tout, code, "class Voiture"));
    }

    // ── Constantes d'enum : la qualification tranche ────────────

    @Test
    public void constante_lesUsagesQualifiésEtNusComptent() {
        String code = "class A {\n"
                + "    enum Couleur { ROUGE, VERT }\n"
                + "    void t(Couleur c) {\n"
                + "        Couleur r = Couleur.ROUGE;\n"
                + "        switch (c) { case ROUGE -> break; }\n"
                + "    }\n"
                + "}\n";
        List<int[]> refs = MiniJavaSymboles.références(
                code, dansQualifié(code, "Couleur.ROUGE"));
        // L'usage qualifié + l'étiquette NUE du switch ; la constante
        // déclarée exclue.
        assertEquals(2, refs.size());
        assertTrue(couvre(refs, code, "Couleur.ROUGE;"));
        assertTrue(couvre(refs, code, "case ROUGE"));
        List<int[]> tout = MiniJavaSymboles.occurrencesDuRenommage(
                code, dansQualifié(code, "Couleur.ROUGE;"));
        assertEquals(3, tout.size());
    }

    @Test
    public void constanteDunAutreEnum_neComptePas() {
        String code = "class A {\n"
                + "    enum Couleur { ROUGE, VERT }\n"
                + "    enum Feu { ROUGE, ORANGE }\n"
                + "    void t() { Couleur c = Couleur.ROUGE; "
                + "Feu f = Feu.ROUGE; }\n"
                + "}\n";
        // Deux enums déclarent ROUGE : l'ambiguïté REFUSE tout —
        // références VIDES, renommage refusé.
        assertTrue(MiniJavaSymboles.références(
                code, dansQualifié(code, "Couleur.ROUGE;")).isEmpty());
        assertNull(MiniJavaSymboles.préparation(
                code, dansQualifié(code, "Couleur.ROUGE;")));
        assertNull(MiniJavaSymboles.occurrencesDuRenommage(
                code, dansQualifié(code, "Couleur.ROUGE;")));
    }

    @Test
    public void constanteQualifiéeParVariable_compte() {
        String code = "class A {\n"
                + "    enum Couleur { ROUGE, VERT }\n"
                + "    void t(Couleur c) { if (c == Couleur.ROUGE) { } }\n"
                + "}\n";
        List<int[]> refs = MiniJavaSymboles.références(
                code, dansQualifié(code, "Couleur.ROUGE"));
        assertEquals(1, refs.size());
    }

    // ── Masquage et inconnus ────────────────────────────────────

    @Test
    public void chaîneEtCommentaire_neSontPasDesRéférences() {
        String code = "class A {\n"
                + "    int total = 1;\n"
                + "    void t() {\n"
                + "        String s = \"total\";\n"
                + "        // int total = 2;\n"
                + "        int u = total;\n"
                + "    }\n"
                + "}\n";
        List<int[]> refs = MiniJavaSymboles.références(
                code, dans(code, "int total = 1;"));
        // Le champ n'a qu'UN usage réel : « int u = total; » — la
        // chaîne et le commentaire ne comptent pas.
        assertEquals(1, refs.size());
        assertTrue(couvre(refs, code, "u = total;"));
    }

    @Test
    public void inconnu_neSertRien() {
        // « ArrayList » : un type du RUNTIME, pas du fichier — aucune
        // référence fichier, aucun renommage.
        assertTrue(MiniJavaSymboles.références(
                ATELIER, dans(ATELIER, "ArrayList")).isEmpty());
        assertNull(MiniJavaSymboles.préparation(
                ATELIER, dans(ATELIER, "ArrayList")));
        assertNull(MiniJavaSymboles.occurrencesDuRenommage(
                ATELIER, dans(ATELIER, "ArrayList")));
    }

    @Test
    public void préparation_donneLaPlageDuMot() {
        String code = "class A { void t() { int total = 1; return total; } }";
        int[] mot = MiniJavaSymboles.préparation(
                code, dans(code, "int total"));
        assertNotNull(mot);
        assertEquals("total", code.substring(mot[0], mot[1]));
    }
}

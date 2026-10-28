package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Le moteur d'aide de signature réelle du mini Java — les VRAIES
 * surcharges de la méthode appelée (4.11.0) : bibliothèque embarquée
 * par récepteur, méthodes déclarées dans le buffer (javadoc
 * {@code @param} comprise), variables typées, repli par nom — et le
 * paramètre actif qui suit les virgules de profondeur 0.
 */
public class MiniJavaSignaturesTest {

    /** L'offset juste après un fragment donné (le caret de frappe). */
    private static int après(String prefixe) {
        return prefixe.length();
    }

    private static List<MiniJavaSignatures.Entry> résoudre(String texte, int caret) {
        MiniJavaSignatures.Result r = MiniJavaSignatures.resolve(texte, caret);
        return r != null ? r.signatures : null;
    }

    // ── Bibliothèque embarquée : les vraies surcharges ────────────

    @Test
    public void systemOutPrintln_sertLesVraiesSurcharges() {
        List<MiniJavaSignatures.Entry> sigs =
                résoudre("class A { void t() { System.out.println(", après("class A { void t() { System.out.println("));
        assertNotNull(sigs);
        assertTrue("au moins 7 surcharges d'impression",
                sigs.size() >= 7);
        assertEquals("println()", sigs.get(0).label);
        assertEquals("println(String x)", sigs.get(1).label);
        // La documentation du paramètre est servie (bande doc du popup).
        assertEquals("le texte à imprimer", sigs.get(1).paramDocs.get(0));
    }

    @Test
    public void parametreActif_suitLesVirgulesDeProfondeurZero() {
        String code = "class A { void t() { System.out.println(\"a, b\", 42, ";
        MiniJavaSignatures.Result r = MiniJavaSignatures.resolve(code, code.length());
        assertNotNull(r);
        // « a, b » est une CHAÎNE : sa virgule ne compte pas ; 42 puis
        // la virgule frappée → paramètre actif = 2.
        assertEquals(2, r.activeParameter);
    }

    @Test
    public void parametreActif_ignoreLesVirgulesImbriquées() {
        String code = "System.out.println(Math.max(1, 2), ";
        MiniJavaSignatures.Result r = MiniJavaSignatures.resolve(code, code.length());
        assertNotNull(r);
        assertEquals("la virgule de max(1, 2) est imbriquée : actif = 1",
                1, r.activeParameter);
    }

    @Test
    public void mathMax_deuxSurcharges() {
        List<MiniJavaSignatures.Entry> sigs = résoudre("Math.max(", 9);
        assertNotNull(sigs);
        assertEquals(2, sigs.size());
        assertEquals("max(int a, int b)", sigs.get(0).label);
        assertEquals("max(double a, double b)", sigs.get(1).label);
    }

    @Test
    public void variableTypée_sertLaTableDeSonType() {
        String code = "class A { void t() { List<String> items = null; items.add(";
        List<MiniJavaSignatures.Entry> sigs = résoudre(code, code.length());
        assertNotNull(sigs);
        assertEquals("la variable List sert la table des collections",
                "add(E e)", sigs.get(0).label);
        assertEquals("add(int index, E e)", sigs.get(1).label);
    }

    @Test
    public void variableString_sertLaTableDesChaînes() {
        String code = "class A { void t() { String nom = \"x\"; nom.substring(";
        List<MiniJavaSignatures.Entry> sigs = résoudre(code, code.length());
        assertNotNull(sigs);
        assertEquals("substring(int début)", sigs.get(0).label);
        assertEquals("substring(int début, int fin)", sigs.get(1).label);
    }

    // ── Méthodes déclarées dans le buffer ─────────────────────────

    @Test
    public void methodeDeclaringDuBuffer_sertSaSignatureRéelle() {
        String code = "class Atelier {\n"
                + "    /**\n"
                + "     * Calcule le total.\n"
                + "     * @param base le montant de base\n"
                + "     * @param taux le taux applicable\n"
                + "     */\n"
                + "    double calculerTotal(double base, double taux) {\n"
                + "        return base * taux;\n"
                + "    }\n"
                + "    void t() { calculerTotal(";
        MiniJavaSignatures.Result r = MiniJavaSignatures.resolve(code, code.length());
        assertNotNull(r);
        assertEquals(1, r.signatures.size());
        MiniJavaSignatures.Entry e = r.signatures.get(0);
        assertEquals("calculerTotal(double base, double taux)", e.label);
        // La javadoc @param devient la documentation des paramètres.
        assertEquals("le montant de base", e.paramDocs.get(0));
        assertEquals("le taux applicable", e.paramDocs.get(1));
    }

    @Test
    public void variableDeClasseDéclarée_sertSesPropresMéthodes() {
        String code = "class Voiture {\n"
                + "    void démarrer(String clé) { }\n"
                + "    void t() { Voiture v = new Voiture(); v.démarrer(";
        List<MiniJavaSignatures.Entry> sigs = résoudre(code, code.length());
        assertNotNull(sigs);
        assertEquals("démarrer(String clé)", sigs.get(0).label);
    }

    @Test
    public void appelQualifiéParLaClasse_méthodeStatiqueDuBuffer() {
        String code = "class Voiture {\n"
                + "    static Voiture créer(String marque) { return null; }\n"
                + "    void t() { Voiture.créer(";
        List<MiniJavaSignatures.Entry> sigs = résoudre(code, code.length());
        assertNotNull(sigs);
        assertEquals("créer(String marque)", sigs.get(0).label);
    }

    // ── Repli par nom et cas négatifs ─────────────────────────────

    @Test
    public void appelNu_inconnuDuBuffer_replieSurLeNomSeul() {
        List<MiniJavaSignatures.Entry> sigs = résoudre("append(", 7);
        assertNotNull("le nom nu trouve la bibliothèque (append → bâtisseur)",
                sigs);
        assertEquals("append(String s)", sigs.get(0).label);
    }

    @Test
    public void methodeInconnue_renvoieNull() {
        assertNull("aucune signature connue → null (le client garde son repli)",
                MiniJavaSignatures.resolve("zzzinconnue(", 12));
    }

    @Test
    public void horsDUnAppel_renvoieNull() {
        assertNull(MiniJavaSignatures.resolve("int a = 1 + 2; ", 15));
        assertNull("caret à 0 : jamais dans un appel",
                MiniJavaSignatures.resolve("foo(", 0));
    }

    @Test
    public void parenthèseDansUneChaîneOuUnCommentaire_neComptePas() {
        // La '(' du commentaire n'ouvre pas d'appel : le caret n'est
        // dans AUCUN appel vivant.
        assertNull(MiniJavaSignatures.resolve(
                "int a = 0; // foo(bar\nint b = 1; ", 35));
        // Une virgule dans une chaîne ne change pas le paramètre actif.
        String code = "System.out.println(\"un, deux\"";
        MiniJavaSignatures.Result r = MiniJavaSignatures.resolve(code, code.length());
        assertNotNull(r);
        assertEquals(0, r.activeParameter);
    }
}

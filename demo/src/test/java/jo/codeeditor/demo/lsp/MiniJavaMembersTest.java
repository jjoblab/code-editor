package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Le moteur des membres manquants du mini Java (P1-C, 4.12.0) : la
 * détection des membres abstraits non implémentés (interface, classe
 * abstraite, héritage transitif, méthodes default exclues, classes
 * abstract excusées) et le gabarit des stubs {@code @Override}.
 */
public class MiniJavaMembersTest {

    private static List<MiniJavaMembers.Manquant> manquants(String code) {
        return MiniJavaMembers.manquants(code);
    }

    private static MiniJavaMembers.Manquant unique(String code) {
        List<MiniJavaMembers.Manquant> ms = manquants(code);
        assertEquals("une seule classe incomplète", 1, ms.size());
        return ms.get(0);
    }

    // ── Détection ────────────────────────────────────────────────

    @Test
    public void classeImplémentantUneInterface_estSignalée() {
        String code = "interface Véhicule {\n"
                + "    void démarrer(String clé);\n"
                + "    int vitesseMax();\n"
                + "}\n"
                + "class Voiture implements Véhicule {\n"
                + "}\n";
        MiniJavaMembers.Manquant m = unique(code);
        assertEquals("Voiture", m.classe);
        assertEquals(2, m.manquants.size());
        assertTrue(m.message().contains("Voiture"));
        assertTrue(m.message().contains("démarrer(String clé)"));
        assertTrue(m.message().contains("vitesseMax()"));
    }

    @Test
    public void classeComplète_nEstPasSignalée() {
        String code = "interface Véhicule {\n"
                + "    void démarrer(String clé);\n"
                + "}\n"
                + "class Voiture implements Véhicule {\n"
                + "    public void démarrer(String clé) { }\n"
                + "}\n";
        assertTrue(manquants(code).isEmpty());
    }

    @Test
    public void aritéDifférente_leMembreResteManquant() {
        // Une méthode de même nom mais d'autre arité n'implémente rien.
        String code = "interface Liste {\n"
                + "    void add(int x);\n"
                + "}\n"
                + "class MaListe implements Liste {\n"
                + "    public void add(int x, int y) { }\n"
                + "}\n";
        MiniJavaMembers.Manquant m = unique(code);
        assertEquals(1, m.manquants.size());
        assertEquals("add", m.manquants.get(0).nom);
    }

    @Test
    public void classeAbstraiteIncomplète_estExcusée() {
        String code = "interface Véhicule {\n"
                + "    void démarrer();\n"
                + "}\n"
                + "abstract class Proto implements Véhicule {\n"
                + "}\n";
        assertTrue(manquants(code).isEmpty());
    }

    @Test
    public void méthodeSansCorpsDansUneClasseAbstraite_estDue() {
        String code = "abstract class Forme {\n"
                + "    abstract double surface();\n"
                + "    public String nom() { return \"?\"; }\n"
                + "}\n"
                + "class Cercle extends Forme {\n"
                + "}\n";
        MiniJavaMembers.Manquant m = unique(code);
        assertEquals("Cercle", m.classe);
        assertEquals(1, m.manquants.size());
        assertEquals("surface", m.manquants.get(0).nom);
        assertEquals("double", m.manquants.get(0).retour);
    }

    @Test
    public void méthodesDefaultEtStaticDUneInterface_neSontPasDues() {
        String code = "interface Affichable {\n"
                + "    void afficher();\n"
                + "    default String étiquette() { return \"?\"; }\n"
                + "    static Affichable vide() { return null; }\n"
                + "}\n"
                + "class Panneau implements Affichable {\n"
                + "    public void afficher() { }\n"
                + "}\n";
        assertTrue(manquants(code).isEmpty());
    }

    @Test
    public void héritageTransitif_lesGrandsParentsComptent() {
        String code = "interface A {\n"
                + "    void a();\n"
                + "}\n"
                + "interface B extends A {\n"
                + "    void b();\n"
                + "}\n"
                + "class C implements B {\n"
                + "    public void b() { }\n"
                + "}\n";
        MiniJavaMembers.Manquant m = unique(code);
        assertEquals(1, m.manquants.size());
        assertEquals("a", m.manquants.get(0).nom);
    }

    @Test
    public void parentsExternes_sontIgnorés() {
        // Runnable vit hors du buffer : impossible de savoir ce qu'il
        // exige — aucune détection (le contrat « zéro faux positif »).
        String code = "class Tâche implements Runnable {\n}\n";
        assertTrue(manquants(code).isEmpty());
    }

    @Test
    public void typeImbriqué_sesMembresRestentÀLui() {
        String code = "interface I {\n"
                + "    void a();\n"
                + "}\n"
                + "class Hôte {\n"
                + "    class Interne implements I {\n"
                + "        public void a() { }\n"
                + "    }\n"
                + "}\n";
        assertTrue("le membre du type imbriqué n'est pas dû à l'hôte",
                manquants(code).isEmpty());
    }

    // ── Gabarit des stubs ────────────────────────────────────────

    @Test
    public void gabarit_génèreLesStubsAvecRetoursParDéfaut() {
        String code = "interface Véhicule {\n"
                + "    void démarrer(String clé);\n"
                + "    int vitesseMax();\n"
                + "    boolean estRapide();\n"
                + "    String nom();\n"
                + "}\n"
                + "class Voiture implements Véhicule {\n"
                + "}\n";
        MiniJavaMembers.Manquant m = unique(code);
        String g = MiniJavaMembers.gabarit(m);
        assertTrue(g.contains("@Override"));
        assertTrue(g.contains("public void démarrer(String clé) {"));
        assertTrue("int → return 0;", g.contains("public int vitesseMax() {"));
        assertTrue(g.contains("return 0;"));
        assertTrue(g.contains("public boolean estRapide() {"));
        assertTrue(g.contains("return false;"));
        assertTrue(g.contains("public String nom() {"));
        assertTrue(g.contains("return null;"));
        assertTrue(g.contains("// TODO : à implémenter"));
        // L'insertion se fait avant la fermante : le gabarit commence
        // par un saut de ligne.
        assertTrue(g.startsWith("\n"));
    }

    @Test
    public void gabarit_inséréAvantLaFermante_leCodeEstComplet() {
        String code = "interface I {\n"
                + "    void a();\n"
                + "}\n"
                + "class X implements I {\n"
                + "    int déjàLà = 1;\n"
                + "}\n";
        MiniJavaMembers.Manquant m = unique(code);
        // L'insertion vit à la fermante de X.
        assertEquals('}', code.charAt(m.insertion));
        String réparé = code.substring(0, m.insertion)
                + MiniJavaMembers.gabarit(m)
                + code.substring(m.insertion);
        // Le code réparé n'a PLUS de membre manquant.
        assertTrue("le diagnostic disparaît après le quickfix",
                manquants(réparé).isEmpty());
    }

    @Test
    public void lIndentationSuitCelleDeLaClasse() {
        String code = "interface I {\n"
                + "    void a();\n"
                + "}\n"
                + "        class X implements I {\n"
                + "        }\n";
        MiniJavaMembers.Manquant m = unique(code);
        String g = MiniJavaMembers.gabarit(m);
        assertTrue("le stub vit un cran plus profond que la classe",
                g.contains("\n            @Override"));
    }

    // ── Le diagnostic ────────────────────────────────────────────

    @Test
    public void leMessageConnaîtLeSingulierEtLePluriel() {
        MiniJavaMembers.Manquant un = unique("interface I { void a(); }\n"
                + "class X implements I { }\n");
        assertTrue(un.message().contains("le membre hérité"));
        MiniJavaMembers.Manquant deux = unique("interface I { void a(); void b(); }\n"
                + "class X implements I { }\n");
        assertTrue(deux.message().contains("les 2 membres hérités"));
    }

    @Test
    public void leNomDeLaClassePorteLeDiagnostic() {
        String code = "interface I { void a(); }\n"
                + "class MonImpl implements I { }\n";
        MiniJavaMembers.Manquant m = unique(code);
        assertEquals("MonImpl", code.substring(m.nomStart, m.nomEnd));
    }
}

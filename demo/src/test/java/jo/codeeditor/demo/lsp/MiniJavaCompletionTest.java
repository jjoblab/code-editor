package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Le moteur de complétion contextuelle du mini Java (P1-B, 4.12.0) :
 * les constantes d'enum après « . », les attributs d'annotation dans
 * « @Anno( », les arguments nommés à une position d'argument d'appel —
 * et les contextes qui ne déclenchent RIEN (déclarations, têtes de
 * contrôle, positions de valeur, récepteurs inconnus).
 */
public class MiniJavaCompletionTest {

    /** L'offset juste après un fragment donné (le caret de frappe). */
    private static int après(String prefixe) {
        return prefixe.length();
    }

    private static List<MiniJavaCompletion.Candidate> candidats(String texte, int caret) {
        return MiniJavaCompletion.candidates(texte, caret);
    }

    private static MiniJavaCompletion.Candidate chercher(
            List<MiniJavaCompletion.Candidate> cs, String label) {
        for (MiniJavaCompletion.Candidate c : cs) {
            if (label.equals(c.label)) return c;
        }
        return null;
    }

    private static final String ENUM_BASE = "class Atelier {\n"
            + "    enum Couleur { ROUGE, VERT, BLEU;\n"
            + "        public String hexa() { return \"#f00\"; }\n"
            + "    }\n"
            + "    void t() { ";

    // ── 1. Constantes d'enum ─────────────────────────────────────

    @Test
    public void aprèsLePoint_sertLesConstantesDeLEnum() {
        String code = ENUM_BASE + "Couleur.";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertNotNull(cs);
        // Les trois constantes, kind EnumMember (badge « # »).
        MiniJavaCompletion.Candidate rouge = chercher(cs, "ROUGE");
        MiniJavaCompletion.Candidate vert = chercher(cs, "VERT");
        MiniJavaCompletion.Candidate bleu = chercher(cs, "BLEU");
        assertNotNull(rouge);
        assertNotNull(vert);
        assertNotNull(bleu);
        assertEquals(20, rouge.kind);
        assertEquals("constante de Couleur", rouge.detail);
        // Les constantes AVANT les méthodes (ordre d'affichage).
        assertTrue("les constantes précèdent hexa",
                cs.indexOf(rouge) < cs.indexOf(chercher(cs, "hexa")));
        // La méthode déclarée dans l'enum, kind Method.
        MiniJavaCompletion.Candidate hexa = chercher(cs, "hexa");
        assertNotNull(hexa);
        assertEquals(2, hexa.kind);
        // Les méthodes de protocole.
        assertNotNull(chercher(cs, "values"));
        assertNotNull(chercher(cs, "valueOf"));
    }

    @Test
    public void variableDeTypeEnum_sertLesMêmesConstantes() {
        String code = ENUM_BASE + "Couleur c = Couleur.ROUGE; c.";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertNotNull(chercher(cs, "ROUGE"));
        assertNotNull(chercher(cs, "hexa"));
    }

    @Test
    public void récepteurInconnu_aucunCandidatEnum() {
        // Un récepteur qui n'est ni un enum ni une variable d'enum : la
        // famille ne s'applique pas (la base du serveur prend le relais).
        String code = "class A { void t() { System.out.println(\"x\"); chaineInconnue.";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
    }

    // ── 2. Attributs d'annotation ────────────────────────────────

    @Test
    public void dansLesParenthèses_sertLesAttributsAvecInsertionNomÉgal() {
        String code = "class Atelier {\n"
                + "    @interface Trace {\n"
                + "        String nom();\n"
                + "        boolean verbose() default false;\n"
                + "    }\n"
                + "    @Trace(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        MiniJavaCompletion.Candidate nom = chercher(cs, "nom");
        MiniJavaCompletion.Candidate verbose = chercher(cs, "verbose");
        assertNotNull(nom);
        assertNotNull(verbose);
        // L'insertion pose le nom + « = ».
        assertEquals("nom=", nom.insertText);
        assertEquals("verbose=", verbose.insertText);
        // Le détail porte le type, le défaut quand il existe.
        assertEquals("String", nom.detail);
        assertTrue(verbose.detail.contains("boolean"));
        assertTrue(verbose.detail.contains("false"));
        // Kind Property (badge « F »).
        assertEquals(10, nom.kind);
    }

    @Test
    public void attributsDéjàPosés_neSontPasReProposés() {
        String code = "class Atelier {\n"
                + "    @interface Trace {\n"
                + "        String nom();\n"
                + "        boolean verbose() default false;\n"
                + "    }\n"
                + "    @Trace(nom = \"x\", ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue("nom est déjà posé", chercher(cs, "nom") == null);
        assertNotNull("verbose reste à poser", chercher(cs, "verbose"));
    }

    @Test
    public void annotationDuJDK_valueDeSuppressWarnings() {
        String code = "class A { @SuppressWarnings(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        MiniJavaCompletion.Candidate value = chercher(cs, "value");
        assertNotNull(value);
        assertEquals("value=", value.insertText);
        assertTrue(value.detail.contains("String[]"));
    }

    @Test
    public void positionDeValeur_aprèsLeÉgal_aucunAttribut() {
        // Après le « = » c'est une position de VALEUR : pas d'attribut.
        String code = "class Atelier {\n"
                + "    @interface Trace { String nom(); }\n"
                + "    @Trace(nom = ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
    }

    // ── 3. Arguments nommés ──────────────────────────────────────

    @Test
    public void appelDéclaré_sertLesNomsDesParamètres() {
        String code = "class Atelier {\n"
                + "    double calculerTotal(double base, double taux) {\n"
                + "        return base * taux;\n"
                + "    }\n"
                + "    void t() { calculerTotal(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        MiniJavaCompletion.Candidate base = chercher(cs, "base");
        assertNotNull("le paramètre actif (position 0) est servi", base);
        assertEquals("base=", base.insertText);
        assertEquals(6, base.kind);
        assertEquals("parameter", base.tag);
        assertTrue(base.detail.contains("calculerTotal"));
        // Le candidat suit la POSITION (comme l'aide de signature) :
        // à l'ouverture, seul le premier paramètre.
        assertTrue("taux attend la virgule", chercher(cs, "taux") == null);
    }

    @Test
    public void paramètreActif_suitLaVirgule() {
        // Après la virgule : le paramètre actif est le SECOND —
        // le même moteur que l'aide de signature.
        String code = "class Atelier {\n"
                + "    double calculerTotal(double base, double taux) {\n"
                + "        return base * taux;\n"
                + "    }\n"
                + "    void t() { calculerTotal(1.0, ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertNotNull("taux (paramètre actif 1)", chercher(cs, "taux"));
        assertTrue("base n'est plus servi à la position 1",
                chercher(cs, "base") == null);
    }

    @Test
    public void nomDéjàPosé_neSertQueLeSuivant() {
        String code = "class Atelier {\n"
                + "    double calculerTotal(double base, double taux) {\n"
                + "        return base * taux;\n"
                + "    }\n"
                + "    void t() { calculerTotal(base = 1.0, ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue("base= déjà posé", chercher(cs, "base") == null);
        assertNotNull(chercher(cs, "taux"));
    }

    @Test
    public void appelEmboîté_sertLesParamètresDeLAppelInterne() {
        String code = "class A { void t() { System.out.println(Math.max(1, ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        // Le paramètre actif de max est « b » — pas les paramètres de
        // println (les deux popups regardent le MÊME appel interne).
        MiniJavaCompletion.Candidate b = chercher(cs, "b");
        assertNotNull(b);
        assertTrue(b.detail.contains("max"));
    }

    @Test
    public void surchargesDeLaBibliothèque_dédupliquéesParNom() {
        String code = "class A { void t() { System.out.println(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        MiniJavaCompletion.Candidate x = chercher(cs, "x");
        assertNotNull("toutes les surcharges nomment leur paramètre x", x);
        assertEquals(1, cs.stream().filter(c -> "x".equals(c.label)).count());
    }

    // ── Contextes qui ne déclenchent RIEN ────────────────────────

    @Test
    public void déclarationDeMéthode_aucunArgumentNommé() {
        // « void f( » est une DÉCLARATION — pas un appel.
        String code = "class A {\n    void f(int a, ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
    }

    @Test
    public void déclarationGénérique_aucunArgumentNommé() {
        String code = "class A {\n    List<String> f(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
    }

    @Test
    public void têteDeContrôle_aucunArgumentNommé() {
        String code = "class A { void t() { if (";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
        String code2 = "class A { void t() { for (int i = 0; i < 3; ";
        assertTrue(candidats(code2, après(code2)).isEmpty());
    }

    @Test
    public void appelStatique_etArgumentsNommés() {
        // « Math.max( » : un VRAI appel (pas de déclaration) — le point
        // devant le nom dément l'indice de déclaration.
        String code = "class A { void t() { Math.max(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertNotNull("le paramètre actif 0 de la surcharge", chercher(cs, "a"));
        // Après la virgule, c'est le tour de « b ».
        String code2 = "class A { void t() { Math.max(1, ";
        List<MiniJavaCompletion.Candidate> cs2 = candidats(code2, après(code2));
        assertNotNull(chercher(cs2, "b"));
    }

    @Test
    public void aprèsLeReturn_lesAppelsRestentDesAppels() {
        // « return max( » : le mot d'avant est « return », pas un type —
        // c'est un appel, les arguments nommés s'appliquent. Même la
        // méthode inconnue du buffer trouve la bibliothèque par son NOM
        // (le repli par nom de l'aide de signature : max → les maths).
        String code = "class A { int t() { return max(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        MiniJavaCompletion.Candidate a = chercher(cs, "a");
        assertNotNull("le repli par nom sert les maths", a);
        assertTrue(a.detail.contains("max"));
        String code2 = "class A {\n"
                + "    int max(int a, int b) { return a > b ? a : b; }\n"
                + "    int t() { return max(";
        List<MiniJavaCompletion.Candidate> cs2 = candidats(code2, après(code2));
        assertNotNull(chercher(cs2, "a"));
    }

    @Test
    public void positionDExpression_aucunArgumentNommé() {
        // « foo(a + » : le « + » signe une expression, pas un début
        // d'argument nommé.
        String code = "class A {\n"
                + "    int f(int a, int b) { return 0; }\n"
                + "    void t() { f(1 + ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
    }

    @Test
    public void parenthèsesEtMotsDansLesChaînes_neComptentPas() {
        // Le point-virgule et la parenthèse dans la CHAÎNE ne comptent
        // pas : le caret n'est dans aucun appel vivant.
        String code = "class A { void t() { String s = \")(\"; ";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
    }

    @Test
    public void méthodeSansParamètres_aucunArgumentNommé() {
        // println() : zéro paramètre — après la '(' il n'y a rien à nommer.
        String code = "class A {\n"
                + "    int taille() { return 0; }\n"
                + "    void t() { taille(";
        List<MiniJavaCompletion.Candidate> cs = candidats(code, après(code));
        assertTrue(cs.isEmpty());
    }
}

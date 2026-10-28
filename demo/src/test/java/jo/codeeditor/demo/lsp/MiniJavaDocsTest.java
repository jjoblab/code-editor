package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * La documentation au SURVOL du mini Java (4.13.0) — les QUATRE
 * sources du moteur, dans l'ordre du plus local au plus lointain :
 * le buffer (constante d'enum, attribut d'annotation, méthode,
 * variable, type déclaré), la bibliothèque embarquée, le classpath
 * par réflexion — et RIEN pour un mot inconnu (jamais de doc
 * inventée).
 */
public class MiniJavaDocsTest {

    /** L'offset de la PREMIÈRE occurrence d'un fragment. */
    private static int sur(String texte, String fragment) {
        int i = texte.indexOf(fragment);
        assertTrue("fragment absent : " + fragment, i >= 0);
        return i;
    }

    /** Le caret AU MILIEU du mot donné (première occurrence). */
    private static int dans(String texte, String mot) {
        int i = sur(texte, mot);
        return i + Math.max(1, mot.length() / 2);
    }

    private static final String ATELIER = """
            package demo;

            import java.util.List;

            public final class Calculator {
                private final List<String> history = new java.util.ArrayList<>();
                private static final double TAX_RATE = 0.20;

                public enum Couleur { ROUGE, VERT, BLEU;
                    public String hexa() { return "#ff0000"; }
                }

                public @interface Trace {
                    String nom();
                    boolean verbose() default false;
                }

                public int add(int a, int b) {
                    history.add(a + " + " + b);
                    return a + b;
                }

                /** Étiquette une opération.
                 * @param étiquette le texte de l'étiquette
                 * @param valeur le nombre affiché
                 */
                public String label(String étiquette, int valeur) {
                    return étiquette + " -> " + valeur;
                }

                public void imprimer() {
                    history.forEach(h -> System.out.println(h));
                }

                @Override
                public String toString() { return String.join("; ", history); }
            }
            """;

    // ── Méthodes : bibliothèque embarquée ──────────────────────

    @Test
    public void méthodeDeLaTable_sertSignatureEtParamètres() {
        String code = "class A { void t() { int m = Math.max(1, 2); } }";
        String md = MiniJavaDocs.survol(code, dans(code, "max"));
        assertNotNull(md);
        assertTrue(md.contains("```java"));
        assertTrue(md.contains("max(int a, int b)"));
        assertTrue(md.contains("Le plus grand des deux entiers."));
        assertTrue(md.contains("@param a le premier entier"));
        assertTrue(md.contains("@param b le second entier"));
    }

    @Test
    public void méthodeDeVariable_sertLesSurchargesDeSonType() {
        String code = "class A { void t() { List<String> l = null; l.add(1); } }";
        String md = MiniJavaDocs.survol(code, dans(code, "add"));
        assertNotNull(md);
        assertTrue(md.contains("add(E e)"));
        assertTrue(md.contains("add(int index, E e)"));
        assertTrue(md.contains("Ajoute l'élément à la fin de la liste."));
    }

    // ── Méthodes : buffer (javadoc @param comprise) ────────────

    @Test
    public void méthodeDéclarée_sertSaJavadoc() {
        String md = MiniJavaDocs.survol(ATELIER, dans(ATELIER, "label("));
        assertNotNull(md);
        assertTrue(md.contains("label(String étiquette, int valeur)"));
        assertTrue(md.contains("@param étiquette le texte de l'étiquette"));
        assertTrue(md.contains("@param valeur le nombre affiché"));
    }

    @Test
    public void méthodeDéclarée_nue_estTrouvéeSansRécepteur() {
        String code = "class A { void hexa() {} void t() { hexa(); } }";
        String md = MiniJavaDocs.survol(code, code.indexOf("hexa()"));
        assertNotNull(md);
        assertTrue(md.contains("hexa()"));
    }

    // ── Méthodes : classpath par réflexion ─────────────────────

    @Test
    public void méthodeClasspath_sertLaSignatureRéelle() {
        String code = "class A { void t() { int n = Integer.parseInt(\"3\"); } }";
        String md = MiniJavaDocs.survol(code, dans(code, "parseInt"));
        assertNotNull(md);
        assertTrue(md.contains("parseInt("));
        assertTrue(md.contains("java.lang.Integer"));
        assertTrue(md.contains("réflexion"));
    }

    @Test
    public void typeDéclaréDansLeFichier_bloqueLeRepliClasspath() {
        // Un « Integer » DÉCLARÉ dans le fichier : le hors-fichier ne
        // le contredit pas — parseInt inconnu du fichier ne sert rien.
        String code = "class Integer { void go() {} } "
                + "class A { void t() { Integer.parseInt(3); } }";
        String md = MiniJavaDocs.survol(code, dans(code, "parseInt"));
        assertNull(md);
    }

    // ── Variables ──────────────────────────────────────────────

    @Test
    public void variable_sertSaDéclarationEtLaFicheDeSonType() {
        String md = MiniJavaDocs.survol(ATELIER, dans(ATELIER, "history"));
        assertNotNull(md);
        assertTrue(md.contains("```java\nList<String> history\n```"));
        assertTrue(md.contains("Variable déclarée dans ce fichier"));
    }

    @Test
    public void variableDeEnum_listeLesConstantes() {
        String code = "class A { enum Couleur { ROUGE, VERT } "
                + "void t() { Couleur c = Couleur.ROUGE; } }";
        String md = MiniJavaDocs.survol(code, dans(code, "c ="));
        assertNotNull(md);
        assertTrue(md.contains("Couleur c"));
        assertTrue(md.contains("Type enum Couleur"));
        assertTrue(md.contains("ROUGE, VERT"));
    }

    // ── Constantes d'enum ──────────────────────────────────────

    @Test
    public void constanteParRécepteur_sertSonOrdinal() {
        String code = "class A { enum Couleur { ROUGE, VERT, BLEU }"
                + " void t() { Couleur c = Couleur.ROUGE; } }";
        // Le caret sur la CONSTANTE de l'usage qualifié.
        String md = MiniJavaDocs.survol(code, dans(code, "ROUGE;"));
        assertNotNull(md);
        assertTrue(md.contains("Couleur.ROUGE"));
        assertTrue(md.contains("ordinal 0"));
        assertTrue(md.contains("Constantes : ROUGE, VERT, BLEU"));
    }

    @Test
    public void constanteDansLeCorps_sertSonOrdinal() {
        String code = "class A { enum Couleur { ROUGE, VERT, BLEU } }";
        String md = MiniJavaDocs.survol(code, dans(code, "VERT"));
        assertNotNull(md);
        assertTrue(md.contains("ordinal 1"));
    }

    // ── Attributs d'annotation ─────────────────────────────────

    @Test
    public void attributPosé_sertTypeEtDéfaut() {
        String code = "class A { @interface Trace { String nom(); "
                + "boolean verbose() default false; } "
                + "@Trace(nom = \"x\") void t() {} }";
        String md = MiniJavaDocs.survol(code, dans(code, "nom ="));
        assertNotNull(md);
        assertTrue(md.contains("String nom"));
        assertTrue(md.contains("sans valeur par défaut"));
    }

    @Test
    public void attributAvecDéfaut_leDit() {
        String code = "class A { @interface Trace { boolean verbose() default false; } "
                + "@Trace(verbose = true) void t() {} }";
        String md = MiniJavaDocs.survol(code, dans(code, "verbose ="));
        assertNotNull(md);
        assertTrue(md.contains("boolean verbose"));
        assertTrue(md.contains("valeur par défaut : false"));
    }

    // ── Types ──────────────────────────────────────────────────

    @Test
    public void typeDéclaredEnum_sertDéclarationEtConstantes() {
        String md = MiniJavaDocs.survol(ATELIER, dans(ATELIER, "enum Couleur"));
        assertNotNull(md);
        assertTrue(md.contains("enum Couleur"));
        assertTrue(md.contains("Constantes : ROUGE, VERT, BLEU"));
    }

    @Test
    public void typeClasspath_sertLaFicheDeRéflexion() {
        String code = "class A { void t() { Integer n = 3; } }";
        String md = MiniJavaDocs.survol(code, dans(code, "Integer n"));
        assertNotNull(md);
        assertTrue(md.contains("class java.lang.Integer"));
        assertTrue(md.contains("réflexion"));
    }

    @Test
    public void typeAnnotationDéclarée_listeSesAttributs() {
        String md = MiniJavaDocs.survol(ATELIER, dans(ATELIER, "Trace {"));
        assertNotNull(md);
        assertTrue(md.contains("@interface Trace"));
        assertTrue(md.contains("Attributs : nom, verbose"));
    }

    // ── Rien pour l'inconnu ────────────────────────────────────

    @Test
    public void motClé_neSertRien() {
        assertNull(MiniJavaDocs.survol(ATELIER, dans(ATELIER, "return")));
    }

    @Test
    public void motInconnu_neSertRien() {
        String code = "class A { void t() { int zzq = 1; } }";
        assertNull(MiniJavaDocs.survol(code, dans(code, "zzq")));
    }

    @Test
    public void horsTexte_neSertRien() {
        assertNull(MiniJavaDocs.survol(ATELIER, 0));
        assertNull(MiniJavaDocs.survol(ATELIER, ATELIER.length()));
        assertNull(MiniJavaDocs.survol(null, 5));
    }

    @Test
    public void chaîneEtCommentaire_neSontPasDesSymboles() {
        // Un appel DANS un commentaire : le qualificatif « Math. » y
        // serait lisible sans le masquage — le survol ne dit rien.
        String code = "class A { void t() {"
                + " String s = \"Math.max(1, 2)\";"
                + " // int m = Math.max(1, 2);\n int x = 1; } }";
        assertNull(MiniJavaDocs.survol(code, dans(code, "// int m = Math.max(")));
        assertNull(MiniJavaDocs.survol(code, dans(code, "\"Math.max(")));
    }

    // ── Le classpath : le résolveur lui-même ───────────────────

    @Test
    public void classpath_résoutLesPréfixesCourants() {
        assertNotNull(MiniJavaClasspath.classe("Integer"));
        assertNotNull(MiniJavaClasspath.classe("Arrays"));
        assertNotNull(MiniJavaClasspath.classe("LocalDate"));
        assertNull(MiniJavaClasspath.classe("ZzqInconnu"));
    }

    @Test
    public void classpath_méthodesDéterministes() {
        java.util.List<MiniJavaSignatures.Entry> sigs =
                MiniJavaClasspath.méthodesRécepteur("Integer", "parseInt");
        assertTrue(sigs.size() >= 1);
        assertTrue(sigs.get(0).label.startsWith("parseInt("));
        // L'ordre est stable : les libellés se répètent à l'identique.
        java.util.List<MiniJavaSignatures.Entry> bis =
                MiniJavaClasspath.méthodesRécepteur("Integer", "parseInt");
        assertEquals(sigs.size(), bis.size());
        for (int i = 0; i < sigs.size(); i++) {
            assertEquals(sigs.get(i).label, bis.get(i).label);
        }
    }
}

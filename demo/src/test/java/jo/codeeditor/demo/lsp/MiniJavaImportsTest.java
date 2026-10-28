package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * L'auto-import du mini Java (4.14.0) — le nom simple capitalisé du
 * caret, résolu au CLASSPATH par réflexion, devient la ligne
 * « import » au bon endroit : après la dernière importation, sinon
 * après le paquet, sinon en tête. Déjà importé, type du fichier,
 * java.lang, chaîne ou commentaire : RIEN (zéro bruit).
 */
public class MiniJavaImportsTest {

    /** Le caret AU MILIEU de la première occurrence d'un fragment. */
    private static int dans(String texte, String fragment) {
        int i = texte.indexOf(fragment);
        assertTrue("fragment absent : " + fragment, i >= 0);
        return i + Math.max(1, fragment.length() / 2);
    }

    @Test
    public void nomDuClasspath_sertLeVraiFqn() {
        String code = "package demo;\n"
                + "\n"
                + "class A {\n"
                + "    void t() { LocalDate d = LocalDate.now(); }\n"
                + "}\n";
        MiniJavaImports.Importation importation =
                MiniJavaImports.importation(code, dans(code, "LocalDate d"));
        assertNotNull(importation);
        assertEquals("java.time.LocalDate", importation.fqn);
        assertEquals("import java.time.LocalDate;\n", importation.ligne);
        // Aucun import existant : l'insertion suit le PAQUET (juste
        // après sa ligne — la ligne vide qui suit reste après).
        assertEquals(code.indexOf('\n') + 1, importation.insertion);
    }

    @Test
    public void importsExistants_lInsertionSuitLeDernier() {
        String code = "package demo;\n"
                + "\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "\n"
                + "class A {\n"
                + "    void t() { LocalDate d = LocalDate.now(); }\n"
                + "}\n";
        MiniJavaImports.Importation importation =
                MiniJavaImports.importation(code, dans(code, "LocalDate d"));
        assertNotNull(importation);
        int finMap = code.indexOf("import java.util.Map;")
                + "import java.util.Map;".length() + 1;
        assertEquals(finMap, importation.insertion);
    }

    @Test
    public void déjàImporté_neSertRien() {
        String code = "package demo;\n"
                + "import java.time.LocalDate;\n"
                + "class A { void t() { LocalDate d = LocalDate.now(); } }\n";
        assertNull(MiniJavaImports.importation(
                code, dans(code, "LocalDate d")));
    }

    @Test
    public void wildcardDuPaquet_neSertRien() {
        String code = "package demo;\n"
                + "import java.time.*;\n"
                + "class A { void t() { LocalDate d = LocalDate.now(); } }\n";
        assertNull(MiniJavaImports.importation(
                code, dans(code, "LocalDate d")));
    }

    @Test
    public void autreNomMêmeSimple_neSertRien() {
        // « import a.b.Autre; » rend « Autre » visible — mais LocalDate
        // n'est couvert par AUCUN import : il se sert. Un import qui
        // finit par un AUTRE nom ne couvre rien.
        String code = "package demo;\n"
                + "import java.util.Locale;\n"
                + "class A { void t() { LocalDate d = LocalDate.now(); } }\n";
        assertNotNull(MiniJavaImports.importation(
                code, dans(code, "LocalDate d")));
    }

    @Test
    public void typeDéclaréDansLeFichier_neSertRien() {
        String code = "package demo;\n"
                + "class LocalDate { }\n"
                + "class A { void t() { LocalDate d = new LocalDate(); } }\n";
        assertNull(MiniJavaImports.importation(
                code, dans(code, "LocalDate d =")));
    }

    @Test
    public void javaLang_neSertRien() {
        String code = "class A { void t() { Integer n = Integer.parseInt(\"1\"); } }";
        assertNull(MiniJavaImports.importation(code, dans(code, "Integer n")));
    }

    @Test
    public void minuscule_etInconnu_neServentRien() {
        String code = "class A { void t() { int total = 1; "
                + "ZzqInconnu z = null; } }";
        assertNull(MiniJavaImports.importation(code, dans(code, "total")));
        assertNull(MiniJavaImports.importation(code, dans(code, "ZzqInconnu")));
    }

    @Test
    public void chaîneEtCommentaire_neServentRien() {
        String code = "class A {\n"
                + "    // LocalDate ici\n"
                + "    void t() { String s = \"LocalDate\"; }\n"
                + "}\n";
        assertNull(MiniJavaImports.importation(
                code, dans(code, "// LocalDate ici")));
        assertNull(MiniJavaImports.importation(
                code, dans(code, "\"LocalDate\"")));
    }

    @Test
    public void sansPaquetSansImport_insèreEnTête() {
        String code = "class A { void t() { LocalDate d = LocalDate.now(); } }";
        MiniJavaImports.Importation importation =
                MiniJavaImports.importation(code, dans(code, "LocalDate d"));
        assertNotNull(importation);
        assertEquals(0, importation.insertion);
    }
}

package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Le parseur Java du mini serveur LSP — chaque règle, ses déclencheurs
 * ET ses non-déclencheurs (le contrat « zéro faux positif » se teste
 * autant que la détection).
 */
public class MiniJavaParserTest {

    private static List<MiniJavaParser.Issue> parse(String text) {
        return MiniJavaParser.parse(text);
    }

    private static List<String> codes(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (MiniJavaParser.Issue issue : parse(text)) out.add(issue.code);
        return out;
    }

    @Test
    public void accoladeNonFermee_signalleeALouverture() {
        String code = "class A {\n    void f() {\n}\n";
        List<MiniJavaParser.Issue> issues = parse(code);
        assertTrue("Une accolade doit rester ouverte",
                codes(code).contains("unclosed-bracket"));
        MiniJavaParser.Issue issue = issues.get(0);
        assertEquals("Signalée à la ligne d'ouverture (0-based)", 0, issue.line);
        assertEquals("Colonne de l'accolade", 8, issue.character);
        assertTrue("Le message nomme l'accolade",
                issue.message.contains("n'est jamais fermé"));
    }

    @Test
    public void parentheseNonFermee_signallee() {
        assertTrue(codes("int x = foo(;\n").contains("unclosed-bracket"));
    }

    @Test
    public void fermanteOrpheline_signalleeASaPosition() {
        List<MiniJavaParser.Issue> issues = parse("int a = 1;\n}\n");
        assertEquals(1, issues.size());
        assertEquals("unexpected-close", issues.get(0).code);
        assertEquals(1, issues.get(0).line);
        assertTrue(issues.get(0).message.contains("inattendu"));
    }

    @Test
    public void pointVirguleManquant_avantNouvelleDeclaration() {
        List<MiniJavaParser.Issue> issues = parse("int x = 5\nint y = 6;\n");
        assertEquals(1, issues.size());
        assertEquals("missing-semicolon", issues.get(0).code);
        assertEquals(0, issues.get(0).line);
        assertTrue(issues.get(0).message.contains(";"));
    }

    @Test
    public void pointVirguleManquant_avantFermetureDeBloc() {
        assertTrue(codes("int x = foo()\n}\n").contains("missing-semicolon"));
    }

    @Test
    public void ligneModificateurSeule_pasDeFauxPositif() {
        assertTrue("« public static final » seul en ligne est légal",
                parse("public static final\nString S = \"x\";\n").isEmpty());
    }

    @Test
    public void continuationEnChaine_pasDeFauxPositif() {
        assertTrue(parse("int r = foo()\n        .bar()\n        .baz();\n").isEmpty());
    }

    @Test
    public void annotationSeule_pasDeFauxPositif() {
        assertTrue(parse("@Override\npublic String toString() {\n    return \"x\";\n}\n").isEmpty());
    }

    @Test
    public void elseSansIf_signale() {
        String code = "int a = 1;\nelse {\n    a = 2;\n}\n";
        List<MiniJavaParser.Issue> issues = parse(code);
        assertTrue(codes(code).contains("orphan-else"));
        assertEquals(1, issues.get(0).line);
    }

    @Test
    public void catchSansTry_signale() {
        assertTrue(codes("catch (Exception e) {\n}\n").contains("orphan-catch"));
    }

    @Test
    public void finallySansTry_signale() {
        assertTrue(codes("finally {\n}\n").contains("orphan-finally"));
    }

    @Test
    public void chaineIfElseIfElse_propre() {
        String code = "if (a > 0) {\n    f();\n} else if (a < 0) {\n    g();\n} else {\n    h();\n}\n";
        assertTrue(parse(code).isEmpty());
    }

    @Test
    public void tryCatchFinally_propre() {
        String code = "try {\n    risky();\n} catch (Exception e) {\n    log();\n} finally {\n    close();\n}\n";
        assertTrue(parse(code).isEmpty());
    }

    @Test
    public void elseDansUnAutreBloc_pasDeMariageCroise() {
        String code = "if (a) {\n    f();\n}\nint b = 1;\nelse {\n    g();\n}\n";
        assertTrue(codes(code).contains("orphan-else"));
    }

    @Test
    public void accoladesDansChaine_ignorées() {
        assertTrue(parse("String s = \"{\";\nint n = 1;\n").isEmpty());
    }

    @Test
    public void pointVirguleDansCommentaire_ignoré() {
        assertTrue(parse("int x = 1; // }{ point-virgule factice\nint y = 2;\n").isEmpty());
    }

    @Test
    public void texteDansBlocCommentaire_ignoré() {
        assertTrue(parse("/* } else { */\nint x = 1;\n").isEmpty());
    }

    @Test
    public void classePropreComplete_aucunProbleme() {
        String code = "package demo;\n\n"
                + "import java.util.List;\n\n"
                + "public final class Calculator {\n\n"
                + "    private int total;\n\n"
                + "    public int add(int value) {\n"
                + "        total += value;\n"
                + "        return total;\n"
                + "    }\n\n"
                + "    public int divide(int value) {\n"
                + "        try {\n"
                + "            return total / value;\n"
                + "        } catch (ArithmeticException e) {\n"
                + "            return 0;\n"
                + "        } finally {\n"
                + "            assert total >= 0;\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        List<MiniJavaParser.Issue> issues = parse(code);
        assertTrue("Une classe propre ne produit aucun diagnostic, pas "
                + issues, issues.isEmpty());
    }

    @Test
    public void texteVideOuNul_aucunProbleme() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse(null).isEmpty());
    }

    @Test
    public void profondeurExtreme_neCrashPas() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            deep.append("if (x) { // ").append(i).append('\n');
        }
        assertNotNull(parse(deep.toString()));
    }
}

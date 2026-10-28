package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Les actions de refactoring du mini Java (4.14.0) — entourer les
 * lignes complètes (six gabarits), envelopper et transtyper la
 * sélection partielle : la nature de la plage tranche, l'édition vit
 * dans l'action, et une plage vide ne sert rien.
 */
public class MiniJavaActionsTest {

    private static MiniJavaActions.Action chercher(
            List<MiniJavaActions.Action> actions, String titre) {
        for (MiniJavaActions.Action a : actions) {
            if (a.titre.equals(titre)) return a;
        }
        return null;
    }

    private static final String MÉTHODE = """
            class A {
                void t() {
                    int x = 1;
                    int y = 2;
                    return;
                }
            }
            """;

    // ── Entourer : la sélection de lignes complètes ─────────────

    @Test
    public void sélectionMultiLignes_sertLesSixEnveloppements() {
        // Les deux lignes « int x… » et « int y… » SÉLECTIONNÉES.
        int début = MÉTHODE.indexOf("int x");
        int fin = MÉTHODE.indexOf("return;");
        List<MiniJavaActions.Action> actions =
                MiniJavaActions.actions(MÉTHODE, début, fin);
        assertEquals(6, actions.size());
        for (MiniJavaActions.Action a : actions) {
            assertEquals("refactor.rewrite", a.genre);
        }
        MiniJavaActions.Action si = chercher(actions, "Entourer avec if (…)");
        assertTrue(si != null);
        assertTrue(si.texte.contains("if (condition) {"));
        assertTrue(si.texte.contains("}"));
        // Le corps sélectionné est INDENTÉ d'un niveau.
        assertTrue(si.texte.contains("    int x = 1;"));
        assertTrue(si.texte.contains("    int y = 2;"));
        // La plage étendue couvre les lignes COMPLÈTES (début de
        // la première ligne sélectionnée).
        int débutLigne = MÉTHODE.lastIndexOf('\n', MÉTHODE.indexOf("int x")) + 1;
        assertEquals(débutLigne, si.début);
    }

    @Test
    public void sélectionLigneUniqueComplète_sertLesEnveloppements() {
        int début = MÉTHODE.indexOf("int x");
        int fin = MÉTHODE.indexOf(";", début) + 1;
        List<MiniJavaActions.Action> actions =
                MiniJavaActions.actions(MÉTHODE, début, fin);
        assertTrue(chercher(actions, "Entourer avec for (…)") != null);
        MiniJavaActions.Action pour = chercher(actions, "Entourer avec for (…)");
        assertTrue(pour.texte.contains("for (var i = 0; i < taille; i++) {"));
    }

    @Test
    public void tryCatch_porteSaClause() {
        int début = MÉTHODE.indexOf("int x");
        int fin = MÉTHODE.indexOf("return;");
        List<MiniJavaActions.Action> actions =
                MiniJavaActions.actions(MÉTHODE, début, fin);
        MiniJavaActions.Action tc = chercher(actions, "Entourer avec try/catch");
        assertTrue(tc != null);
        assertTrue(tc.texte.contains("try {"));
        assertTrue(tc.texte.contains("} catch (Exception e) {"));
    }

    @Test
    public void indentation_deLaPremièreLignePorteLesMotsClés() {
        // Les gabarits s'indentent comme la PREMIÈRE ligne du bloc
        // (huit espaces dans ce fixture).
        int début = MÉTHODE.indexOf("int x");
        int fin = MÉTHODE.indexOf("return;");
        MiniJavaActions.Action si = chercher(
                MiniJavaActions.actions(MÉTHODE, début, fin),
                "Entourer avec while (…)");
        assertTrue(si.texte.startsWith("        while (condition) {"));
        assertTrue(si.texte.endsWith("        }"));
    }

    // ── Envelopper + transtyper : la sélection partielle ────────

    @Test
    public void sélectionPartielle_sertEnveloppementsEtCasts() {
        // « a + b » DANS la ligne — pas la ligne entière.
        String code = "class A { void t() { int s = a + b; } }";
        int début = code.indexOf("a + b");
        int fin = début + "a + b".length();
        List<MiniJavaActions.Action> actions =
                MiniJavaActions.actions(code, début, fin);
        assertEquals(7, actions.size());
        assertEquals("(a + b)",
                chercher(actions, "Mettre entre parenthèses").texte);
        assertEquals("String.valueOf(a + b)",
                chercher(actions, "Envelopper dans String.valueOf(…)").texte);
        assertEquals("Objects.requireNonNull(a + b)",
                chercher(actions,
                        "Envelopper dans Objects.requireNonNull(…)").texte);
        assertEquals("Math.abs(a + b)",
                chercher(actions, "Envelopper dans Math.abs(…)").texte);
        assertEquals("(int) a + b",
                chercher(actions, "Transtyper en int").texte);
        assertEquals("(long) a + b",
                chercher(actions, "Transtyper en long").texte);
        assertEquals("(double) a + b",
                chercher(actions, "Transtyper en double").texte);
        // La plage remplacée est la sélection, telle quelle.
        assertEquals(début,
                chercher(actions, "Mettre entre parenthèses").début);
        assertEquals(fin,
                chercher(actions, "Mettre entre parenthèses").fin);
    }

    @Test
    public void sélectionDunMot_sertLesMêmesFamilles() {
        String code = "class A { void t() { int s = total; } }";
        int début = code.indexOf("total");
        List<MiniJavaActions.Action> actions =
                MiniJavaActions.actions(code, début, début + 5);
        assertEquals(7, actions.size());
        assertEquals("(total)",
                chercher(actions, "Mettre entre parenthèses").texte);
    }

    // ── Les plages qui ne servent rien ──────────────────────────

    @Test
    public void plageBlanche_neSertRien() {
        String code = "class A {\n\n    void t() { }\n}";
        int vide = code.indexOf("\n\n") + 1;
        assertTrue(MiniJavaActions.actions(code, vide, vide + 1).isEmpty());
    }

    @Test
    public void plageInvalide_neSertRien() {
        String code = "class A { void t() { } }";
        assertTrue(MiniJavaActions.actions(null, 0, 5).isEmpty());
        assertTrue(MiniJavaActions.actions(code, 5, 5).isEmpty());
        assertTrue(MiniJavaActions.actions(code, -2, 4).isEmpty());
        assertTrue(MiniJavaActions.actions(code, 0, code.length() + 9)
                .isEmpty());
    }
}

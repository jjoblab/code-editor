package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La table postfix du PROFIL (4.5.0) : les gabarits « nom=gabarit »
 * complètent et surchargent les défauts de la famille, un gabarit vide
 * RETIRE un nom, un langage hors famille qui déclare une table active
 * les postfixes, et le jeton {@code ${expr}} reçoit l'expression
 * extraite avec ré-indentation des lignes de continuation.
 */
class PostfixProfileTemplatesTest {

    /** Langage maison hors C_LIKE/PYTHON. */
    private static final String LANG = "postfix-maison";

    private void registerMaison(String... entries) {
        // forName(...) installe la table intégrée au premier appel.
        LanguageRegistry.forName("java");
        LanguageRegistry.register(LanguageProfile.builder(LANG)
                .family(SyntaxFamily.MARKDOWN)
                .postfixTemplates(entries)
                .build());
    }

    @Test
    void tableDuProfil_postfixeDéclenché() {
        registerMaison("echo=<< ${expr} >>");
        PostfixCompletion.Result r = PostfixCompletion.tryComplete("valeur.echo", 11, LANG);
        assertNotNull(r);
        assertEquals("<< valeur >>", r.insertText);
        // Le remplacement couvre l'expression ET le postfixe.
        assertEquals(0, r.start);
        assertEquals(11, r.end);
    }

    @Test
    void langageHorsFamille_sansTable_aucunPostfixe() {
        registerMaison();
        assertNull(PostfixCompletion.tryComplete("obj.if", 6, LANG));
    }

    @Test
    void gabaritMultiligne_réindenté() {
        registerMaison("bloc=begin(${expr})\n    corps\nend");
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "    x.bloc", 10, LANG);
        assertNotNull(r);
        // Les lignes de continuation portent l'indentation de la ligne.
        assertEquals("begin(x)\n        corps\n    end", r.insertText);
        // Le caret se pose juste après la première ligne — la position
        // du corps (règle historique des postfixes intégrés).
        assertEquals("    ".length() + "begin(x)".length() + 1, r.caret);
    }

    @Test
    void surcharge_défautDeFamille() {
        LanguageRegistry.forName("java");
        LanguageRegistry.register(LanguageProfile.builder("c-like-maison")
                .family(SyntaxFamily.C_LIKE)
                .postfixTemplates("if=if (${expr}) then\nend")
                .build());
        PostfixCompletion.Result r = PostfixCompletion.tryComplete("ok.if", 5, "c-like-maison");
        assertNotNull(r);
        // Le gabarit du profil a REMPLACÉ celui de la famille…
        assertEquals("if (ok) then\nend", r.insertText);
        // …mais les autres défauts restent.
        assertNotNull(PostfixCompletion.tryComplete("ok.notnull", 10, "c-like-maison"));
    }

    @Test
    void gabaritVide_retraitDuNom() {
        LanguageRegistry.forName("java");
        LanguageRegistry.register(LanguageProfile.builder("c-like-retrait")
                .family(SyntaxFamily.C_LIKE)
                .postfixTemplates("notnull=")
                .build());
        assertNull(PostfixCompletion.tryComplete("obj.notnull", 11, "c-like-retrait"));
        assertNotNull(PostfixCompletion.tryComplete("obj.if", 6, "c-like-retrait"));
    }

    @Test
    void sout_javaEtKotlin() {
        LanguageRegistry.forName("java");
        PostfixCompletion.Result java = PostfixCompletion.tryComplete("msg.sout", 8, "java");
        assertNotNull(java);
        assertEquals("System.out.println(msg);", java.insertText);

        PostfixCompletion.Result kotlin = PostfixCompletion.tryComplete("msg.sout", 8, "kotlin");
        assertNotNull(kotlin);
        assertEquals("println(msg)", kotlin.insertText);

        // Les autres langages C-like n'ont PAS .sout par défaut.
        assertNull(PostfixCompletion.tryComplete("msg.sout", 8, "javascript"));
    }

    @Test
    void python_défautsPréservés() {
        LanguageRegistry.forName("java");
        PostfixCompletion.Result r = PostfixCompletion.tryComplete("items.for", 9, "python");
        assertNotNull(r);
        assertTrue(r.insertText.contains("for item in items:"));
        assertNull(PostfixCompletion.tryComplete("msg.sout", 8, "python"));
    }

    @Test
    void plusLongPostfixe_gagne() {
        registerMaison("if=BAD", "ifelse=BEST(${expr})");
        PostfixCompletion.Result r = PostfixCompletion.tryComplete("x.ifelse", 8, LANG);
        assertNotNull(r);
        assertEquals("BEST(x)", r.insertText);
    }
}

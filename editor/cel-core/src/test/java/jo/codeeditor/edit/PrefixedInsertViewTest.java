package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrat de {@link PrefixedInsertView} — la vue paresseuse « préfixe +
 * insertion » du lot P-01 (audit 3.69.0) : même contenu que la
 * concaténation matérialisée, sans jamais copier le préfixe.
 */
class PrefixedInsertViewTest {

    /** La vue expose exactement préfixe + insertion, par tous les accès. */
    @Test
    void contenu_identique_a_la_concatenation() {
        PrefixedInsertView v = new PrefixedInsertView("abc", "de");
        assertEquals(5, v.length());
        assertEquals('a', v.charAt(0));
        assertEquals('c', v.charAt(2));
        assertEquals('d', v.charAt(3));
        assertEquals('e', v.charAt(4));
        assertEquals("abcde", v.toString());
    }

    /** Tranche entièrement dans le préfixe — déléguée, jamais copiée. */
    @Test
    void tranche_dans_le_prefixe() {
        PrefixedInsertView v = new PrefixedInsertView("hello", "world");
        assertEquals("ell", v.subSequence(1, 4).toString());
    }

    /** Tranche entièrement dans l'insertion. */
    @Test
    void tranche_dans_l_insertion() {
        PrefixedInsertView v = new PrefixedInsertView("hello", "world");
        assertEquals("orl", v.subSequence(6, 9).toString());
    }

    /**
     * Tranche À CHEVAL sur la frontière — le cas de la complétion
     * postfix : l'expression commence dans le document et le postfix
     * vit dans le texte frappé (p. ex. « foo » + « bar.if »).
     */
    @Test
    void tranche_a_cheval_sur_la_frontiere() {
        PrefixedInsertView v = new PrefixedInsertView("foo", "bar.if");
        // Expression « foobar » : [0, 6) traverse la frontière (3).
        assertEquals("foobar", v.subSequence(0, 6).toString());
        // Tranche minimale à cheval : [2, 4) = « ob ».
        assertEquals("ob", v.subSequence(2, 4).toString());
    }

    /** Bornes : hors bornes et start > end lèvent, comme une String. */
    @Test
    void bornes_rejectees_comme_une_string() {
        PrefixedInsertView v = new PrefixedInsertView("ab", "cd");
        assertThrows(IndexOutOfBoundsException.class, () -> v.charAt(4));
        assertThrows(IndexOutOfBoundsException.class, () -> v.charAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> v.subSequence(1, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> v.subSequence(2, 1));
        // Tranche vide licite aux bornes exactes.
        assertEquals("", v.subSequence(4, 4).toString());
    }

    /**
     * Préfixe vide (caret en début de document) : la vue vaut
     * l'insertion seule.
     */
    @Test
    void prefixe_vide() {
        PrefixedInsertView v = new PrefixedInsertView("", ".if");
        assertEquals(3, v.length());
        assertEquals(".if", v.toString());
        assertEquals(".i", v.subSequence(0, 2).toString());
    }

    /** Insertion vide : la vue vaut le préfixe seul. */
    @Test
    void insertion_vide() {
        PrefixedInsertView v = new PrefixedInsertView("doc", "");
        assertEquals(3, v.length());
        assertEquals("doc", v.toString());
        assertEquals("oc", v.subSequence(1, 3).toString());
    }

    /**
     * Scénario P-01 de bout en bout sur une rope réelle : la vue sur
     * « préfixe de 25 000 lignes + texte frappé » sert l'analyse postfix
     * (regionEquals + remontée d'expression + retrait de ligne) sans
     * jamais matérialiser le préfixe.
     */
    @Test
    void analyse_postfix_sur_grand_prefixe_sans_copie() {
        StringBuilder sb = new StringBuilder(25_000 * 45);
        for (int i = 0; i < 25_000; i++) {
            sb.append("int var").append(i).append(" = ").append(i * 2)
              .append("; // commentaire ").append(i).append('\n');
        }
        sb.append("obj.");
        CharSequence rope =
            jo.codeeditor.rope.Rope.fromString(sb.toString());
        // Par la fabrique publique (M-03 : toute API publique a son test).
        CharSequence v = EditOps.simulatedInsert(rope, "if");
        EditOps.PostfixResult r = EditOps.tryPostfix(v, v.length(), "java");
        assertNotNull(r, "le postfix .if doit être détecté au bout du document");
        assertEquals("if (obj) {\n    \n}", r.insertText);
        // Le remplacement couvre « obj.if » (fin du document).
        assertEquals(sb.length() - "obj.".length(), r.start);
        assertEquals(v.length(), r.end);
    }
}

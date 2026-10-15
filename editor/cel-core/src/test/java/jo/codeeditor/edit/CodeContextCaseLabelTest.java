package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrat du prédicat de label de branchement (P-04, audit 3.69.0) —
 * le motif est désormais PRÉCOMPILÉ (static final Pattern) : ce test
 * fige la sémantique reconnue, qui ne doit pas changer.
 */
class CodeContextCaseLabelTest {

    /** Un label case complet avec valeur est reconnu. */
    @Test
    void labelCaseAvecValeur_reconnu() {
        assertTrue(CodeContext.isCaseLabel("case FOO:", 8));
        assertTrue(CodeContext.isCaseLabel("case 42:", 7));
        assertTrue(CodeContext.isCaseLabel("            case A.B:", 20));
    }

    /** Le default est un label de branchement. */
    @Test
    void labelDefault_reconnu() {
        assertTrue(CodeContext.isCaseLabel("default:", 7));
        assertTrue(CodeContext.isCaseLabel("    default:", 11));
    }

    /** Pas de label sans les deux-points finaux. */
    @Test
    void sansDeuxPoints_refusé() {
        assertFalse(CodeContext.isCaseLabel("case FOO", 7));
        assertFalse(CodeContext.isCaseLabel("default", 6));
    }

    /** Un identifiant qui CONTIENT case n'est pas un label. */
    @Test
    void contenantCaseSansMotifComplet_refusé() {
        // « lowercase x: » — case n'y est pas un mot-clé en tête.
        assertFalse(CodeContext.isCaseLabel("lowercase x:", 11));
        // un mot-clé switch n'est pas un label.
        assertFalse(CodeContext.isCaseLabel("switch (x) {", 11));
    }

    /** La ligne peut être partielle : le prédicat lit jusqu'à pos. */
    @Test
    void positionIntermédiaire_neCompteQueJusquAPos() {
        String text = "case FOO: // et un commentaire";
        // pos au niveau des deux-points → ligne vue = « case FOO: ».
        assertTrue(CodeContext.isCaseLabel(text, 8));
    }
}

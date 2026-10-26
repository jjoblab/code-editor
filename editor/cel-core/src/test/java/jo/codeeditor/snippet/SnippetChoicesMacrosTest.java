package jo.codeeditor.snippet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Les variables de choix (« {@code ${1|a,b,c|}} ») et les macros
 * (« {@code $FILE$} », « {@code $DATE$} », « {@code $USER$} ») du
 * parseur de snippets (4.5.0).
 */
class SnippetChoicesMacrosTest {

    // ── Variables de choix ───────────────────────────────────────

    @Test
    void choix_premiereOptionCommeTexteInitial() {
        SnippetSession s = SnippetSession.parse("const ${1|val,var,const|} = $2;", 0, null);
        assertNotNull(s);
        assertEquals("const val = ;", s.plainText());
        SnippetSession.TabStop stop = s.current();
        assertNotNull(stop);
        assertEquals(1, stop.index);
        // Le champ porte ses options ; la première est le texte initial.
        assertEquals(List.of("val", "var", "const"), stop.choices);
        // Le champ sélectionné couvre « val ».
        assertEquals("val", s.plainText().substring(stop.start, stop.end));
    }

    @Test
    void choix_2() {
        SnippetSession s = SnippetSession.parse("${2|a,b|}", 0, null);
        SnippetSession.TabStop stop = s.current();
        assertEquals(2, stop.index);
        assertEquals(List.of("a", "b"), stop.choices);
    }

    @Test
    void choix_malforme_optionsVides_champLibre() {
        // « ${1|} » : séparateur sans options → champ libre, texte vide.
        SnippetSession s = SnippetSession.parse("x${1|}y", 0, null);
        assertNotNull(s);
        assertEquals("xy", s.plainText());
        assertTrue(s.current().choices.isEmpty());
    }

    @Test
    void choix_optionsAvecEspacesConservées() {
        SnippetSession s = SnippetSession.parse("${1| alpha , beta |}", 0, null);
        assertEquals(List.of(" alpha ", " beta "), s.current().choices);
        // Le texte initial EST la première option, espaces compris.
        assertEquals(" alpha ", s.plainText());
    }

    @Test
    void choix_neBrisentPasLePlaceholderLegacy() {
        SnippetSession s = SnippetSession.parse("a ${1:nom} b ${2|x,y|} c", 0, null);
        assertEquals("a nom b x c", s.plainText());
        assertTrue(s.current().choices.isEmpty());
        s.next();
        assertEquals(List.of("x", "y"), s.current().choices);
    }

    @Test
    void choix_miroirsParIndexRepete() {
        // Deux occurrences du MÊME index : la primaire porte les
        // options, le miroir est lié (les choix vivent sur la primaire).
        SnippetSession s = SnippetSession.parse("${1|a,b|} et $1", 0, null);
        assertEquals("a et ", s.plainText());
        assertEquals(List.of("a", "b"), s.current().choices);
        assertEquals(2, s.getStops().size());
    }

    // ── Macros ───────────────────────────────────────────────────

    @Test
    void macros_resoluesAvecContexte() {
        SnippetMacroContext ctx = new SnippetMacroContext(
                "Main.kt", "Jeanne", () -> "2026-10-27");
        SnippetSession s = SnippetSession.parse(
                "// $FILE$ — $USER$ — $DATE$\n$1", 0, ctx);
        assertNotNull(s);
        assertEquals("// Main.kt — Jeanne — 2026-10-27\n", s.plainText());
    }

    @Test
    void macros_sansContexte_conservéesLittéralement() {
        SnippetSession s = SnippetSession.parse("// $FILE$ $1", 0, null);
        assertEquals("// $FILE$ ", s.plainText());
    }

    @Test
    void macros_champNonRenseigné_littéral() {
        // Fichier posé, auteur absent : $USER$ reste littéral.
        SnippetMacroContext ctx = SnippetMacroContext.of("Main.kt", null);
        SnippetSession s = SnippetSession.parse("$FILE$/$USER$ $1", 0, ctx);
        assertEquals("Main.kt/$USER$ ", s.plainText());
    }

    @Test
    void macros_inconnue_littérale() {
        SnippetMacroContext ctx = SnippetMacroContext.of("f", "u");
        SnippetSession s = SnippetSession.parse("$FOO$ $1", 0, ctx);
        assertEquals("$FOO$ ", s.plainText());
    }

    @Test
    void macros_dateRésolueÀExpansion() {
        // Deux expansions successives, deux dates différentes : le
        // fournisseur est lu à CHAQUE parse, pas capturé à la
        // construction du contexte.
        int[] calls = {0};
        SnippetMacroContext ctx = new SnippetMacroContext(
                null, null, () -> "d" + (++calls[0]));
        assertEquals("d1 ", SnippetSession.parse("$DATE$ $1", 0, ctx).plainText());
        assertEquals("d2 ", SnippetSession.parse("$DATE$ $1", 0, ctx).plainText());
    }

    @Test
    void macros_neBrisentPasLesChamps() {
        SnippetMacroContext ctx = SnippetMacroContext.of("F", "U");
        SnippetSession s = SnippetSession.parse("$FILE$.$1($2)", 0, ctx);
        assertEquals("F.()", s.plainText());
        assertNotNull(s.current());
        assertEquals(1, s.current().index);
    }

    @Test
    void dollarSeulEtDollarInconnu_littéraux() {
        // Le comportement historique est préservé : un $ non suivi
        // d'un marqueur connu est recopié tel quel.
        SnippetSession s = SnippetSession.parse("prix: 5$ et $x $1", 0, null);
        assertEquals("prix: 5$ et $x ", s.plainText());
    }
}

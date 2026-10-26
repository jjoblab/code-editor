package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Surbrillance des candidats : les indices renvoyés par
 * {@link CompletionSession#matchPositions(String, String)} doivent être
 * EXACTEMENT ceux qui ont fait matcher le préfixe — les initiales de mots
 * pour un appariement camel-hump, le run initial pour un préfixe — jamais
 * des lettres quelconques prises au fil d'un parcours glouton.
 *
 * <p>Rouge avant (constat d'audit) : le parcours glouton prenait le
 * premier caractère correspondant rencontré, y compris au milieu d'un mot
 * (le « t » de « get » gras pour « gtv » sur « getTextView » au lieu du
 * « T » de « Text »), et ne distinguait pas les débuts de mots.</p>
 */
class CompletionMatchPositionsTest {

    @Test
    void camelHumps_lesInitialesSontEnGras() {
        // « LLM » sur « LazyListModifier » : les initiales 0, 4, 8.
        List<Integer> positions = CompletionSession.matchPositions(
                "LazyListModifier", "LLM");
        assertEquals(List.of(0, 4, 8), positions,
                "les indices en gras doivent être les initiales de mots");
    }

    @Test
    void camelHumps_pasDeLettrePriseAuMilieuDUnMot() {
        // « gtv » sur « getTextView » : g(0), T(3), V(7) — le « t » de
        // « get » (indice 2) ne fait pas partie de l'appariement : ce
        // n'est ni un début de mot ni un run contigu utile.
        List<Integer> positions = CompletionSession.matchPositions(
                "getTextView", "gtv");
        assertEquals(List.of(0, 3, 7), positions,
                "le « t » du milieu de « get » ne doit jamais être gras");
    }

    @Test
    void runContigu_toutLeRunEstEnGras() {
        // « get » sur « getString » : run contigu au début.
        assertEquals(List.of(0, 1, 2),
                CompletionSession.matchPositions("getString", "get"));
    }

    @Test
    void prefixe_surLabelAvecSignature() {
        // « Tex » sur « TextField() » : le préfixe du libellé, la
        // signature (parenthèses comprises) n'est jamais en gras.
        assertEquals(List.of(0, 1, 2),
                CompletionSession.matchPositions("TextField()", "Tex"));
    }

    @Test
    void exact_identifiantDeTete() {
        // « Text » sur « Text(text: String) » : l'identifiant de tête
        // matche en entier — pas la signature.
        assertEquals(List.of(0, 1, 2, 3),
                CompletionSession.matchPositions("Text(text: String)", "Text"));
    }

    @Test
    void separateursDeMots_underscoreEtPoint() {
        // « glw » sur « get_layout_width » : g(0) début, l(4) après le
        // « _ », w(11) après le second « _ » — les séparateurs ouvrent
        // des débuts de mots.
        assertEquals(List.of(0, 4, 11),
                CompletionSession.matchPositions("get_layout_width", "glw"));
    }

    @Test
    void pasDeCorrespondance_listeVide() {
        assertTrue(CompletionSession.matchPositions("hello", "xyz").isEmpty());
    }
}

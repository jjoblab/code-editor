package jo.codeeditor.languages;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (chantier C1, décision D5 « source unique ») — les snippets
 * de complétion intégrée deviennent une donnée du profil.
 *
 * <p>Avant C1, la liste Markdown (« # », « ## », « - », clôtures de
 * blocs de code…) vivait en dur dans {@code EditorCompletionPopup}
 * (UI) : elle était la SEULE donnée de complétion hors table. Désormais
 * tout langage — intégré ou enregistré par l'hôte — peut déclarer ses
 * snippets à côté de ses mots-clés.</p>
 */
public class LanguageProfileSnippetsTest {

    /** Snippets déclarés : ordre préservé, entrées nulles/vides ignorées. */
    @Test
    public void ordreEtFiltrageDesEntrées() {
        LanguageProfile p = LanguageProfile.builder("t-snip")
                .snippets("# ", null, "", "## ")
                .build();
        assertEquals(List.of("# ", "## "), p.snippets);
    }

    /** Défaut vide ; la liste est immuable. */
    @Test
    public void défautVideImmuable() {
        LanguageProfile p = LanguageProfile.builder("t-snip2").build();
        assertTrue(p.snippets.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> p.snippets.add("x"));
    }

    /** La table intégrée porte la liste Markdown historique. */
    @Test
    public void markdownIntgréPorteLaListeHistorique() {
        LanguageProfile md = LanguageRegistry.forName("markdown");
        assertEquals(19, md.snippets.size());
        assertEquals("# ", md.snippets.get(0));
        assertEquals("## ", md.snippets.get(1));
        assertTrue(md.snippets.contains("```java"));
        assertTrue(md.snippets.contains("---|---"));
        // Le mot-clé historique « markdown_keywords_stillProposed »
        // (cel-ui) vérifie la proposition effective de « # ».
    }

    /** Un langage maison déclare des snippets sans toucher au Java. */
    @Test
    public void langageMaisonAvecSnippets() {
        LanguageProfile p = LanguageProfile.builder("t-snip3")
                .family(SyntaxFamily.MARKDOWN)
                .snippets("TODO ", "FIXME ")
                .build();
        assertEquals(List.of("TODO ", "FIXME "), p.snippets);
    }
}

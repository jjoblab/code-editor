package jo.codeeditor.view;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ADR-002 (chantier C1, décision D5 « source unique ») — les snippets
 * de complétion intégrée sont lus DANS LE PROFIL.
 *
 * <p>Avant C1, la seule liste de snippets vivait en dur dans
 * {@code EditorCompletionPopup.builtinKeywordCompletions} (branche
 * « markdown ») : un langage maison déclarant des snippets n'avait
 * aucune voie — la donnée n'existait pas dans le profil. Désormais
 * {@link LanguageProfile#snippets} porte la liste (Markdown intégré
 * inclus) et le popup la lit pour tout langage.</p>
 *
 * <p>Rouge avant : les snippets d'un langage maison enregistré
 * n'étaient jamais proposés (branche markdown codée en dur).
 * Vert après : la donnée du profil décide.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ProfileSnippetCompletionsTest {

    private static final Method METHOD;
    static {
        try {
            METHOD = Class.forName("jo.codeeditor.view.popup.EditorCompletionPopup")
                    .getDeclaredMethod("builtinKeywordCompletions",
                            String.class, String.class);
            METHOD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CompletionSession.Item> call(String lang, String prefix) {
        try {
            return (List<CompletionSession.Item>) METHOD.invoke(null, lang, prefix);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @After
    public void cleanup() {
        LanguageRegistry.unregister("psc-mylang");
    }

    /**
     * Langage maison avec snippets déclarés : ils DOIVENT être proposés
     * au même titre que les mots-clés — la donnée vit dans le profil,
     * plus dans une branche markdown codée dans l'UI.
     */
    @Test
    public void snippets_langageMaison_proposés() {
        LanguageRegistry.register(LanguageProfile.builder("psc-mylang")
                .family(SyntaxFamily.MARKDOWN)
                .snippets("TODO ", "FIXME ")
                .build());
        List<CompletionSession.Item> items = call("psc-mylang", "TO");
        assertFalse("Un langage maison avec snippets doit proposer des items",
                items.isEmpty());
        boolean foundTodo = items.stream()
                .anyMatch(i -> "TODO ".equals(i.insertText));
        assertTrue("Le snippet 'TODO ' doit être proposé (préfixe 'TO')",
                foundTodo);
    }

    /** Non-régression : Markdown intégré propose toujours « # ». */
    @Test
    public void markdown_intgré_snippetsToujoursProposés() {
        List<CompletionSession.Item> items = call("markdown", "#");
        assertTrue("Markdown intégré doit toujours proposer '# '",
                items.stream().anyMatch(i -> "# ".equals(i.insertText)));
        // Alias « md » : même profil via le registre.
        List<CompletionSession.Item> viaAlias = call("md", "##");
        assertTrue("L'alias md doit proposer '## '",
                viaAlias.stream().anyMatch(i -> "## ".equals(i.insertText)));
    }
}

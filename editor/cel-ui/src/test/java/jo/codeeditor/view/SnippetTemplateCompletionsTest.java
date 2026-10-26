package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.lang.model.SnippetTemplate;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Les GABARITS de complétion (4.5.0) : ceux du profil
 * (« libellé|corps », {@code Builder#snippetTemplates}) et ceux du
 * provider SPI de l'hôte ({@code SnippetProvider}) remontent dans le
 * popup — fusionnés AUX candidats du fournisseur externe comme à la
 * complétion intégrée — et s'insèrent EXPANSÉS (champs, miroirs,
 * choix, macros) à l'acceptation.
 */
@RunWith(RobolectricTestRunner.class)
public class SnippetTemplateCompletionsTest {

    /** Langage maison avec gabarits déclarés par le profil. */
    private static final String LANG = "gabarit-test";

    private void registerLanguage() {
        LanguageRegistry.register(LanguageProfile.builder(LANG)
                .keywords("alpha", "beta")
                .snippetTemplates(
                        "for|int for (${1:i} = 0; ${1:i} < $2; ${1:i}++) {\n    $0\n}",
                        "log|System.out.println(${1|msg,texte|});")
                .build());
    }

    private EditorView viewWithDoc(String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setLanguage(LANG);
        session.setSelection(doc.length());
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private static void awaitPopup(EditorView view) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (view.completion.items.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                fail("le popup n'a reçu aucun item dans le délai imparti");
            }
            Thread.sleep(20);
        }
        Thread.sleep(60);
    }

    private static List<String> labels(EditorView view) {
        List<String> out = new ArrayList<>();
        for (CompletionSession.Item item : view.completion.items) {
            out.add(item.label);
        }
        return out;
    }

    private static CompletionSession.Item itemWithLabel(EditorView view, String label) {
        for (CompletionSession.Item item : view.completion.items) {
            if (item.label.equals(label)) return item;
        }
        return null;
    }

    // ── Gabarits du profil ───────────────────────────────────────

    @Test
    public void gabaritsDuProfil_proposésEtExpansés() throws Exception {
        registerLanguage();
        EditorView view = viewWithDoc("for");
        view.refreshCompletion();
        awaitPopup(view);
        // Le gabarit « for » remonte, comme les mots-clés.
        assertTrue("le gabarit for doit être proposé (reçu : " + labels(view) + ")",
                labels(view).contains("for"));
        CompletionSession.Item gabarit = itemWithLabel(view, "for");
        assertTrue(gabarit.isSnippet);

        // Acceptation : le corps est EXPANSÉ — premier champ sélectionné,
        // marqueurs apurés, miroirs en place (les ${1:i} répétés portent
        // le placeholder « i », $2 vide).
        view.completion.selected = view.completion.items.indexOf(gabarit);
        assertTrue(view.completionAccept());
        assertEquals("int for (i = 0; i < ; i++) {\n    \n}", view.session.getText());
        assertTrue(view.session.snippets.isActive());
    }

    @Test
    public void gabaritsDuProfil_filtreParPrefixe() throws Exception {
        registerLanguage();
        EditorView view = viewWithDoc("lo");
        view.refreshCompletion();
        awaitPopup(view);
        assertTrue(labels(view).contains("log"));
        assertFalse("le gabarit for ne matche pas « lo »", labels(view).contains("for"));
    }

    @Test
    public void gabarits_fusionnésAuxCandidatsDuProvider() throws Exception {
        // Avec un fournisseur externe branché (voie LSP), les gabarits
        // du profil doivent TOUJOURS remonter, fusionnés à sa base.
        registerLanguage();
        EditorView view = viewWithDoc("for");
        view.setCompletionProvider((text, caret, tokenStart, prefix) ->
                Collections.singletonList(
                        new CompletionSession.Item("format()", "", "format()", "m", 2, 0, false, false)));
        view.refreshCompletion();
        awaitPopup(view);
        List<String> received = labels(view);
        assertTrue("candidat du fournisseur présent", received.contains("format()"));
        assertTrue("gabarit fusionné présent", received.contains("for"));
    }

    // ── Gabarits utilisateur (SPI hôte) ──────────────────────────

    /** Language sans effet (cel-lsp-api) étendu pour exposer les gabarits « stockés » côté hôte. */
    private static class FakeTemplateLanguage extends jo.codeeditor.lang.EmptyLanguage {
        @Override
        public jo.codeeditor.lang.provider.SnippetProvider getSnippetProvider() {
            return language -> Arrays.asList(
                    new SnippetTemplate("sout", "System.out.println($0);", "gabarit utilisateur"),
                    new SnippetTemplate("ifnull", "if (${1:o} == null) {\n    $0\n}"));
        }
    }

    @Test
    public void gabaritsUtilisateur_providerFournisseur_stockageHote() throws Exception {
        registerLanguage();
        EditorView view = viewWithDoc("sou");
        view.setLanguage(new FakeTemplateLanguage());
        view.refreshCompletion();
        awaitPopup(view);
        assertTrue("gabarit utilisateur proposé (reçu : " + labels(view) + ")",
                labels(view).contains("sout"));
        CompletionSession.Item gabarit = itemWithLabel(view, "sout");
        assertEquals("gabarit utilisateur", gabarit.detail);

        view.completion.selected = view.completion.items.indexOf(gabarit);
        assertTrue(view.completionAccept());
        // $0 est l'arrêt final : caret entre les parenthèses, texte apuré.
        assertEquals("System.out.println();", view.session.getText());
    }

    @Test
    public void gabaritAvecChoix_sessionDeChoixPosée() throws Exception {
        // Le gabarit « log » porte un champ de choix : après
        // acceptation, la session est active et le champ courant a
        // ses options.
        registerLanguage();
        EditorView view = viewWithDoc("log");
        view.refreshCompletion();
        awaitPopup(view);
        CompletionSession.Item gabarit = itemWithLabel(view, "log");
        view.completion.selected = view.completion.items.indexOf(gabarit);
        assertTrue(view.completionAccept());
        assertEquals("System.out.println(msg);", view.session.getText());
        assertNotNull(view.session.snippets.get());
        assertEquals(Arrays.asList("msg", "texte"),
                view.session.snippets.get().current().choices);
    }

    @Test
    public void gabarits_macrosRésoluesAuContexteDeLaSession() throws Exception {
        registerLanguage();
        EditorView view = viewWithDoc("log");
        // L'hôte pose le contexte de macros AVANT l'acceptation.
        view.session.snippets.setMacroContext(
                jo.codeeditor.snippet.SnippetMacroContext.of("Main.java", "Jeanne"));
        view.refreshCompletion();
        awaitPopup(view);
        CompletionSession.Item gabarit = itemWithLabel(view, "log");
        view.completion.selected = view.completion.items.indexOf(gabarit);
        assertTrue(view.completionAccept());
        // Le gabarit « log » n'a pas de macro — le contexte ne doit
        // rien casser ; le champ de choix reste sélectionné.
        assertTrue(view.session.getText().startsWith("System.out.println(msg"));
    }
}

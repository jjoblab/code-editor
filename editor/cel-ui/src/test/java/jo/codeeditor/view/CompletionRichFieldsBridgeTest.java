package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.Collections;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.BracketMatch;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.model.CodeBlock;
import jo.codeeditor.lang.provider.CompletionPublisher;
import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Pont des champs riches de complétion (4.3.0) : un fournisseur SPI qui
 * publie l'origine du symbole ({@code container}) et la documentation
 * les transporte jusqu'au provider de la vue — le popup peut les
 * afficher sans rien exiger du serveur (les champs absents restent
 * absents, comportement historique).
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionRichFieldsBridgeTest {

    @Test
    public void pont_fournisseurSpiPublieChampsRiches() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("obj"));
        session.setSelection(3);
        view.setSession(session);
        view.setLanguage(new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return new Analyzer() {
                    @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) {}
                    @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) {}
                    @Override public void reset(CharSequence t) {}
                    @Override public StyledLine styledLine(int line) { return null; }
                    @Override public List<CodeBlock> computeBlocks() {
                        return Collections.emptyList();
                    }
                    @Override public BracketMatch computeBracketMatch(int o) { return null; }
                    @Override public void destroy() {}
                };
            }
            @Override
            public CompletionProvider getCompletionProvider() {
                return new CompletionProvider() {
                    @Override
                    public void complete(CharSequence text, int caret,
                                          CompletionPublisher publisher) {
                        publisher.addItem(new jo.codeeditor.lang.model.CompletionItem(
                                "obj", "java.lang.Object", "obj", "v",
                                6, null, 10, false,
                                null, null, -1, -1,
                                "java.util", "Retourne un objet."));
                    }
                    @Override
                    public List<String> getTriggerCharacters() {
                        return Collections.emptyList();
                    }
                };
            }
            @Override public int getInterruptionLevel() { return INTERRUPTION_LEVEL_NONE; }
            @Override public void destroy() {}
        });

        // Le provider installé par setLanguage adapte le SPI : les
        // champs riches doivent traverser le pont.
        CompletionResult result = view.completionProvider.provideResult(
                "obj", 3, 0, "obj", null);
        assertEquals(1, result.items.size());
        CompletionSession.Item item = result.items.get(0);
        assertEquals("java.util", item.container);
        assertEquals("Retourne un objet.", item.documentation);
        assertFalse("pas de drapeau posé : liste complète", result.isIncomplete);
    }

    @Test
    public void pont_fournisseurSpiSansChampsRiches_historique() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("obj"));
        session.setSelection(3);
        view.setSession(session);
        view.setLanguage(new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return new Analyzer() {
                    @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) {}
                    @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) {}
                    @Override public void reset(CharSequence t) {}
                    @Override public StyledLine styledLine(int line) { return null; }
                    @Override public List<CodeBlock> computeBlocks() {
                        return Collections.emptyList();
                    }
                    @Override public BracketMatch computeBracketMatch(int o) { return null; }
                    @Override public void destroy() {}
                };
            }
            @Override
            public CompletionProvider getCompletionProvider() {
                return new CompletionProvider() {
                    @Override
                    public void complete(CharSequence text, int caret,
                                          CompletionPublisher publisher) {
                        publisher.addItem(new jo.codeeditor.lang.model.CompletionItem(
                                "obj", "java.lang.Object", "obj", "v",
                                6, null, 10, false));
                    }
                    @Override
                    public List<String> getTriggerCharacters() {
                        return Collections.emptyList();
                    }
                };
            }
            @Override public int getInterruptionLevel() { return INTERRUPTION_LEVEL_NONE; }
            @Override public void destroy() {}
        });

        CompletionResult result = view.completionProvider.provideResult(
                "obj", 3, 0, "obj", null);
        assertEquals(1, result.items.size());
        assertNull("champs absents : ils restent absents",
                result.items.get(0).container);
        assertNull(result.items.get(0).documentation);
    }
}

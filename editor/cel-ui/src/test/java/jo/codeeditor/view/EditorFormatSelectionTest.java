package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.EmptyLanguage;
import jo.codeeditor.lang.Language;
import jo.codeeditor.session.EditorSession;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Disponibilité du formatage (3.74.0, L2) et garanties du cycle de vie
 * côté VUE : {@code setLanguage(null)} (ce que
 * {@code LspEditor.disconnect()} restaure via l'hôte) efface TOUS les
 * résolveurs, et {@code canFormat()} / {@code canFormatSelection()}
 * reflètent le résolveur courant.
 *
 * <p>Rouge avant le correctif 3.74.0 : l'API de disponibilité
 * n'EXISTAIT pas (C5 — le résolveur de formatage de la vue était
 * package-private, l'hôte ne pouvait ni masquer ni griser son action
 * « Formater ») : le test ne compilait même pas.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorFormatSelectionTest {

    /**
     * Langage factice qui branche UN provider de CHAQUE capacité — pour
     * vérifier que setLanguage(null) efface TOUT.
     */
    private static final class LangageFactice implements Language {
        @Override
        public Analyzer getAnalyzer() {
            return new EmptyLanguage().getAnalyzer();
        }

        @Override
        public void destroy() {}

        @Override
        public int getInterruptionLevel() {
            return INTERRUPTION_LEVEL_NONE;
        }

        @Override
        public jo.codeeditor.lang.provider.CompletionProvider getCompletionProvider() {
            return new jo.codeeditor.lang.provider.CompletionProvider() {
                @Override
                public void complete(CharSequence text, int caret,
                        jo.codeeditor.lang.provider.CompletionPublisher publisher) { }
                @Override
                public List<String> getTriggerCharacters() {
                    return java.util.Collections.emptyList();
                }
            };
        }

        @Override
        public jo.codeeditor.lang.provider.SignatureHelpProvider getSignatureHelpProvider() {
            return new jo.codeeditor.lang.provider.SignatureHelpProvider() {
                @Override
                public jo.codeeditor.lang.model.SignatureHelp signatureHelp(
                        CharSequence text, int caret) {
                    return null;
                }
                @Override
                public List<String> getTriggerCharacters() {
                    return java.util.Collections.emptyList();
                }
            };
        }

        @Override
        public jo.codeeditor.lang.provider.HoverProvider getHoverProvider() {
            return (text, offset) -> null;
        }

        @Override
        public jo.codeeditor.lang.provider.CodeActionsProvider getCodeActionsProvider() {
            return new jo.codeeditor.lang.provider.CodeActionsProvider() {
                @Override
                public List<jo.codeeditor.lang.model.CodeAction> codeActions(
                        CharSequence text, int line) {
                    return java.util.Collections.emptyList();
                }
            };
        }

        @Override
        public jo.codeeditor.lang.provider.SymbolProvider getSymbolProvider() {
            return (text) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.DiagnosticsProvider getDiagnosticsProvider() {
            return (text) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.DefinitionProvider getDefinitionProvider() {
            return (text, offset) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.TypeDefinitionProvider getTypeDefinitionProvider() {
            return (text, offset) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.ImplementationsProvider getImplementationsProvider() {
            return (text, offset) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.SuperDefinitionProvider getSuperDefinitionProvider() {
            return (text, offset) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.ReferencesProvider getReferencesProvider() {
            return (text, offset) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.DocumentHighlightProvider getDocumentHighlightProvider() {
            return (text, offset) -> java.util.Collections.emptyList();
        }

        @Override
        public jo.codeeditor.lang.provider.RenameProvider getRenameProvider() {
            return (text, offset, newName) -> null;
        }

        @Override
        public jo.codeeditor.lang.provider.InlayHintProvider getInlayHintProvider() {
            return (text, startLine, endLine) -> java.util.Collections.emptyList();
        }
    }

    private EditorView newView(String texte) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(texte));
        view.setSession(session);
        return view;
    }

    // ── L1 (côté vue) : setLanguage(null) efface tous les résolveurs ──

    @Test
    public void setLanguageNull_effaceTousLesRésolveurs() {
        EditorView view = newView("class A {}\n");
        view.setLanguage(new LangageFactice());
        // Préconditions : chaque capacité est branchée.
        assertNotNull("précondition : complétion branchée", view.completionProvider);
        assertNotNull("précondition : signature branchée", view.signatureHelpResolver);
        assertNotNull("précondition : quick doc branché", view.quickDocResolver);
        assertNotNull("précondition : actions branchées", view.codeActionsResolver);
        assertNotNull("précondition : symboles branchés", view.symbolResolver);
        assertNotNull("précondition : définitions branchées", view.definitionResolver);
        assertNotNull("précondition : occurrences branchées", view.documentHighlightResolver);
        assertNotNull("précondition : renommage branché", view.renameResolver);

        // Ce que disconnect() restaure via l'hôte : setLanguage(null).
        view.setLanguage(null);

        assertNull("formateur effacé", view.formatterResolver);
        assertNull("complétion effacée", view.completionProvider);
        assertNull("aide de signature effacée", view.signatureHelpResolver);
        assertNull("quick doc effacé", view.quickDocResolver);
        assertNull("actions de code effacées", view.codeActionsResolver);
        assertNull("symboles effacés", view.symbolResolver);
        assertNull("définitions effacées", view.definitionResolver);
        assertNull("type-définitions effacées", view.typeDefinitionResolver);
        assertNull("implémentations effacées", view.implementationsResolver);
        assertNull("super effacé", view.superResolver);
        assertNull("références effacées", view.referencesController.resolver);
        assertNull("occurrences effacées", view.documentHighlightResolver);
        assertNull("renommage effacé", view.renameResolver);
        assertNull("renommage (préparation) effacé", view.renamePrepareResolver);
        assertTrue("indices en ligne purgés",
                view.getSession().getInlayHints().isEmpty());
        assertTrue("diagnostics purgés",
                view.getSession().getDiagnostics().isEmpty());
    }

    // ── L2 : canFormat / canFormatSelection (C5) ────────────────────

    @Test
    public void canFormat_reflèteLeRésolveurCourant() {
        EditorView view = newView("class A {}\n");
        assertFalse("aucun résolveur : pas de formateur", view.canFormat());
        assertFalse(view.canFormatSelection());

        view.setFormatterResolver(new EditorView.FormatterResolver() {
            @Override
            public String format(String text) { return text; }
        });
        assertTrue("formateur installé", view.canFormat());
        assertFalse("supportsRange défaut faux : document seulement",
                view.canFormatSelection());

        view.setFormatterResolver(new EditorView.FormatterResolver() {
            @Override
            public String format(String text) { return text; }
            @Override
            public boolean supportsRange() { return true; }
        });
        assertTrue("formateur à plage", view.canFormat());
        assertTrue(view.canFormatSelection());

        view.setFormatterResolver(null);
        assertFalse("résolveur retiré", view.canFormat());
        assertFalse(view.canFormatSelection());
    }
}

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

    // ── L4 : formatSelection ────────────────────────────────────────

    /** Réception asynchrone du callback (voie features + post). */
    private static final class Rappel {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        volatile Boolean reçu;
        void accepter(Boolean b) { reçu = b; latch.countDown(); }
        Boolean attendre() throws InterruptedException {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return reçu;
        }
    }

    /** Texte de base : lignes 1-3 non indentées, la « bonne » indentation
     *  est 2 espaces par niveau. */
    private static final String TEXTE = "class A {\nvoid m() {\nx();\n}\n}\n";
    /** TEXTE avec lignes 1-3 ([10, 28)) réindentées : le résultat attendu. */
    private static final String TEXTE_FORMATÉ =
            "class A {\n  void m() {\n    x();\n  }\n}\n";

    private static EditorView.FormatterResolver formateurÀPlage() {
        return new EditorView.FormatterResolver() {
            @Override
            public String format(String text) { return text; }
            @Override
            public boolean supportsRange() { return true; }
            @Override
            public String formatRange(String text, int start, int end) {
                return new StringBuilder(text)
                        .replace(start, end, "  void m() {\n    x();\n  }\n")
                        .toString();
            }
        };
    }

    @Test
    public void formatSelection_formateLaPlage_unSeulPasDUndoCaretConservé()
            throws Exception {
        EditorView view = newView(TEXTE);
        // Sélection = lignes 1 à 3 ("void m() {\nx();\n}") : [10, 28).
        view.getSession().setSelection(new jo.codeeditor.document.Selection(10, 28));
        view.setFormatterResolver(formateurÀPlage());

        Rappel rappel = new Rappel();
        view.formatSelection(rappel::accepter);

        assertEquals("callback TRUE : le texte a changé", Boolean.TRUE, rappel.attendre());
        assertEquals(TEXTE_FORMATÉ, view.getSession().getText().toString());
        // Sélection conservée (bornée au nouveau texte).
        assertEquals(10, view.getSession().getSelection().start);
        assertEquals(28, view.getSession().getSelection().end);
        // UN SEUL pas d'undo : un undo() restaure TOUT l'original.
        assertTrue(view.getSession().undo());
        assertEquals(TEXTE, view.getSession().getText().toString());
        assertFalse("un seul pas d'undo pour le formatage de sélection",
                view.getSession().undo());
    }

    @Test
    public void formatSelection_sansPlage_refusPropreTexteIntact() throws Exception {
        EditorView view = newView(TEXTE);
        view.getSession().setSelection(new jo.codeeditor.document.Selection(10, 28));
        view.setFormatterResolver(new EditorView.FormatterResolver() {
            @Override
            public String format(String text) {
                throw new AssertionError("le document entier ne part jamais "
                        + "à la place d'une plage");
            }
        });

        Rappel rappel = new Rappel();
        view.formatSelection(rappel::accepter);

        assertEquals("callback FALSE : refus propre (pas de capacité de plage)",
                Boolean.FALSE, rappel.attendre());
        assertEquals("rien n'a été modifié", TEXTE,
                view.getSession().getText().toString());
    }

    @Test
    public void formatSelection_sansFormateur_rappelNull() throws Exception {
        EditorView view = newView(TEXTE);
        view.getSession().setSelection(new jo.codeeditor.document.Selection(10, 28));

        Rappel rappel = new Rappel();
        view.formatSelection(rappel::accepter);

        assertNull("callback null : aucun formateur installé", rappel.attendre());
        assertEquals(TEXTE, view.getSession().getText().toString());
    }

    @Test
    public void formatSelection_sélectionVide_noOp() throws Exception {
        EditorView view = newView(TEXTE);
        view.getSession().setSelection(
                jo.codeeditor.document.Selection.cursor(12));
        view.setFormatterResolver(new EditorView.FormatterResolver() {
            @Override
            public String format(String text) { return text; }
            @Override
            public boolean supportsRange() { return true; }
            @Override
            public String formatRange(String text, int start, int end) {
                throw new AssertionError("une sélection vide ne formate rien");
            }
        });

        Rappel rappel = new Rappel();
        view.formatSelection(rappel::accepter);

        assertEquals("callback FALSE : sélection vide = no-op", Boolean.FALSE,
                rappel.attendre());
        assertEquals(TEXTE, view.getSession().getText().toString());
    }

    @Test
    public void formatSelection_commandeEtRaccourciSansCollision() {
        // La table par défaut lie FORMAT_SELECTION à Ctrl+Shift+K —
        // aucune autre commande n'y vit (les voisins : Ctrl+Shift+I =
        // formatage du document, Ctrl+Shift+L = actions au caret).
        jo.codeeditor.view.input.EditorKeymap km =
                jo.codeeditor.view.input.EditorKeymap.defaults();
        jo.codeeditor.view.input.EditorKeymap.Binding b =
                km.resolve(android.view.KeyEvent.KEYCODE_K, true, true);
        assertNotNull("Ctrl+Shift+K est lié par défaut", b);
        assertEquals(jo.codeeditor.view.EditorCommands.FORMAT_SELECTION, b.command);
        assertEquals("Ctrl+Shift+I reste le formatage de document",
                jo.codeeditor.view.EditorCommands.FORMAT_DOCUMENT,
                km.resolve(android.view.KeyEvent.KEYCODE_I, true, true).command);

        // Le dispatch du raccourci clavier physique déclenche bien le
        // formatage de la sélection (observable par le texte réindenté).
        EditorView view = newView(TEXTE);
        view.setKeymap(km);
        view.getSession().setSelection(new jo.codeeditor.document.Selection(10, 28));
        view.setFormatterResolver(formateurÀPlage());
        boolean consommé = view.onKeyDown(android.view.KeyEvent.KEYCODE_K,
                new android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_K, 0,
                        android.view.KeyEvent.META_CTRL_ON
                                | android.view.KeyEvent.META_SHIFT_ON));
        assertTrue("Ctrl+Shift+K consommé par l'éditeur", consommé);
        assertTrue("le texte a été réindenté par le raccourci",
                jo.codeeditor.AttenteBornee.jusquA(5000,
                        () -> TEXTE_FORMATÉ.equals(
                                view.getSession().getText().toString())));
    }
}

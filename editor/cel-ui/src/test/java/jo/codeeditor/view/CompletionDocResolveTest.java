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
import java.util.concurrent.atomic.AtomicReference;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.BracketMatch;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.model.CodeBlock;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.state.CompletionState;

import static org.junit.Assert.*;

/**
 * Résolution paresseuse de la documentation du popup (4.7.0) : un
 * candidat SANS documentation sélectionné (flèche, lot, refiltrage)
 * déclenche — après séjour — UNE requête au provider de langage
 * (resolveDocumentation) ; la documentation livrée remplit le panneau
 * SANS bouger l'ancre (elle vit à la ligne du caret) ; tout mouvement
 * RESET le séjour ; une réponse périmée (sélection bougée, popup
 * refermé) est jetée ; les candidats déjà documentés ne déclenchent
 * RIEN.
 *
 * <p>Harnais sans fenêtre : le handler est null, le séjour s'exécute
 * immédiatement (discipline du debounce complétion) — le faux provider
 * répond de façon SYNCHRONE, les gardes de génération restent
 * exerçables en déplaçant la sélection APRÈS coup.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionDocResolveTest {

    private static final String DOC =
            "public class Main {\n    obje\n    obje\n    obje\n    obje\n}\n";

    /** Compteur des requêtes de résolution reçues par le faux provider. */
    private final AtomicInteger resolveRequests = new AtomicInteger();
    /** Dernier libellé demandé au provider. */
    private final AtomicReference<String> lastResolvedLabel = new AtomicReference<>();
    /** Ce que le faux provider sert (null = silence). */
    private volatile String providerDoc =
            "Ajoute un élément à la fin de la liste. La capacité interne "
            + "est doublée si nécessaire. @param x l'élément à ajouter "
            + "@return vrai si la liste a changé";

    private EditorView viewAvecLangage(CompletionSession.Item... items) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        session.setSelection(DOC.indexOf("obje") + 4);
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
            public jo.codeeditor.lang.provider.CompletionProvider getCompletionProvider() {
                return new jo.codeeditor.lang.provider.CompletionProvider() {
                    @Override
                    public void complete(CharSequence text, int caret,
                            jo.codeeditor.lang.provider.CompletionPublisher publisher) {
                    }

                    @Override
                    public List<String> getTriggerCharacters() {
                        return Collections.emptyList();
                    }

                    @Override
                    public void resolveDocumentation(String label,
                            java.util.function.Consumer<String> callback) {
                        resolveRequests.incrementAndGet();
                        lastResolvedLabel.set(label);
                        callback.accept(providerDoc);
                    }
                };
            }
            @Override
            public int getInterruptionLevel() {
                return Language.INTERRUPTION_LEVEL_NONE;
            }
            @Override
            public void destroy() {}
        });
        view.completion.items.clear();
        view.completion.items.addAll(Arrays.asList(items));
        view.completion.baseItems = new ArrayList<>(Arrays.asList(items));
        view.completion.visible = true;
        view.completion.selected = 0;
        view.completion.scrollOffset = 0;
        view.completion.tokenStart = DOC.indexOf("obje");
        view.completion.prefix = "obje";
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private static CompletionSession.Item item(String label, String documentation) {
        return new CompletionSession.Item(
                label, "java.lang.Object", label, "m", 2, 10, false, false,
                null, null, null, -1, -1, null,
                "java.util", documentation);
    }

    @Test
    public void candidatSansDoc_séjourné_uneRequête_panneauRempli() {
        EditorView view = viewAvecLangage(item("obje", null));
        assertEquals(0, view.completion.docPanelRows.size());

        // Le séjour (exécution immédite sans fenêtre) : UNE requête part,
        // la documentation livrée remplit le panneau de l'item sélectionné.
        view.popupManager.scheduleCompletionDocResolve();

        assertEquals(1, resolveRequests.get());
        assertEquals("obje", lastResolvedLabel.get());
        assertEquals("La documentation résolue est posée sur l'item",
                providerDoc, view.completion.items.get(0).documentation);
        assertTrue("le panneau est rempli pour le peintre",
                !view.completion.docPanelRows.isEmpty());
    }

    @Test
    public void candidatDocumenté_aucuneRequête() {
        EditorView view = viewAvecLangage(
                item("obje", "Documentation directe du lot"));
        view.popupManager.scheduleCompletionDocResolve();
        assertEquals("une documentation déjà présente ne re-résout rien",
                0, resolveRequests.get());
    }

    @Test
    public void flèche_leSéjourRepartDeZéro_puisRésout() {
        EditorView view = viewAvecLangage(
                item("objetA", null), item("objetB", null));
        // Sélection sur B (index 1) : le séjour repart de zéro et résout B.
        view.completionSelectDown();
        assertEquals(1, resolveRequests.get());
        assertEquals("objetB", lastResolvedLabel.get());
        assertEquals(providerDoc, view.completion.items.get(1).documentation);
    }

    @Test
    public void réponsePérimée_sélectionBougée_jetée() {
        // Le provider retarde sa réponse AU-DELÀ du mouvement : la
        // livraison doit être jetée par la garde de génération/libellé.
        final java.util.List<java.util.function.Consumer<String>> enAttente =
                new ArrayList<>();
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        session.setSelection(DOC.indexOf("obje") + 4);
        view.setSession(session);
        view.setLanguage(new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return new Analyzer() {
                    @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) {}
                    @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) {}
                    @Override public void reset(CharSequence t) {}
                    @Override public StyledLine styledLine(int line) { return null; }
                    @Override public List<CodeBlock> computeBlocks() { return Collections.emptyList(); }
                    @Override public BracketMatch computeBracketMatch(int o) { return null; }
                    @Override public void destroy() {}
                };
            }
            @Override
            public jo.codeeditor.lang.provider.CompletionProvider getCompletionProvider() {
                return new jo.codeeditor.lang.provider.CompletionProvider() {
                    @Override
                    public void complete(CharSequence text, int caret,
                            jo.codeeditor.lang.provider.CompletionPublisher publisher) {}
                    @Override
                    public List<String> getTriggerCharacters() { return Collections.emptyList(); }
                    @Override
                    public void resolveDocumentation(String label,
                            java.util.function.Consumer<String> callback) {
                        resolveRequests.incrementAndGet();
                        enAttente.add(callback); // réponse différée par le test
                    }
                };
            }
            @Override
            public int getInterruptionLevel() {
                return Language.INTERRUPTION_LEVEL_NONE;
            }
            @Override
            public void destroy() {}
        });
        view.completion.items.clear();
        view.completion.items.addAll(Arrays.asList(
                item("objetA", null), item("objetB", null)));
        view.completion.visible = true;
        view.completion.selected = 0;
        view.completion.tokenStart = DOC.indexOf("obje");
        view.completion.prefix = "obje";
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        // Le séjour part pour objetA ; AVANT la réponse, la sélection
        // descend sur objetB (nouveau séjour, génération bumpée).
        view.popupManager.scheduleCompletionDocResolve();
        assertEquals(1, resolveRequests.get());
        view.completionSelectDown();
        assertEquals(2, resolveRequests.get());

        // La réponse périmée de objetA arrive APRÈS : jetée.
        enAttente.get(0).accept("Documentation périmée de objetA");
        assertNull("la réponse périmée ne touche pas l'item",
                view.completion.items.get(0).documentation);
        assertNull("objetB attend SA réponse, pas celle de objetA",
                view.completion.items.get(1).documentation);

        // La réponse de objetB arrive : elle EST livrée.
        enAttente.get(1).accept(providerDoc);
        assertEquals(providerDoc, view.completion.items.get(1).documentation);
    }

    @Test
    public void dismiss_laLivraisonEnVolEstJetée() {
        final java.util.List<java.util.function.Consumer<String>> enAttente =
                new ArrayList<>();
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        session.setSelection(DOC.indexOf("obje") + 4);
        view.setSession(session);
        view.setLanguage(new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return new Analyzer() {
                    @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) {}
                    @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) {}
                    @Override public void reset(CharSequence t) {}
                    @Override public StyledLine styledLine(int line) { return null; }
                    @Override public List<CodeBlock> computeBlocks() { return Collections.emptyList(); }
                    @Override public BracketMatch computeBracketMatch(int o) { return null; }
                    @Override public void destroy() {}
                };
            }
            @Override
            public jo.codeeditor.lang.provider.CompletionProvider getCompletionProvider() {
                return new jo.codeeditor.lang.provider.CompletionProvider() {
                    @Override
                    public void complete(CharSequence text, int caret,
                            jo.codeeditor.lang.provider.CompletionPublisher publisher) {}
                    @Override
                    public List<String> getTriggerCharacters() { return Collections.emptyList(); }
                    @Override
                    public void resolveDocumentation(String label,
                            java.util.function.Consumer<String> callback) {
                        enAttente.add(callback);
                    }
                };
            }
            @Override
            public int getInterruptionLevel() {
                return Language.INTERRUPTION_LEVEL_NONE;
            }
            @Override
            public void destroy() {}
        });
        view.completion.items.clear();
        view.completion.items.addAll(Collections.singletonList(item("obje", null)));
        view.completion.visible = true;
        view.completion.selected = 0;
        view.completion.tokenStart = DOC.indexOf("obje");
        view.completion.prefix = "obje";
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        view.popupManager.scheduleCompletionDocResolve();
        view.dismissCompletion();
        enAttente.get(0).accept(providerDoc);
        assertFalse(view.completion.visible);
        assertTrue("popup refermé : la livraison tardive ne réouvre rien",
                view.completion.items.isEmpty());
    }

    @Test
    public void documentationLivrée_ancreStable_bandeDuCaretRespectée() {
        EditorView view = viewAvecLangage(item("obje", null));
        float[] avant = view.completionPopupAnchor();
        assertEquals("l'ancre 4.4.0 a 6 éléments", 6, avant.length);
        assertEquals("sans documentation, pas de panneau", 0f, avant[5], 0f);

        view.popupManager.scheduleCompletionDocResolve();

        float[] apres = view.completionPopupAnchor();
        assertTrue("le panneau est compté dans l'ancre",
                apres[5] > 0f);
        // L'ancre vit à la ligne du caret : la bande du caret n'est
        // JAMAIS recouverte — le popup grandit en s'éloignant de la
        // bande, la garde 4.4.0 tient avec la documentation résolue.
        float[] band = bandDuCaret(view);
        boolean auDessus = apres[1] <= band[0];
        boolean auDessous = apres[3] >= band[1];
        assertTrue("le popup complété ne recouvre pas la bande du caret",
                auDessus || auDessous);
        // Et la fenêtre de scroll reste solidaire de la sélection.
        assertTrue(view.completion.selected >= view.completion.scrollOffset);
        assertTrue(view.completion.selected
                < view.completion.scrollOffset + view.popupManager.completionRowsVisible());
    }

    @Test
    public void docRésolue_plafonnéeCommeLaDirecte() {
        EditorView view = viewAvecLangage(item("obje", null));
        view.popupManager.scheduleCompletionDocResolve();
        assertTrue("le panneau plafonne comme la documentation directe",
                view.completion.docPanelRows.size() <= CompletionState.DOC_PANEL_MAX_LINES);
    }

    /** La bande verticale de la ligne du caret (px) — miroir simple de
     *  completionCaretBand pour l'assertion d'ancre. */
    private static float[] bandDuCaret(EditorView view) {
        // L'ancre [x, yTop, x, yBottom, largeur, panneau] : le popup est
        // posé AU-DESSUS (yTop..yBottom = segment vertical) ou en
        // dessous — la bande du caret vit entre les deux candidats
        // d'ancrage. Pour l'assertion, on utilise la position du caret
        // en pixels via la géométrie de la vue.
        int line = view.session.getDocument().lineForOffset(
                view.session.getSelection().start);
        float y = line * view.metrics.getLineHeight() - view.getVOffset();
        return new float[]{y, y + view.metrics.getLineHeight()};
    }
}

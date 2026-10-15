package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.BracketMatch;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.model.CodeBlock;
import jo.codeeditor.lang.provider.CompletionPublisher;
import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Famille XML — la porte de contexte (Lot 3, audit v3.41.0) doit
 * LAISSER PASSER la complétion : son prédicat {@code isInStringOrComment}
 * est calibré pour les littéraux C/Java et se trompe sur XML de deux
 * façons constatées en production (CodeIDE, édition de layouts Android
 * en français) :
 *
 * <ul>
 *   <li>le curseur entre les guillemets d'une VALEUR d'attribut
 *       {@code android:text="|…} est une zone de complétion légitime
 *       (le serveur LSP XML propose références et énumérations) mais
 *       était classée « dans une chaîne littérale » → dismiss ;</li>
 *   <li>une APOSTROPHE dans le texte français (« Nom d'utilisateur »)
 *       ouvrait un littéral char jamais refermé → tout le fichier en
 *       dessous était classé « dans une chaîne » → plus AUCUNE
 *       complétion (balises ni attributs) sous cette ligne.</li>
 * </ul>
 *
 * <p>Correctif : pour la famille {@code SyntaxFamily.XML}, la porte
 * chaîne/commentaire est écartée — le serveur LSP tranche lui-même
 * les zones muettes (texte, fermantes, commentaires {@code <!--},
 * PI {@code <?}). Le cas « valeur d'attribut » utilise un Language
 * dont le char déclencheur est {@code "} (miroir du serveur LSP XML
 * de CodeIDE, qui déclare {@code <}, {@code =}, {@code :} et
 * {@code "}) : le préfixe vide juste après la quote ouvrante est une
 * ouverture légitime.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionContextGateXmlTest {

    private EditorView newViewWithDoc(String doc, int caretOffset, boolean languageAvecQuote) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setSelection(caretOffset);
        session.setLanguage("xml");
        view.setSession(session);
        if (languageAvecQuote) {
            // Miroir du serveur LSP XML réel : « " » est un char
            // déclencheur (ouverture de valeur d'attribut).
            view.setLanguage(new Language() {
                @Override
                public Analyzer getAnalyzer() {
                    return new Analyzer() {
                        @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) {}
                        @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) {}
                        @Override public void reset(CharSequence t) {}
                        @Override public StyledLine styledLine(int line) { return null; }
                        @Override public java.util.List<CodeBlock> computeBlocks() {
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
                            // No-op — l'assertion passe par le compteur.
                        }
                        @Override
                        public java.util.List<String> getTriggerCharacters() {
                            return Collections.singletonList("\"");
                        }
                    };
                }
                @Override public int getInterruptionLevel() { return INTERRUPTION_LEVEL_NONE; }
                @Override public void destroy() {}
            });
        }
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /** Provider compteur : le latch tombe si provide() est appelé. */
    private CountDownLatch installCountingProvider(EditorView view) {
        final CountDownLatch latch = new CountDownLatch(1);
        view.setCompletionProvider((text, caret, tokenStart, prefix) -> {
            latch.countDown();
            return Collections.emptyList();
        });
        return latch;
    }

    /**
     * Curseur DANS une valeur d'attribut en frappe
     * (android:orientation="|) : la complétion doit partir — c'est
     * la zone légitime des propositions de valeurs (énumérations,
     * références @…). Le char « " » est déclencheur (préfixe vide
     * légitime), et la porte chaîne/commentaire est écartée pour
     * la famille XML.
     */
    @Test
    public void completion_triggeredInsideXmlAttributeValue() throws Exception {
        String doc = "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:orientation=\"\">\n"
            + "</LinearLayout>\n";
        // Curseur juste après la quote OUVRANTE de la valeur.
        int caret = doc.indexOf("android:orientation=\"") + "android:orientation=\"".length();
        EditorView view = newViewWithDoc(doc, caret, true);
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(2, TimeUnit.SECONDS);
        assertTrue(
            "La complétion DOIT se déclencher dans une valeur d'attribut XML "
            + "(zone légitime : énumérations, références).",
            triggered);
    }

    /**
     * Apostrophe française dans un contenu texte (« Nom d'utilisateur »)
     * AU-DESSUS du curseur : la complétion doit continuer de
     * fonctionner en dessous (avant le correctif, l'apostrophe ouvrait
     * un littéral char jamais refermé → tout le reste du fichier était
     * « dans une chaîne »).
     */
    @Test
    public void completion_triggeredBelowFrenchApostrophe() throws Exception {
        String doc = "<resources>\n"
            + "    <string name=\"nom\">Nom d'utilisateur</string>\n"
            + "    <string name=\"au";
        // Curseur sur le dernier <string (préfixe non vide « au »),
        // SOUS l'apostrophe de « d'utilisateur ».
        int caret = doc.length();
        EditorView view = newViewWithDoc(doc, caret, false);
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(2, TimeUnit.SECONDS);
        assertTrue(
            "La complétion DOIT se déclencher sous une apostrophe française "
            + "(l'heuristique char-littéral ne s'applique pas au XML).",
            triggered);
    }

    /**
     * Balise en frappe avec préfixe non vide (« <Linear| ») dans un
     * document SANS apostrophe : doit fonctionner comme avant (le
     * correctif ne régresse pas le cas sain).
     */
    @Test
    public void completion_triggeredInsideTagPrefix() throws Exception {
        String doc = "<Linear";
        EditorView view = newViewWithDoc(doc, doc.length(), false);
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(2, TimeUnit.SECONDS);
        assertTrue("La complétion doit se déclencher sur un préfixe de balise.", triggered);
    }

    /**
     * Non-régression Java : la porte chaîne/commentaire reste active
     * pour la famille C-like (le correctif ne touche QUE la famille
     * XML).
     */
    @Test
    public void completion_stillBlockedInJavaString() throws Exception {
        String doc = "String s = \"abc";
        EditorView view = newViewWithDoc(doc, doc.length(), false);
        view.getSession().setLanguage("java");
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(200, TimeUnit.MILLISECONDS);
        assertFalse(
            "La porte chaîne/commentaire reste active pour Java "
            + "(caret dans un littéral String).",
            triggered);
    }
}

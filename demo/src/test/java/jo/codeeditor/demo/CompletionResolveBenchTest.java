package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.lang.provider.CompletionPublisher;
import jo.codeeditor.lang.provider.CompletionProvider;

/**
 * Le banc de la résolution différée (4.7.0) : le mini serveur Java de
 * la démo annonce désormais {@code completionProvider} avec
 * {@code resolveProvider} — la chaîne COMPLÈTE (pont in-process,
 * négociation de capacités, liste servie sans documentation pour
 * add/addAll, {@code completionItem/resolve}, cache côté client,
 * dwell du popup) est observable dans l'atelier : moteur LSP branché
 * sur l'onglet Java, les candidats d'ajout reçoivent leur documentation
 * APRÈS un séjour de la sélection — journal à l'appui.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34,
          qualifiers = "w480dp-h960dp")
public class CompletionResolveBenchTest {

    /** Un publisher collecteur simple. */
    private static final class Collecteur implements CompletionPublisher {
        final List<jo.codeeditor.lang.model.CompletionItem> items =
                new ArrayList<>();

        @Override
        public void addItem(jo.codeeditor.lang.model.CompletionItem item) {
            items.add(item);
        }

        @Override
        public void addItems(List<jo.codeeditor.lang.model.CompletionItem> list) {
            items.addAll(list);
        }

        @Override
        public void flush() {}

        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() { return false; }
    }

    @Test
    public void leMiniServeurSertLaListePuisRésoutLaDocumentation() throws Exception {
        new EditorPrefs(RuntimeEnvironment.getApplication())
                .set(EditorPrefs.keyLspAuto(), true);
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue("Le moteur LSP doit se brancher sur l'onglet Java",
                        activity.lsp.awaitConnected(15000));
            });
            scenario.onActivity(activity -> {
                CompletionProvider provider =
                        activity.editor.language.getCompletionProvider();
                assertNotNull("Le langage LSP expose son provider de complétion",
                        provider);

                // 1. La liste : toString documentée DIRECTEMENT, add et
                //    addAll PARESSEUX (aucune documentation dans la liste).
                Collecteur lot = new Collecteur();
                String texte = activity.editor.session.getText();
                provider.complete(texte, texte.length(), lot);
                assertTrue("Le mini serveur sert sa base de candidats",
                        lot.items.size() >= 4);
                jo.codeeditor.lang.model.CompletionItem toString = chercher(lot, "toString");
                jo.codeeditor.lang.model.CompletionItem add = chercher(lot, "add");
                jo.codeeditor.lang.model.CompletionItem addAll = chercher(lot, "addAll");
                assertNotNull(toString);
                assertNotNull(add);
                assertNotNull(addAll);
                assertEquals("La documentation directe voyage dans la liste",
                        "La représentation textuelle de l'élément",
                        toString.documentation);
                assertNull("add est servi SANS documentation (résolution différée)",
                        add.documentation);
                assertNull("addAll est servi SANS documentation",
                        addAll.documentation);

                // 2. La résolution : la documentation d'add ne vient qu'au
                //    completionItem/resolve, asynchrone sur le pont.
                CompletableFuture<String> résolue = new CompletableFuture<>();
                provider.resolveDocumentation("add", résolue::complete);
                String doc;
                try {
                    doc = résolue.get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("La résolution ne répond pas", e);
                }
                assertNotNull("add reçoit sa documentation au resolve", doc);
                assertTrue("La documentation d'add parle d'ajout : " + doc,
                        doc.contains("Ajoute un élément"));
            });
        }
    }

    @Test
    public void leDwellDuPopupRemplitLePanneauAvecLeVraiServeur() throws Exception {
        new EditorPrefs(RuntimeEnvironment.getApplication())
                .set(EditorPrefs.keyLspAuto(), true);
        final EditorActivity[] ancreActivité = new EditorActivity[1];
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.lsp.awaitConnected(15000));
                ancreActivité[0] = activity;

                CompletionProvider provider =
                        activity.editor.language.getCompletionProvider();
                assertNotNull(provider);

                // Le lot servi par le VRAI mini serveur, posé dans l'état
                // du popup avec la sélection sur add (le candidat
                // paresseux) — puis le séjour (dwell).
                Collecteur lot = new Collecteur();
                String texte = activity.editor.session.getText();
                provider.complete(texte, texte.length(), lot);
                List<CompletionSession.Item> items = new ArrayList<>();
                for (jo.codeeditor.lang.model.CompletionItem m : lot.items) {
                    items.add(new CompletionSession.Item(
                            m.label, m.detail != null ? m.detail : "",
                            m.insertText, "m", m.kindCode, m.sortPriority,
                            false, m.isSnippet, m.kindTag, m.filterText,
                            m.sortText, m.replaceStart, m.replaceEnd, null,
                            m.container, m.documentation));
                }
                activity.editor.completion.items.clear();
                activity.editor.completion.items.addAll(items);
                activity.editor.completion.visible = true;
                int sélection = -1;
                for (int i = 0; i < items.size(); i++) {
                    if ("add".equals(items.get(i).label)) {
                        sélection = i;
                        break;
                    }
                }
                assertTrue("La sélection est posée sur add", sélection >= 0);
                activity.editor.completion.selected = sélection;
                activity.editor.completion.tokenStart = 0;
                activity.editor.completion.prefix = "";

                activity.editor.popupManager.scheduleCompletionDocResolve();
            });

            // Vue ATTACHÉE : le handler est non nul, le dwell (200 ms)
            // et la livraison sont postés au looper principal — PAUSÉ en
            // Robolectric. On l'avance en boucle : le dwell part, la
            // requête voyage sur le pont in-process, la livraison
            // revient au looper.
            EditorActivity activity = ancreActivité[0];
            String doc = null;
            long fin = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < fin) {
                org.robolectric.Shadows.shadowOf(
                        android.os.Looper.getMainLooper()).runToEndOfTasks();
                int sel = activity.editor.completion.selected;
                if (sel >= 0 && sel < activity.editor.completion.items.size()) {
                    String d = activity.editor.completion.items.get(sel).documentation;
                    if (d != null && !d.isEmpty()) {
                        doc = d;
                        break;
                    }
                }
                Thread.sleep(50);
            }
            assertNotNull("La documentation résolue finit par arriver", doc);
            assertTrue(doc.contains("Ajoute un élément"));

            // Le panneau se remplit (l'ancre force le calcul des lignes
            // plafonnées) sans bouger : la bande du caret reste
            // respectée.
            scenario.onActivity(a -> {
                float[] x = a.editor.completionPopupAnchor();
                assertEquals("L'ancre 4.4.0 garde ses 6 éléments", 6, x.length);
                assertTrue("Le panneau de documentation est rempli",
                        !a.editor.completion.docPanelLines.isEmpty());
            });
        }
    }

    private static jo.codeeditor.lang.model.CompletionItem chercher(
            Collecteur lot, String label) {
        for (jo.codeeditor.lang.model.CompletionItem m : lot.items) {
            if (label.equals(m.label)) {
                return m;
            }
        }
        return null;
    }
}

package jo.codeeditor.view;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.languages.CompletionGate;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ADR-002 (chantier C1) — le popup honore la porte de complétion PORTÉE
 * PAR LE PROFIL, plus la famille.
 *
 * <p>Avant C1, {@code EditorCompletionPopup.isInCodeContext} commutait
 * sur la famille : LOG → jamais de complétion, XML → porte écartée. Un
 * hôte enregistrant un langage de famille LOG mais voulant la complétion
 * (ou l'inverse pour XML) n'avait AUCUNE voie : le comportement était
 * codé dans l'UI. Désormais la politique vit dans
 * {@link jo.codeeditor.languages.CompletionGate}, donnée du
 * {@link LanguageProfile}.</p>
 *
 * <p>Rouge avant : les surcharges {@code completionGate(STANDARD)} sur
 * famille LOG et {@code completionGate(OFF)} sur famille XML étaient
 * ignorées (le switch famille tranchait avant de lire le profil).
 * Vert après : la donnée du profil décide.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionGateProfileTest {

    @After
    public void cleanup() {
        LanguageRegistry.unregister("cgp-mylog");
        LanguageRegistry.unregister("cgp-myxml");
    }

    private EditorView newViewWithDoc(String doc, int caretOffset, String language) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setSelection(caretOffset);
        session.setLanguage(language);
        view.setSession(session);
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
     * Langage de famille LOG avec porte STANDARD explicite : la complétion
     * DOIT partir (texte hors chaîne/commentaire = contexte de code). Avant
     * C1, le switch famille LOG coupait la complétion sans regarder le
     * profil — la surcharge était ignorée.
     */
    @Test
    public void log_surchargeStandard_complétionDéclenchée() throws Exception {
        LanguageRegistry.register(LanguageProfile.builder("cgp-mylog")
                .family(SyntaxFamily.LOG)
                .completionGate(CompletionGate.STANDARD)
                .build());
        String doc = "INFO démarrage du service";
        EditorView view = newViewWithDoc(doc, doc.length(), "cgp-mylog");
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        assertTrue("La porte STANDARD surchargée doit laisser passer la complétion "
                + "(la famille LOG ne doit plus couper sans lire le profil).",
                latch.await(2, TimeUnit.SECONDS));
    }

    /**
     * Langage de famille XML avec porte OFF explicite : AUCUNE complétion,
     * même dans une valeur d'attribut (zone qui passait toujours avant).
     * Avant C1, le switch famille XML forçait la porte écartée — la
     * surcharge était ignorée.
     */
    @Test
    public void xml_surchargeOff_aucuneComplétion() throws Exception {
        LanguageRegistry.register(LanguageProfile.builder("cgp-myxml")
                .family(SyntaxFamily.XML)
                .completionGate(CompletionGate.OFF)
                .build());
        // Curseur en fin de valeur d'attribut avec préfixe NON VIDE
        // (« val ») : la complétion se déclenche normalement quand la
        // porte est ouverte (famille XML = porte écartée avant C1) —
        // seul le profil porte la décision ici.
        String doc = "<Button android:text=\"val";
        int caret = doc.length();
        EditorView view = newViewWithDoc(doc, caret, "cgp-myxml");
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        // Debounce 80 ms : une fenêtre de 600 ms sans déclenchement est
        // une absence concluante (le chemin vert déclenche en ~80 ms).
        boolean triggered = latch.await(600, TimeUnit.MILLISECONDS);
        assertFalse("La porte OFF surchargée doit couper la complétion "
                + "(la famille XML ne doit plus forcer la porte écartée).",
                triggered);
    }

    /**
     * Non-régression : « log » intégré (famille LOG, défaut OFF) reste
     * muet — le défaut de famille descendu dans le profil conserve le
     * comportement historique.
     */
    @Test
    public void log_intégré_défautOff_complétionCoupée() throws Exception {
        String doc = "12:00:00.123 DEBUG service prêt";
        EditorView view = newViewWithDoc(doc, doc.length(), "log");
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(600, TimeUnit.MILLISECONDS);
        assertFalse("« log » intégré (défaut OFF) doit rester sans complétion.",
                triggered);
    }
}

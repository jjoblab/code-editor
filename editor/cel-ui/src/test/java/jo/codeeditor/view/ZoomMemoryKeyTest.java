package jo.codeeditor.view;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * B12 (P1-3) — la mémoire de zoom VIT dans la vue : chaque mutation
 * d'échelle s'enregistre sous la clé de type posée par l'hôte, la
 * repose applique le réglage du type, l'état d'instance B17 (rotation)
 * reste PLUS PRÉCIS que la mémoire du type.
 *
 * <p>Scénario du terrain : l'utilisateur pince le Java à 1,8, passe au
 * Markdown (retour à l'échelle 1 du Markdown), REVIENT au Java → 1,8
 * restauré. Sans la mémoire, chaque (ré)ouverture repartait de ×1 quel
 * que soit le réglage passé.</p>
 *
 * <p>Note d'environnement (parité EditorInstanceStateTest) : sous
 * Robolectric, setFontScale recalcule lineHeight depuis les métriques
 * réelles de la police — nulles dans les shadows — mais fontScale, LUI,
 * est un champ pur : le test porte sur lui.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ZoomMemoryKeyTest {

    @Before
    public void resetMemoire() {
        EditorZoomMemory.clear();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("ligne 0\nligne 1\n")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private Parcelable save(EditorView view) {
        return view.onSaveInstanceState();
    }

    private void restore(EditorView view, Parcelable state) {
        view.onRestoreInstanceState(state);
    }

    @Test
    public void leZoomSEnregistreSousLaClé() {
        EditorView view = newView();
        view.setZoomMemoryKey("java");
        // La pose de clé normalise au réglage du type (1 par défaut —
        // un type jamais zoomé est à l'échelle de base).
        assertEquals(1f, view.getFontScale(), 0f);
        view.setFontScale(1.8f);
        assertEquals(1.8f, EditorZoomMemory.recall("java"), 0f);
        assertEquals(1.8f, view.getFontScale(), 0f);
    }

    @Test
    public void sansClé_aucuneMémorisationPuisPoseNormale() {
        EditorView view = newView();
        view.setFontScale(1.5f);
        // Sans clé posée : la mutation ne s'enregistre nulle part.
        assertFalse(EditorZoomMemory.isRemembered("java"));
        assertEquals(1f, EditorZoomMemory.recall("java"), 0f);
        // Clé posée APRÈS le zoom : le zoom passé n'est PAS enregistré
        // rétroactivement — la clé repart du réglage du type (1).
        view.setZoomMemoryKey("java");
        assertEquals(1f, EditorZoomMemory.recall("java"), 0f);
        assertEquals(1f, view.getFontScale(), 0f);
        // La mémoire ne démarre qu'au prochain réglage.
        view.setFontScale(1.6f);
        assertEquals(1.6f, EditorZoomMemory.recall("java"), 0f);
    }

    @Test
    public void changementDeClé_appliqueLeZoomDUneAutreType() {
        EditorView view = newView();
        view.setZoomMemoryKey("java");
        view.setFontScale(1.8f);
        // Bascule vers un type jamais zoomé : échelle de base (le zoom
        // de Java ne FUITE PAS sur le Markdown).
        view.setZoomMemoryKey("markdown");
        assertEquals(1f, view.getFontScale(), 0f);
        // Java a gardé son réglage en mémoire…
        assertEquals(1.8f, EditorZoomMemory.recall("java"), 0f);
        // …et y REVIENT.
        view.setZoomMemoryKey("java");
        assertEquals(1.8f, view.getFontScale(), 0f);
    }

    @Test
    public void bascule_enregistreLeRéglageAvantDePartir() {
        EditorView view = newView();
        view.setZoomMemoryKey("markdown");
        view.setFontScale(0.8f);
        // Le zoom est posé SANS clé mémorisée pour « java » au départ :
        // la bascule doit enregistrer 0,8 sous « markdown » elle-même.
        view.setZoomMemoryKey("java"); // → enregistre markdown=0.8, applique java (1)
        assertEquals(0.8f, EditorZoomMemory.recall("markdown"), 0f);
        view.setZoomMemoryKey("markdown");
        assertEquals(0.8f, view.getFontScale(), 0f);
    }

    @Test
    public void cléNulle_désactive_laMémoire() {
        EditorView view = newView();
        view.setZoomMemoryKey("java");
        view.setFontScale(1.8f);
        view.setZoomMemoryKey(null); // désactive — échelle conservée
        assertEquals(1.8f, view.getFontScale(), 0f);
        view.setFontScale(1.2f); // ne s'enregistre nulle part
        assertEquals(1.8f, EditorZoomMemory.recall("java"), 0f);
    }

    @Test
    public void reposeLaMêmeClé_neRedéclencheRien() {
        EditorView view = newView();
        view.setZoomMemoryKey("java");
        view.setFontScale(1.8f);
        EditorZoomMemory.forget("java"); // simule l'oubli externe
        view.setZoomMemoryKey("java"); // même clé : no-op, PAS de reset à 1
        assertEquals(1.8f, view.getFontScale(), 0f);
    }

    @Test
    public void laVueRecréée_retrouveLeRéglageDuType() {
        EditorView première = newView();
        première.setZoomMemoryKey("java");
        première.setFontScale(1.6f);
        // « Réouverture » : une vue NEUVE (nouvel onglet/activité sans
        // état sauvé) pose la même clé → le réglage du type s'applique.
        EditorView réouverture = newView();
        réouverture.setZoomMemoryKey("java");
        assertEquals(1.6f, réouverture.getFontScale(), 0f);
    }

    @Test
    public void rotation_lÉtatDeVueRestePlusPrécisQueLaMémoire() {
        EditorView avant = newView();
        avant.setZoomMemoryKey("java");
        avant.setFontScale(1.7f);
        Parcelable état = save(avant);
        // Entre-temps, un autre onglet du même type zoome autrement.
        EditorZoomMemory.remember("java", 2.2f);
        // Recréation : l'hôte recâble (applique la mémoire 2.2)…
        EditorView après = newView();
        après.setZoomMemoryKey("java");
        assertEquals(2.2f, après.getFontScale(), 0f);
        // …puis la restauration d'état (rotation) l'emporte : CETTE vue
        // était à 1.7.
        restore(après, état);
        assertEquals(1.7f, après.getFontScale(), 0f);
    }
}

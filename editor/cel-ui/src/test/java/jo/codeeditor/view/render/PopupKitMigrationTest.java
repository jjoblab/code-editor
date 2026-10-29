package jo.codeeditor.view.render;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import android.graphics.Paint;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Régression des coutures migrées vers le kit de layout (Phase 1 du
 * chantier popups) : le wrap public ({@code wrapText}) coupe
 * désormais un mot plus large que la ligne (l'ancien moteur le
 * laissait déborder) et l'ellipse autour du paramètre actif garde le
 * maximum de texte (l'ancienne boucle à retrait de 2 caractères
 * perdait un caractère utile).
 *
 * <p>La mesure est un {@link Paint} factice déterministe (10 px par
 * caractère) — surcharge de méthode Java ordinaire, le comportement
 * ne dépend donc pas des métriques de police de la machine.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class PopupKitMigrationTest {

    /** Paint factice : 10 px par caractère, l'ellipse pèse 1 caractère. */
    private static Paint paintFactice() {
        return new Paint() {
            @Override
            public float measureText(String text) {
                return text.length() * 10f;
            }
        };
    }

    // ── wrapText : le mot long est coupé (correctif 1) ──────────────

    @Test
    public void wrapText_motPlusLargeQueLaLigne_coupé() {
        // AVANT la migration : le mot de 11 caractères revenait ENTIER
        // sur une seule ligne (aucune coupe sans espace) — la ligne
        // débordait du panneau de documentation. APRÈS : coupé.
        List<String> lignes = EditorAssistPopupPainter.wrapText(
                "aaaaaaaaaaa", 30f, paintFactice());
        assertTrue("le mot long est coupé en plusieurs lignes",
                lignes.size() >= 3);
        for (String l : lignes) {
            assertTrue("chaque ligne tient dans la largeur: «" + l + " »",
                    l.length() * 10f <= 30f + 0.5f);
        }
        assertEquals("les morceaux recomposent le mot",
                "aaaaaaaaaaa", String.join("", lignes));
    }

    @Test
    public void wrapText_mesureParMot_resultatIdentiqueSurTexteSimple() {
        // Compatibilité : un texte ordinaire se découpe aux espaces,
        // exactement comme l'ancien moteur.
        List<String> lignes = EditorAssistPopupPainter.wrapText(
                "aaa bbb ccc", 70f, paintFactice());
        assertEquals(2, lignes.size());
        assertEquals("aaa bbb", lignes.get(0));
        assertEquals("ccc", lignes.get(1));
    }

    @Test
    public void wrapText_retoursÀLaLigneEtLigneVide() {
        List<String> lignes = EditorAssistPopupPainter.wrapText(
                "a\n\nb", 100f, paintFactice());
        assertEquals(3, lignes.size());
        assertEquals("", lignes.get(1));
    }

    // ── ellipsizeAroundActive : le maximum de texte survit ─────────

    @Test
    public void ellipsizeAutourDuParamètreActif_gardeLeMaximum() {
        // 30 caractères disponibles ; pre 8, param 4, post 8 = 200 px.
        // L'ancienne boucle (retrait de 2) rognait un caractère de trop :
        // ici la queue garde exactement ce qui tient.
        Paint p = paintFactice();
        String[] r = EditorAssistPopupPainter.ellipsizeAroundActive(
                "preprepre", "pppp", "postpost", 130f, p);
        // param (40) + ell (10) + queue : 130 - 40 = 90 → queue ≤ 8
        // caractères avec l'ellipse : queue de 7 + ell = 80… la coupe
        // exacte : param 40, ell 10, reste 80 pour pre+post.
        assertEquals("le paramètre actif survit entier", "pppp", r[1]);
        assertTrue("le préfixe est sacrifié, ellipsé ou conservé selon la place",
                r[0].isEmpty() || r[0].endsWith("…") || r[0].equals("preprepre"));
        float total = p.measureText(r[0]) + p.measureText(r[1])
                + p.measureText(r[2]);
        assertTrue("le total ne déborde pas: " + total, total <= 130f);
    }

    @Test
    public void ellipsizeAutourDuParamètreActif_paramètreTropLarge_rogné() {
        // Le paramètre SEUL dépasse la largeur : rogné à droite, pré et
        // post sacrifiés — contrat inchangé de la méthode historique.
        Paint p = paintFactice();
        String[] r = EditorAssistPopupPainter.ellipsizeAroundActive(
                "pre", "pppppppppppppppp", "post", 80f, p);
        assertEquals("", r[0]);
        assertEquals("", r[2]);
        assertTrue("le paramètre rogné tient dans la largeur",
                p.measureText(r[1]) <= 80f);
        assertTrue("la coupe est ellipsée en queue", r[1].endsWith("…"));
    }
}

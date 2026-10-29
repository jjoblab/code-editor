package jo.codeeditor.view.popup.kit;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests PURS du kit de mise en ligne : la mesure est un compteur
 * déterministe (10 px par caractère), aucune dépendance Android —
 * le comportement du wrap et de l'ellipse se vérifie exactement.
 *
 * <p>Deux correctifs historiques sont verrouillés ici : (1) un mot
 * plus large que la ligne est désormais COUPÉ (l'ancien moteur le
 * laissait déborder) ; (2) l'ellipse garde le maximum de texte (le
 * retrait de 2 caractères par itération perdait un caractère utile).</p>
 */
public class TextLayoutKitTest {

    /** Mesure déterministe : 10 px par caractère, l'ellipse pèse 1. */
    private static final TextLayoutKit.Measurer COMPTEUR =
            s -> s.length() * 10f;

    private static List<String> wrap(String texte, int largeurCar) {
        return TextLayoutKit.wrap(texte, largeurCar * 10f, COMPTEUR);
    }

    // ── Wrap : cas de base ─────────────────────────────────────────

    @Test
    public void texteVide_listeVide() {
        assertTrue(TextLayoutKit.wrap(null, 100f, COMPTEUR).isEmpty());
        assertTrue(TextLayoutKit.wrap("", 100f, COMPTEUR).isEmpty());
    }

    @Test
    public void texteTenant_surUneSeuleLigne() {
        List<String> l = wrap("mot court", 100);
        assertEquals(1, l.size());
        assertEquals("mot court", l.get(0));
    }

    @Test
    public void retoursÀLaLigne_respectés() {
        List<String> l = wrap("a\nb", 100);
        assertEquals(2, l.size());
        assertEquals("a", l.get(0));
        assertEquals("b", l.get(1));
    }

    @Test
    public void paragrapheVide_ligneDeRespiration() {
        // Double \n : le paragraphe vide du MILIEU garde sa ligne vide.
        List<String> l = wrap("a\n\nb", 100);
        assertEquals(3, l.size());
        assertEquals("", l.get(1));
    }

    @Test
    public void paragrapheVideDeQueue_ignoré() {
        // Un \n final ne crée PAS de ligne vide de queue (motif de
        // l'ancien split).
        List<String> l = wrap("a\n", 100);
        assertEquals(1, l.size());
        assertEquals("a", l.get(0));
    }

    @Test
    public void wrapClassique_coupéAuxEspaces() {
        List<String> l = wrap("aaa bbb ccc", 7);   // 7 caractères max
        assertEquals("aaa bbb", l.get(0));
        assertEquals("ccc", l.get(1));
        assertEquals(2, l.size());
    }

    @Test
    public void espacesMultiples_neMigrentPasEnTêteDeLigne() {
        // Les espaces superflus ne doivent pas se retrouver en tête
        // d'une ligne coupée, ni doubler le séparateur conservé.
        List<String> l = wrap("aaa     bbb   ccc", 5);
        assertEquals("aaa", l.get(0));
        for (String ligne : l) {
            assertTrue("aucune ligne ne commence par une espace: «"
                    + ligne + " »", ligne.isEmpty() || ligne.charAt(0) != ' ');
        }
        assertTrue("le contenu complet survit au wrap",
                String.join(" ", l).replace("  ", " ").contains("ccc"));
    }

    // ── Wrap : le mot long est coupé (correctif 1) ─────────────────

    @Test
    public void motPlusLargeQueLaLigne_coupéParCaractères() {
        // AVANT : le mot de 11 caractères ressortait ENTIER sur une
        // ligne de 3 caractères (le moteur ne coupait pas un mot trop
        // long) — débordement du panneau. APRÈS : coupé en morceaux
        // tenant dans la largeur.
        List<String> l = wrap("aaaaaaaaaaa", 3);
        assertTrue("le mot long est coupé en plusieurs lignes",
                l.size() >= 3);
        for (String ligne : l) {
            assertTrue("chaque morceau tient dans la largeur: «"
                    + ligne + " »", ligne.length() <= 3);
        }
        assertEquals("les morceaux recomposent le mot",
                "aaaaaaaaaaa", String.join("", l));
    }

    @Test
    public void motLong_aprèsUnMotCourt_laLignePrecedentePart() {
        List<String> l = wrap("ab aaaaaaaa", 3);
        assertEquals("ab", l.get(0));
        assertEquals("les morceaux du mot long suivent", 4, l.size());
        assertEquals("aaaaaaaa", String.join("", l.subList(1, 4)));
    }

    @Test
    public void motSuivant_rejointLaQueueDuMotLong() {
        // La queue du mot long ouvre une ligne : le mot suivant peut
        // la compléter (accumulation gloutonne honnête) — ici la queue
        // « aa » accueille « bb » sur la même ligne.
        List<String> l = wrap("aaaaaaa bb", 5);
        assertEquals("aaaaa", l.get(0));
        assertEquals(2, l.size());
        assertEquals("aa bb", l.get(1));
    }

    // ── Ellipse : les trois modes ──────────────────────────────────

    @Test
    public void texteTenantDéjà_resteTelQuel() {
        assertEquals("abc",
                TextLayoutKit.ellipsize("abc", 100f,
                        TextLayoutKit.Truncate.TAIL, COMPTEUR));
        assertEquals("exactement la largeur",
                "abc",
                TextLayoutKit.ellipsize("abc", 30f,
                        TextLayoutKit.Truncate.TAIL, COMPTEUR));
    }

    @Test
    public void queue_ellipseEnFin() {
        String s = TextLayoutKit.ellipsize("abcdefgh", 60f,
                TextLayoutKit.Truncate.TAIL, COMPTEUR);
        // 8 caractères = 80 px > 60 px : 5 caractères + ellipse = 60.
        assertEquals("abcde…", s);
    }

    @Test
    public void tête_ellipseEnDébut() {
        String s = TextLayoutKit.ellipsize("abcdefgh", 60f,
                TextLayoutKit.Truncate.HEAD, COMPTEUR);
        assertEquals("…defgh", s);
    }

    @Test
    public void milieu_ellipseÉquilibrée() {
        String s = TextLayoutKit.ellipsize("abcdefgh", 60f,
                TextLayoutKit.Truncate.MIDDLE, COMPTEUR);
        // 5 caractères conservés + ellipse : tête de 3, queue de 2.
        assertEquals("abc…gh", s);
    }

    @Test
    public void queue_gardeLeMaximumDeTexte() {
        // Correctif 2 : l'ancienne boucle à retrait de DEUX caractères
        // coupait ici un caractère de trop (75 px → 73 → 63 : « abcde… »
        // alors que « abcdef… » tient exactement à 70 px).
        String s = TextLayoutKit.ellipsize("abcdefgh", 75f,
                TextLayoutKit.Truncate.TAIL, COMPTEUR);
        assertEquals("le maximum de texte survit", "abcdef…", s);
    }

    @Test
    public void largeurNulle_chaîneVide() {
        for (TextLayoutKit.Truncate mode : TextLayoutKit.Truncate.values()) {
            assertEquals("mode " + mode, "",
                    TextLayoutKit.ellipsize("abcdefgh", 0f, mode, COMPTEUR));
        }
    }

    @Test
    public void largeurInferieureÀLEllipse_chaîneVide() {
        // L'ellipse pèse 10 px : 8 px ne logent même pas «…».
        for (TextLayoutKit.Truncate mode : TextLayoutKit.Truncate.values()) {
            assertEquals("mode " + mode, "",
                    TextLayoutKit.ellipsize("abcdefgh", 8f, mode, COMPTEUR));
        }
    }

    @Test
    public void texteNull_chaîneVide() {
        assertEquals("",
                TextLayoutKit.ellipsize(null, 100f,
                        TextLayoutKit.Truncate.TAIL, COMPTEUR));
    }

    @Test
    public void texteVide_resteVide() {
        assertEquals("",
                TextLayoutKit.ellipsize("", 100f,
                        TextLayoutKit.Truncate.TAIL, COMPTEUR));
    }

    // ── Wrap : propriété générale ──────────────────────────────────

    @Test
    public void aucuneLigneDéborde_surUnCorpusVarié() {
        String[] corpus = {
                "Ajoute un élément à la fin de cette liste, la capacité "
                        + "interne est doublée si nécessaire.",
                "un\ttrèslongmotquiaaaaaaaaaaaaaaaaaaaaaaaaaaa "
                        + "puis la suite normale du texte",
                "  espaces de tête et de queue  ",
        };
        for (String texte : corpus) {
            for (int largeurCar : new int[]{1, 2, 3, 5, 8}) {
                List<String> l = new ArrayList<>(
                        wrap(texte.trim(), largeurCar));
                assertFalseVide(l);
                for (String ligne : l) {
                    assertTrue(texte + " / largeur " + largeurCar
                            + " : ligne «" + ligne + " » déborde",
                            ligne.length() <= largeurCar);
                }
            }
        }
    }

    private static void assertFalseVide(List<String> l) {
        assertTrue("un texte non vide produit au moins une ligne",
                !l.isEmpty());
    }
}

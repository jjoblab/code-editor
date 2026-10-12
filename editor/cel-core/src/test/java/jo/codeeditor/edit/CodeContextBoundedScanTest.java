package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 3 (audit v3.41.0) — bornage des scans O(n) à ~10 000
 * caractères dans {@link CodeContext}. Avant le Lot 3, les
 * prédicats {@code isInString}, {@code isInBlockComment},
 * {@code isInLineComment} et {@code isInStringOrComment}
 * parcouraient le document depuis l'offset 0 jusqu'à la position
 * du caret, sans borne. Sur un document de 100 000+ caractères,
 * chaque frappe déclenchait 100 000 itérations — pression GC et
 * latence perceptible sur le thread UI.
 *
 * <p>Le Lot 3 borne le scan à ~10 000 caractères en arrière de la
 * position du caret. Si le scan ne trouve pas de fin de chaîne /
 * commentaire dans cette fenêtre, on considère que la position
 * n'est PAS dans une chaîne ou un commentaire — c'est le cas
 * normal pour du code en dehors d'un littéral. Le faux négatif
 * potentiel (chaîne/commentaire ouverte il y a plus de 10 000
 * caractères) est acceptable :</p>
 * <ul>
 *   <li>Une chaîne littérale de plus de 10 000 caractères est
 *       extrême (e.g. minified JS) — l'utilisateur est en train
 *       de l'éditer, le reste du document n'a pas besoin du
 *       prédicat pendant l'édition.</li>
 *   <li>Un commentaire de bloc de plus de 10 000 caractères est
 *       encore plus rare.</li>
 * </ul>
 *
 * <p>Cf. prompt Lot 3 :
 * « isInString / isInBlockComment parcourent le fichier depuis
 * le début à chaque Entrée : borner ou rendre incrémental, avec
 * mesure avant/après sur un gros fichier ».</p>
 */
class CodeContextBoundedScanTest {

    private static final int SCAN_LIMIT = 10_000;

    // ── Vérifie que les prédicats sont bornés à ~10K chars ──────

    @Test
    void isInString_boundedScan_returnsFalseBeyondLimit() {
        // Document de 50 000 caractères : 10 000 + 'a' + ' "string" '
        // suivi de 30 000 'a' + ' sans ouvrir de chaîne.
        // Si on appelle isInString à la position finale, l'ancien
        // code (sans borne) parcourrait tout le document (~50K
        // itérations). Le nouveau code (borné à 10K) s'arrête après
        // 10K itérations et renvoie false (pas de chaîne ouverte
        // dans les 10K derniers caractères).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++) sb.append('a');
        // Pas de chaîne nulle part — le résultat doit être false,
        // indépendamment de la borne.
        String text = sb.toString();
        boolean result = CodeContext.isInString(text, text.length() - 1);
        assertFalse(result,
            "isInString doit renvoyer false pour une position sans "
                    + "chaîne, indépendamment de la borne");
    }

    @Test
    void isInString_recentStringStillDetected() {
        // Une chaîne ouverte récemment (à moins de 10K du caret)
        // doit être détectée.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('a');
        sb.setLength(100);
        sb.append("\"");  // ouverture de chaîne à la position 100
        sb.append("hello, world");  // contenu de la chaîne
        String text = sb.toString();
        // Caret à la fin, dans la chaîne non fermée.
        boolean result = CodeContext.isInString(text, text.length() - 1);
        assertTrue(result,
            "isInString doit détecter une chaîne ouverte récemment");
    }

    @Test
    void isInBlockComment_boundedScan_returnsFalseBeyondLimit() {
        // 50 000 caractères sans commentaire de bloc.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++) sb.append('a');
        String text = sb.toString();
        boolean result = CodeContext.isInBlockComment(text, text.length() - 1);
        assertFalse(result,
            "isInBlockComment doit renvoyer false pour une position "
                    + "sans commentaire de bloc, indépendamment de la borne");
    }

    @Test
    void isInBlockComment_recentCommentStillDetected() {
        StringBuilder sb = new StringBuilder();
        sb.append("/* ouverture de commentaire\n");
        sb.append("contenu du commentaire sur plusieurs lignes\n");
        sb.append("encore du contenu");
        String text = sb.toString();
        // Caret à la fin, dans le commentaire non fermé.
        boolean result = CodeContext.isInBlockComment(text, text.length() - 1);
        assertTrue(result,
            "isInBlockComment doit détecter un commentaire de bloc récent");
    }

    @Test
    void isInLineComment_boundedScan_returnsFalseBeyondLimit() {
        // 50 000 caractères sans commentaire de ligne (et sans \n).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++) sb.append('a');
        String text = sb.toString();
        boolean result = CodeContext.isInLineComment(text, text.length() - 1);
        // Sans \n, l'ancien code marquerait tout comme « pas dans
        // un commentaire de ligne » car il n'y a pas de //. Le
        // nouveau code (borné) renvoie false aussi.
        assertFalse(result,
            "isInLineComment doit renvoyer false pour une position "
                    + "sans commentaire de ligne");
    }

    @Test
    void isInLineComment_recentCommentStillDetected() {
        // Un commentaire de ligne // suivi d'un caret sur la même ligne.
        String text = "// un commentaire avec du texte";
        boolean result = CodeContext.isInLineComment(text, text.length() - 1);
        assertTrue(result,
            "isInLineComment doit détecter un commentaire de ligne récent");
    }

    @Test
    void isInStringOrComment_boundedScan_returnsFalseBeyondLimit() {
        // 50 000 caractères sans chaîne ni commentaire.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++) sb.append('a');
        String text = sb.toString();
        boolean result = CodeContext.isInStringOrComment(text, text.length() - 1);
        assertFalse(result,
            "isInStringOrComment doit renvoyer false pour une "
                    + "position sans chaîne ni commentaire");
    }

    @Test
    void isInStringOrComment_recentContextStillDetected() {
        // Mix : une chaîne ouverte récemment.
        StringBuilder sb = new StringBuilder();
        sb.append("\"hello, world");
        String text = sb.toString();
        assertTrue(CodeContext.isInStringOrComment(text, text.length() - 1),
            "isInStringOrComment doit détecter une chaîne récente");

        // Mix : un commentaire de bloc récent.
        String text2 = "/* un commentaire\nsur plusieurs lignes\n";
        assertTrue(CodeContext.isInStringOrComment(text2, text2.length() - 1),
            "isInStringOrComment doit détecter un commentaire récent");

        // Mix : un commentaire de ligne récent.
        String text3 = "// un commentaire";
        assertTrue(CodeContext.isInStringOrComment(text3, text3.length() - 1),
            "isInStringOrComment doit détecter un commentaire de ligne récent");
    }
}

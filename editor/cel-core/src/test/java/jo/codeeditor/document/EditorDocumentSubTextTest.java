package jo.codeeditor.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B19 — lectures ciblées du document sans matérialisation complète :
 * {@code subText(start, end)} (tranche via la rope) et
 * {@code charSequence()} (vue vivante, aucune copie). Les chemins de
 * frappe intelligente, d'appariement de crochets et de complétion lisent
 * quelques caractères autour du curseur — l'ancienne voie getText()
 * recopiait tout le fichier à chaque frappe (cache invalidé par édition).
 */
public class EditorDocumentSubTextTest {

    private static final String CONTENT =
            "public class Main {\n"
            + "    void run() {\n"
            + "        greet(name);\n"
            + "    }\n"
            + "}\n";

    @Test
    void subText_matchesGetTextSubstring() {
        EditorDocument doc = EditorDocument.of(CONTENT);
        assertEquals(CONTENT.substring(0, 6), doc.subText(0, 6));
        assertEquals(CONTENT.substring(12, 30), doc.subText(12, 30));
        assertEquals("", doc.subText(7, 7));
        // Bornage défensif.
        assertEquals(CONTENT.substring(CONTENT.length() - 2), doc.subText(CONTENT.length() - 2, 9999));
        assertEquals("", doc.subText(50, 3));
    }

    @Test
    void charSequence_readsLiveContent() {
        EditorDocument doc = EditorDocument.of("abc\ndef\n");
        CharSequence view = doc.charSequence();
        assertEquals(8, view.length());
        assertEquals('a', view.charAt(0));
        assertEquals('d', view.charAt(4));
        assertEquals("c\nd", view.subSequence(2, 5).toString());

        // Contrat : la vue reflète le document AU MOMENT DE L'APPEL — une
        // édition substitue la rope sous-jacente (replace retourne une
        // NOUVELLE instance) ; re-récupérer la vue après l'édition lit le
        // nouveau contenu (sans copie complète).
        doc = doc.replace(0, 3, "xyz");
        CharSequence after = doc.charSequence();
        assertEquals("xyz", after.subSequence(0, 3).toString());
        assertEquals(8, after.length());
    }
}

package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 2/3 (audit v3.41.0) — fermeture de balise sur {@code >}
 * pour XML/HTML/SVG. Quand l'utilisateur tape {@code >} après
 * un nom de balise ouvrant non auto-fermant, la balise fermante
 * est insérée automatiquement.
 *
 * <p>Exemple : taper {@code >} après {@code <div} insère
 * {@code ></div>}, plaçant le caret entre {@code >} et
 * {@code </div>}.</p>
 *
 * <p>Périmètre : langages de famille XML (xml, html, htm, svg).
 * Les langages non XML ne sont pas affectés.</p>
 */
class SmartTypingXmlTagAutoCloseTest {

    @Test
    void xml_divTag_autoCloses() {
        String text = "<div";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "xml");
        // Le résultat doit contenir "></div>"
        assertTrue(e.text.contains("></div>"),
                "Taper > après <div doit insérer ></div> — text='" + e.text + "'");
    }

    @Test
    void html_spanTag_autoCloses() {
        String text = "<span";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "html");
        assertTrue(e.text.contains("></span>"),
                "Taper > après <span doit insérer ></span> — text='" + e.text + "'");
    }

    @Test
    void xml_selfClosingTag_doesNotAutoClose() {
        // Si la balise se termine par / (auto-fermante), ne pas
        // insérer la balise fermante.
        String text = "<br /";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "xml");
        // Pas de </br> — la balise est auto-fermante.
        assertFalse(e.text.contains("</br>"),
                "Taper > après <br / ne doit PAS insérer </br> — "
                        + "la balise est auto-fermante");
    }

    @Test
    void xml_closingTag_doesNotAutoClose() {
        // Si c'est une balise fermante (</div>), ne pas insérer
        // une autre fermante.
        String text = "</div";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "xml");
        assertFalse(e.text.contains("</div></div>"),
                "Taper > après </div ne doit PAS insérer une 2e </div> — "
                        + "c'est déjà une balise fermante");
    }

    @Test
    void xml_tagWithAttributes_autoCloses() {
        // Balise avec attributs : <TextView android:text="...">
        String text = "<TextView android:text=\"hello\"";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "xml");
        assertTrue(e.text.contains("></TextView>"),
                "Taper > après <TextView android:text=\"hello\" doit "
                        + "insérer ></TextView> — text='" + e.text + "'");
    }

    @Test
    void java_angleBracket_doesNotAutoClose() {
        // Java générique : List<String> — pas de fermeture de balise.
        String text = "List<String";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "java");
        // Pas de </String> — Java n'est pas XML.
        assertFalse(e.text.contains("</String>"),
                "Taper > après List<String ne doit PAS insérer </String> "
                        + "pour Java — text='" + e.text + "'");
    }

    @Test
    void xml_unknownTag_autoCloses() {
        // N'importe quel nom de balise valide.
        String text = "<foo";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "xml");
        assertTrue(e.text.contains("></foo>"),
                "Taper > après <foo doit insérer ></foo> — text='" + e.text + "'");
    }

    @Test
    void svg_rectTag_autoCloses() {
        String text = "<rect";
        RangeEdit e = EditOps.smartInsert(text, text.length(), text.length(), '>', "svg");
        assertTrue(e.text.contains("></rect>"),
                "Taper > après <rect (SVG) doit insérer ></rect> — text='" + e.text + "'");
    }
}

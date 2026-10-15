package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static jo.codeeditor.highlight.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Caractérisation du tokéniseur XML/HTML (ADR-002, chantier C2 —
 * cliquet de démantèlement) : verrouille le comportement EXACT (bornes
 * et types de chaque span, états inter-lignes BLOCK_COMMENT, XML_CDATA,
 * XML_STRING, XML_TAG) de {@code XmlTokenizer.styleXml} sur une batterie
 * de lignes représentatives, AVANT puis APRÈS la migration sur le moteur
 * générique {@link GenericTokenizer} (quatrième saveur : mode balisage).
 * La migration doit garder cette suite verte sans toucher à ses
 * assertions — c'est le critère « octet pour octet » du chantier C2.
 */
class XmlTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return XmlTokenizer.styleXml(line, LexState.NORMAL).spans;
    }

    // ── Balises ──────────────────────────────────────────────────────

    @Test
    void balise_ouvrante() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 13, TYPE),
                new LineSpan(13, 14, PUNCT)),
                spans("<LinearLayout>"));
    }

    @Test
    void balise_fermante() {
        assertEquals(List.of(
                new LineSpan(0, 2, PUNCT),
                new LineSpan(2, 14, TYPE),
                new LineSpan(14, 15, PUNCT)),
                spans("</LinearLayout>"));
    }

    @Test
    void balise_avecAttributEtFermetureCourte() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 9, TYPE),
                new LineSpan(10, 17, NAMESPACE),
                new LineSpan(17, 18, PUNCT),
                new LineSpan(18, 22, PROPERTY),
                new LineSpan(22, 23, OPERATOR),
                new LineSpan(23, 30, STRING),
                new LineSpan(31, 33, PUNCT)),
                spans("<TextView android:text=\"hello\" />"));
    }

    @Test
    void balise_qualifiéeÉclatée() {
        // ns:tag → NAMESPACE + PUNCT + TYPE (lot 4 #26).
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 3, NAMESPACE),
                new LineSpan(3, 4, PUNCT),
                new LineSpan(4, 7, TYPE),
                new LineSpan(7, 8, PUNCT)),
                spans("<ns:tag>"));
    }

    @Test
    void balise_inachevéePorteLEtat() {
        // <a href="unclosed : la valeur déborde → XML_STRING.
        StyledLine line = XmlTokenizer.styleXml("<a href=\"unclosed", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 2, TYPE),
                new LineSpan(3, 7, PROPERTY),
                new LineSpan(7, 8, OPERATOR),
                new LineSpan(8, 17, STRING)), line.spans);
        assertEquals(LexState.XML_STRING, line.exitState);
    }

    @Test
    void balise_attributsMultiLignes() {
        // Une balise ouverte non fermée porte XML_TAG — la ligne
        // suivante poursuit les attributs.
        StyledLine line = XmlTokenizer.styleXml("<LinearLayout", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 13, TYPE)), line.spans);
        assertEquals(LexState.XML_TAG, line.exitState);
    }

    @Test
    void balise_continuationDesAttributs() {
        // Ligne de continuation d'une balise multi-lignes : les noms
        // d'attributs restent PROPERTY (partagé avec le chemin en
        // ligne — garde historique).
        StyledLine line = XmlTokenizer.styleXml(
                "  android:id=\"@+id/x\"", LexState.XML_TAG);
        assertEquals(List.of(
                new LineSpan(2, 9, NAMESPACE),
                new LineSpan(9, 10, PUNCT),
                new LineSpan(10, 12, PROPERTY),
                new LineSpan(12, 13, OPERATOR),
                new LineSpan(13, 21, STRING)), line.spans);
        assertEquals(LexState.XML_TAG, line.exitState);
    }

    @Test
    void balise_texteAvecChevronSansNom() {
        // « a < b » : le chevron ouvre une balise vide jamais fermée —
        // état XML_TAG propagé (garde historique, pas d'analyse de
        // validité).
        StyledLine line = XmlTokenizer.styleXml("a < b", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 2, PLAIN),
                new LineSpan(2, 3, PUNCT),
                new LineSpan(4, 5, PROPERTY)), line.spans);
        assertEquals(LexState.XML_TAG, line.exitState);
    }

    // ── Instructions de traitement et commentaires ───────────────────

    @Test
    void instruction_deTraitement() {
        assertEquals(List.of(
                new LineSpan(0, 21, ANNOTATION)),
                spans("<?xml version=\"1.0\"?>"));
    }

    @Test
    void commentaire_uneLigne() {
        assertEquals(List.of(
                new LineSpan(0, 16, COMMENT)),
                spans("<!-- comment -->"));
    }

    @Test
    void commentaire_interLignes() {
        StyledLine line = XmlTokenizer.styleXml("<!-- open", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 9, COMMENT)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void commentaire_continuationFermée() {
        StyledLine line = XmlTokenizer.styleXml("still comment --> x",
                LexState.BLOCK_COMMENT);
        assertEquals(List.of(
                new LineSpan(0, 17, COMMENT),
                new LineSpan(18, 19, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── CDATA ────────────────────────────────────────────────────────

    @Test
    void cdata_surUneLigne() {
        // Le contenu n'est PAS re-lexé comme du balisage (état dédié,
        // lot 4 #26).
        assertEquals(List.of(
                new LineSpan(0, 24, STRING)),
                spans("<![CDATA[ raw <text> ]]>"));
    }

    @Test
    void cdata_interLignes() {
        StyledLine line = XmlTokenizer.styleXml("<![CDATA[ open", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 14, STRING)), line.spans);
        assertEquals(LexState.XML_CDATA, line.exitState);
    }

    @Test
    void cdata_continuationFermée() {
        StyledLine line = XmlTokenizer.styleXml("cdata continues ]]> end",
                LexState.XML_CDATA);
        assertEquals(List.of(
                new LineSpan(0, 19, STRING),
                new LineSpan(20, 23, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── Chaîne d'attribut inter-lignes ───────────────────────────────

    @Test
    void chaîne_continuationRetourneDansLaBalise() {
        // La chaîne d'attribut déborde : fermée sur la ligne suivante,
        // on RETOURNE dans la liste d'attributs (XML_TAG), puis la
        // balise se ferme normalement.
        StyledLine line = XmlTokenizer.styleXml("rest\" more>", LexState.XML_STRING);
        assertEquals(List.of(
                new LineSpan(0, 5, STRING),
                new LineSpan(6, 10, PROPERTY),
                new LineSpan(10, 11, PUNCT)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── Entités et texte ─────────────────────────────────────────────

    @Test
    void entité_bienFormée() {
        // &amp; etc. dans une fenêtre de 12 caractères → ENTITY.
        assertEquals(List.of(
                new LineSpan(0, 3, PLAIN),
                new LineSpan(3, 11, ENTITY),
                new LineSpan(12, 17, PLAIN)),
                spans("Caf&eacute; rocks"));
    }

    @Test
    void esperluetteOrpheline_estDuTexte() {
        // Sans « ; » proche, le « & » est du texte brut.
        assertEquals(List.of(
                new LineSpan(0, 2, PLAIN),
                new LineSpan(2, 3, PLAIN),
                new LineSpan(4, 5, PLAIN)),
                spans("a & b"));
    }

    @Test
    void texte_simple() {
        assertEquals(List.of(
                new LineSpan(0, 11, PLAIN)),
                spans("hello world"));
    }
}

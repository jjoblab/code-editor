package jo.codeeditor.highlight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jo.codeeditor.highlight.tokenizer.CLikeTokenizer;
import jo.codeeditor.highlight.tokenizer.MarkdownTokenizer;
import jo.codeeditor.highlight.tokenizer.XmlTokenizer;

/**
 * Distinctions fines de tokens (lot 4 #26) : DOC_COMMENT (état porté),
 * KEYWORD_CONTROL/KEYWORD_MODIFIER (tables partagées), CHAR, RAW_STRING,
 * NAMESPACE + entités XML (fenêtre 12) + état XML_CDATA dédié, EMPHASIS
 * Markdown avec règle intra-mot `_` vs `*`.
 */
public class FineTokenDistinctionsTest {

    private static TokenType typeAt(List<LineSpan> spans, int col) {
        for (LineSpan s : spans) {
            if (col >= s.startCol && col < s.endCol) return s.type;
        }
        return null;
    }

    // ── DOC_COMMENT ──────────────────────────────────────────────

    @Test
    public void docComment_onOneLine_getsDedicatedType() {
        StyledLine l = CLikeTokenizer.styleCLike("/** Bonjour. */ int x;", LexState.NORMAL, "java");
        assertEquals(TokenType.DOC_COMMENT, typeAt(l.spans, 2));
        // « int » : mot-clé Java ni contrôle ni modificateur → KEYWORD.
        assertEquals(TokenType.KEYWORD, typeAt(l.spans, 16));
    }

    @Test
    public void plainBlockComment_staysCOMMENT() {
        StyledLine l = CLikeTokenizer.styleCLike("/* simple */ int x;", LexState.NORMAL, "java");
        assertEquals(TokenType.COMMENT, typeAt(l.spans, 2));
    }

    @Test
    public void docComment_multiline_stateRemembersDocOpening() {
        StyledLine l1 = CLikeTokenizer.styleCLike("/** Première ligne", LexState.NORMAL, "java");
        assertEquals(TokenType.DOC_COMMENT, typeAt(l1.spans, 2));
        assertEquals(LexState.DOC_COMMENT, l1.exitState, "l'état porté doit être DOC_COMMENT");

        StyledLine l2 = CLikeTokenizer.styleCLike(" * suite du javadoc", l1.exitState, "java");
        assertEquals(TokenType.DOC_COMMENT, typeAt(l2.spans, 4));

        StyledLine l3 = CLikeTokenizer.styleCLike(" */ fin", l2.exitState, "java");
        assertEquals(TokenType.DOC_COMMENT, typeAt(l3.spans, 2));
        assertEquals(LexState.NORMAL, l3.exitState);
        // Et une ligne suivante normale n'est PAS un commentaire.
        StyledLine l4 = CLikeTokenizer.styleCLike("int ok;", l3.exitState, "java");
        assertNotEquals(TokenType.DOC_COMMENT, typeAt(l4.spans, 1));
    }

    // ── Contrôle / modificateurs (tables partagées) ──────────────

    @Test
    public void keywords_splitIntoControlAndModifier() {
        // 0        1         2         3         4
        // 1234567890123456789012345678901234567890123456
        // public static final int x = if (a) return b;
        StyledLine l = CLikeTokenizer.styleCLike(
                "public static final int x = if (a) return b;", LexState.NORMAL, "java");
        assertEquals(TokenType.KEYWORD_MODIFIER, typeAt(l.spans, 1));  // public
        assertEquals(TokenType.KEYWORD_MODIFIER, typeAt(l.spans, 8));  // static
        assertEquals(TokenType.KEYWORD_MODIFIER, typeAt(l.spans, 15)); // final
        assertEquals(TokenType.KEYWORD, typeAt(l.spans, 21));          // int
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l.spans, 28));  // if
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l.spans, 35));  // return
    }

    @Test
    public void contributedLanguage_getsSplitForFree() {
        // Tout langage contribué qui déclare déjà ces mots comme
        // mots-clés obtient la distinction sans table propre.
        // 012345678901234567890123456789
        // fn main() { if x { return; } }
        StyledLine l = CLikeTokenizer.styleCLike(
                "fn main() { if x { return; } }", LexState.NORMAL, "rust");
        assertEquals(TokenType.KEYWORD, typeAt(l.spans, 1));           // fn
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l.spans, 12));  // if
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l.spans, 19));  // return
    }

    // ── CHAR / RAW_STRING ────────────────────────────────────────

    @Test
    public void charLiteral_getsDedicatedType() {
        StyledLine l = CLikeTokenizer.styleCLike("char c = 'a';", LexState.NORMAL, "java");
        assertEquals(TokenType.CHAR, typeAt(l.spans, 10));
        assertEquals(TokenType.CHAR, typeAt(l.spans, 11));
    }

    @Test
    public void kotlinRawString_getsDedicatedType_multiline() {
        StyledLine l1 = CLikeTokenizer.styleCLike("val s = \"\"\"bonjour", LexState.NORMAL, "kotlin");
        assertEquals(TokenType.RAW_STRING, typeAt(l1.spans, 12));
        assertEquals(LexState.KT_RAW_STRING, l1.exitState);

        StyledLine l2 = CLikeTokenizer.styleCLike("suite\"\"\" ;", l1.exitState, "kotlin");
        assertEquals(TokenType.RAW_STRING, typeAt(l2.spans, 2));
        assertEquals(LexState.NORMAL, l2.exitState);
    }

    // ── XML : namespace, entités, CDATA ──────────────────────────

    @Test
    public void xml_qualifiedTagName_splitIntoNamespaceAndLocal() {
        StyledLine l = XmlTokenizer.styleXml("<android:card>", LexState.NORMAL);
        assertEquals(TokenType.PUNCT, typeAt(l.spans, 0));       // <
        assertEquals(TokenType.NAMESPACE, typeAt(l.spans, 2));   // android
        assertEquals(TokenType.PUNCT, typeAt(l.spans, 8));       // :
        assertEquals(TokenType.TYPE, typeAt(l.spans, 10));       // card
    }

    @Test
    public void xml_qualifiedAttribute_splitIntoNamespaceAndLocal() {
        StyledLine l = XmlTokenizer.styleXml("<view android:text=\"a\"/>", LexState.NORMAL);
        // « android » → NAMESPACE, « : » → PUNCT, « text » → PROPERTY.
        assertEquals(TokenType.NAMESPACE, typeAt(l.spans, 7));
        assertEquals(TokenType.PUNCT, typeAt(l.spans, 13));
        assertEquals(TokenType.PROPERTY, typeAt(l.spans, 16));
    }

    @Test
    public void xml_unqualifiedName_unchanged() {
        StyledLine l = XmlTokenizer.styleXml("<card>", LexState.NORMAL);
        assertEquals(TokenType.TYPE, typeAt(l.spans, 2));
    }

    @Test
    public void xml_entities_ownType_window12() {
        //            0         1         2         3
        //            0123456789012345678901234567890123456789
        String line = "<p>a &amp; b &#233; c &trèsLongEntité; fin</p>";
        StyledLine l = XmlTokenizer.styleXml(line, LexState.NORMAL);
        assertEquals(TokenType.ENTITY, typeAt(l.spans, 5));   // &amp;  (4 < 12)
        assertEquals(TokenType.ENTITY, typeAt(l.spans, 13));  // &#233; (5 < 12)
        // L'entité longue (« ; » à 14 caractères du « & ») ne doit PAS
        // être reconnue : texte brut.
        assertEquals(TokenType.PLAIN, typeAt(l.spans, 22));
    }

    @Test
    public void xml_cdata_dedicatedLexState() {
        StyledLine l1 = XmlTokenizer.styleXml("<x><![CDATA[données", LexState.NORMAL);
        assertEquals(LexState.XML_CDATA, l1.exitState, "l'état porté doit être XML_CDATA dédié");

        StyledLine l2 = XmlTokenizer.styleXml("toujours du cdata ]]> <y>", l1.exitState);
        // Le contenu de la 2e ligne doit rester une chaîne (pas re-lexé
        // en balisage — le « <y> » suivant, lui, est re-lexé).
        assertEquals(TokenType.STRING, typeAt(l2.spans, 5));
        assertEquals(TokenType.TYPE, typeAt(l2.spans, 23));
        assertEquals(LexState.NORMAL, l2.exitState);
    }

    // ── Emphase Markdown ─────────────────────────────────────────

    @Test
    public void markdown_boldAndItalic_getEmphasis() {
        StyledLine l = MarkdownTokenizer.styleMarkdown("du **gras** et de *l'italique*", LexState.NORMAL);
        assertEquals(TokenType.EMPHASIS, typeAt(l.spans, 4));   // **gras**
        assertEquals(TokenType.EMPHASIS, typeAt(l.spans, 20));  // *l'italique*
    }

    @Test
    public void markdown_underscoreIntraWord_isPlain() {
        StyledLine l = MarkdownTokenizer.styleMarkdown("un snake_case simple", LexState.NORMAL);
        assertEquals(TokenType.PLAIN, typeAt(l.spans, 8),
                "le « _ » intra-mot n'ouvre pas d'emphase");
    }

    @Test
    public void markdown_underscoreWordBoundary_isEmphasis() {
        StyledLine l = MarkdownTokenizer.styleMarkdown("un _terme_ isolé", LexState.NORMAL);
        assertEquals(TokenType.EMPHASIS, typeAt(l.spans, 4));
    }

    @Test
    public void markdown_starIntraWord_isEmphasis() {
        // À l'inverse du « _ », le « * » PEUT ouvrir à l'intérieur d'un mot.
        StyledLine l = MarkdownTokenizer.styleMarkdown("a*b*c", LexState.NORMAL);
        assertEquals(TokenType.EMPHASIS, typeAt(l.spans, 2));
    }
}

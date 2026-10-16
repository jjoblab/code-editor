package jo.codeeditor.textmate;

import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-002 (C3/C4) — le mapping portées de grammaires → TokenType :
 * la correspondance du segment le plus précis connu prime
 * (comment.block.documentation avant comment).
 */
class ScopeMapperTest {

    @Test
    void commentaire_documentationAvantBloc() {
        assertEquals(TokenType.DOC_COMMENT,
                ScopeMapper.tokenTypeFor("comment.block.documentation.csharp"));
        assertEquals(TokenType.COMMENT, ScopeMapper.tokenTypeFor("comment.block.csharp"));
        assertEquals(TokenType.COMMENT, ScopeMapper.tokenTypeFor("comment.line.double-slash"));
    }

    @Test
    void chaînes_finesse() {
        assertEquals(TokenType.STRING, ScopeMapper.tokenTypeFor("string.quoted.double.csharp"));
        assertEquals(TokenType.CHAR, ScopeMapper.tokenTypeFor("string.quoted.single"));
        assertEquals(TokenType.RAW_STRING, ScopeMapper.tokenTypeFor("string.quoted.triple.python"));
        assertEquals(TokenType.RAW_STRING,
                ScopeMapper.tokenTypeFor("string.quoted.double.verbatim.csharp"));
        assertEquals(TokenType.ESCAPE, ScopeMapper.tokenTypeFor("constant.character.escape"));
        assertEquals(TokenType.REGEXP, ScopeMapper.tokenTypeFor("string.regexp"));
    }

    @Test
    void motsClés_contrôleEtModificateur() {
        assertEquals(TokenType.KEYWORD_CONTROL, ScopeMapper.tokenTypeFor("keyword.control.flow"));
        assertEquals(TokenType.KEYWORD_MODIFIER,
                ScopeMapper.tokenTypeFor("storage.modifier.csharp"));
        assertEquals(TokenType.KEYWORD, ScopeMapper.tokenTypeFor("keyword.other.using"));
        assertEquals(TokenType.OPERATOR, ScopeMapper.tokenTypeFor("keyword.operator.assignment"));
    }

    @Test
    void entitésEtVariables() {
        assertEquals(TokenType.FUNC, ScopeMapper.tokenTypeFor("entity.name.function"));
        assertEquals(TokenType.TYPE, ScopeMapper.tokenTypeFor("entity.name.type.class"));
        assertEquals(TokenType.NAMESPACE, ScopeMapper.tokenTypeFor("entity.name.namespace"));
        assertEquals(TokenType.PROPERTY, ScopeMapper.tokenTypeFor("entity.other.attribute-name"));
        assertEquals(TokenType.VARIABLE, ScopeMapper.tokenTypeFor("variable.parameter"));
    }

    @Test
    void constantesEtSupport() {
        assertEquals(TokenType.NUMBER, ScopeMapper.tokenTypeFor("constant.numeric.hex"));
        assertEquals(TokenType.CONSTANT, ScopeMapper.tokenTypeFor("constant.language.boolean"));
        assertEquals(TokenType.FUNC, ScopeMapper.tokenTypeFor("support.function.builtin"));
        assertEquals(TokenType.TYPE, ScopeMapper.tokenTypeFor("support.type"));
    }

    // ── C4 : complétion des catégories standard de l'écosystème ────

    @Test
    void interpolations_convergenceAvecLaVoieIntégrée() {
        // La voie intégrée colore les ${…} des template literals JS/TS
        // en VARIABLE (addTemplateLiteralSpans) — la voie grammaires
        // converge : les portées d'interpolation standard aussi.
        assertEquals(TokenType.VARIABLE, ScopeMapper.tokenTypeFor("meta.interpolation"));
        assertEquals(TokenType.VARIABLE, ScopeMapper.tokenTypeFor("meta.template.expression"));
        assertEquals(TokenType.VARIABLE,
                ScopeMapper.tokenTypeFor("punctuation.definition.template-expression.begin"));
    }

    @Test
    void propriétésDObjet_convergenceAvecLaVoieIntégrée() {
        // La voie intégrée C colore obj.champ en PROPERTY (identifiant
        // après point) — les portées standard des grammaires idem.
        assertEquals(TokenType.PROPERTY,
                ScopeMapper.tokenTypeFor("variable.other.object.property.python"));
        assertEquals(TokenType.PROPERTY, ScopeMapper.tokenTypeFor("variable.other.property"));
        // … mais une variable « simple » reste VARIABLE.
        assertEquals(TokenType.VARIABLE, ScopeMapper.tokenTypeFor("variable.other.object"));
    }

    @Test
    void délimiteurs_colorésCommeLeurContenu() {
        // Les // /* " ' de définition ne tombent plus en PUNCT gris.
        assertEquals(TokenType.COMMENT,
                ScopeMapper.tokenTypeFor("punctuation.definition.comment.line"));
        assertEquals(TokenType.STRING,
                ScopeMapper.tokenTypeFor("punctuation.definition.string.begin"));
    }

    @Test
    void invalides_illégalVersusDéprécié() {
        assertEquals(TokenType.ERROR, ScopeMapper.tokenTypeFor("invalid.illegal"));
        assertEquals(TokenType.WARNING, ScopeMapper.tokenTypeFor("invalid.deprecated"));
    }

    @Test
    void markup_convergenceAvecLaVoieIntégréeMarkdown() {
        // La voie intégrée Markdown : titres → KEYWORD, emphases →
        // EMPHASIS, code inline → STRING, liens → FUNC. Les portées
        // markup.* des grammaires Markdown convergent.
        assertEquals(TokenType.KEYWORD, ScopeMapper.tokenTypeFor("markup.heading.marker"));
        assertEquals(TokenType.EMPHASIS, ScopeMapper.tokenTypeFor("markup.bold"));
        assertEquals(TokenType.EMPHASIS, ScopeMapper.tokenTypeFor("markup.italic"));
        assertEquals(TokenType.STRING, ScopeMapper.tokenTypeFor("markup.inline.raw.string"));
        assertEquals(TokenType.FUNC, ScopeMapper.tokenTypeFor("markup.underline.link"));
        assertEquals(TokenType.FUNC, ScopeMapper.tokenTypeFor("string.other.link"));
    }

    @Test
    void support_modules() {
        assertEquals(TokenType.TYPE, ScopeMapper.tokenTypeFor("support.module.node"));
    }

    @Test
    void inconnusEtVides() {
        assertEquals(TokenType.PLAIN, ScopeMapper.tokenTypeFor("source.unknown.segment"));
        assertEquals(TokenType.PLAIN, ScopeMapper.tokenTypeFor(null));
        assertEquals(TokenType.PLAIN, ScopeMapper.tokenTypeFor("  "));
    }

    // ── C3-sexties : listes de portées (convention TextMate) ──────

    @Test
    void listesDePortées_laPlusSpécifiqueGagne() {
        // Une règle TextMate peut porter PLUSIEURS portées séparées
        // par des espaces (« meta.x.css support.type.x.css ») — l'externe
        // d'abord, l'interne en dernier. Sans éclatement, la chaîne
        // ENTIÈRE était cherchée dans la table : aucune correspondance,
        // le jeton tombait PLAIN (noms de propriétés CSS, définitions
        // de variables JS, 125 portées python…). L'interne (la plus
        // précise) l'emporte sur l'externe.
        assertEquals(TokenType.TYPE,
                ScopeMapper.tokenTypeFor("meta.property-name.css support.type.property-name.css"));
        assertEquals(TokenType.TYPE,
                ScopeMapper.tokenTypeFor(
                        "meta.property-name.css support.type.vendored.property-name.css"));
        assertEquals(TokenType.VARIABLE,
                ScopeMapper.tokenTypeFor(
                        "meta.definition.variable.js variable.other.constant.js"));
        // L'interne inconnue cède la place à l'externe connue.
        assertEquals(TokenType.STRING,
                ScopeMapper.tokenTypeFor("string.quoted.double.js support.unknown.js"));
        // Liste entièrement inconnue → PLAIN.
        assertEquals(TokenType.PLAIN,
                ScopeMapper.tokenTypeFor("meta.unknown.a source.unknown.b"));
    }
}

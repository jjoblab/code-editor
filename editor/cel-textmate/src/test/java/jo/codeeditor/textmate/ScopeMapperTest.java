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

    @Test
    void inconnusEtVides() {
        assertEquals(TokenType.PLAIN, ScopeMapper.tokenTypeFor("source.unknown.segment"));
        assertEquals(TokenType.PLAIN, ScopeMapper.tokenTypeFor(null));
        assertEquals(TokenType.PLAIN, ScopeMapper.tokenTypeFor("  "));
    }
}

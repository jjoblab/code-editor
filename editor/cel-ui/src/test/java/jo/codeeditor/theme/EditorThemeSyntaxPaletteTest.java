package jo.codeeditor.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Garde du schéma de portées C4 (ADR-002 — « un thème colore un
 * langage nouveau sans clé par langage ; les deux voies convergent
 * vers le même schéma ») côté THÈME : la palette syntaxique est
 * complète (23 entrées {@code syntax.*}), chaque {@link TokenType}
 * — y compris les types fins DOC_COMMENT, KEYWORD_CONTROL, RAW_STRING…
 * produits par les DEUX voies de tokénisation — reçoit une couleur sur
 * les neuf thèmes prédéfinis, et les clés virtuelles héritent de leur
 * parent tant qu'un scheme ne les sépare pas.
 */
public class EditorThemeSyntaxPaletteTest {

    private static final List<EditorTheme> THÈMES = List.of(
            EditorTheme.dark(), EditorTheme.light(), EditorTheme.dracula(),
            EditorTheme.oneDark(), EditorTheme.monokai(), EditorTheme.solarizedDark(),
            EditorTheme.gitHubLight(), EditorTheme.gitHubDark(), EditorTheme.nord());

    // ── Palette : 23 entrées syntax.*, aucune clé par langage ─────

    @Test
    public void palette_23EntréesSyntaxSansCléParLangage() {
        List<ColorAttribute> syntax = ColorAttributes.byGroup(ColorAttribute.GROUP_SYNTAX);
        assertEquals("palette C4 : 15 champs dédiés + 8 clés virtuelles",
                23, syntax.size());
        for (ColorAttribute a : syntax) {
            assertTrue("clé de palette syntaxique : " + a.key, a.key.startsWith("syntax."));
        }
        // Critère C4 : AUCUNE clé par langage — un langage grammaire-only
        // (csharp aujourd'hui, demain n'importe quel .tmg.json) se
        // colore avec les catégories standard, jamais avec une clé
        // dédiée. Les noms de langages n'apparaissent nulle part.
        for (ColorAttribute a : syntax) {
            String k = a.key.toLowerCase();
            for (String langue : LANGAGES_CONNUS) {
                assertTrue("la palette ne doit PAS contenir de clé par langage : " + a.key,
                        !k.contains("." + langue) && !k.endsWith(langue));
            }
        }
    }

    private static final String[] LANGAGES_CONNUS = {
            "java", "kotlin", "csharp", "python", "javascript", "typescript",
            "ruby", "rust", "go", "php", "swift", "dart", "groovy", "shell",
            "yaml", "toml", "json", "xml", "css", "lua", "sql", "markdown"
    };

    // ── Chaque type fine reçoit sa couleur sur les neuf thèmes ────

    @Test
    public void tousLesTokenTypes_colorésSurLesNeufThèmes() {
        for (EditorTheme t : THÈMES) {
            for (TokenType type : TokenType.values()) {
                // Aucune exception, aucune valeur magique : chaque type
                // — y compris les types fins des deux voies — passe
                // par colorForToken (source unique de vérité).
                t.colorForToken(type);
            }
            // Héritage par défaut (lot 4 #26) : les types fins
            // retombent sur le champ parent tant qu'un scheme ne les
            // sépare pas — comportement visuel inchangé.
            assertEquals(t.comment, t.colorForToken(TokenType.DOC_COMMENT));
            assertEquals(t.keyword, t.colorForToken(TokenType.KEYWORD_CONTROL));
            assertEquals(t.keyword, t.colorForToken(TokenType.KEYWORD_MODIFIER));
            assertEquals(t.string, t.colorForToken(TokenType.CHAR));
            assertEquals(t.string, t.colorForToken(TokenType.RAW_STRING));
            assertEquals(t.type, t.colorForToken(TokenType.NAMESPACE));
            assertEquals(t.escape, t.colorForToken(TokenType.ENTITY));
            assertEquals(t.annotation, t.colorForToken(TokenType.EMPHASIS));
        }
    }

    // ── Un scheme sépare les clés virtuelles quand il veut ────────

    @Test
    public void clésVirtuelles_séparablesParScheme() {
        EditorTheme base = EditorTheme.dark();
        int docColor = 0xFF608B4E;
        EditorTheme dérivé = EditorTheme.from(base)
                .set("syntax.docComment", docColor)
                .set("syntax.keywordControl", 0xFFC586C0)
                .build();
        assertEquals(docColor, dérivé.colorForToken(TokenType.DOC_COMMENT));
        assertEquals(0xFFC586C0, dérivé.colorForToken(TokenType.KEYWORD_CONTROL));
        // Les autres clés virtuelles héritent toujours du parent.
        assertEquals(base.string, dérivé.colorForToken(TokenType.CHAR));
        assertEquals(base.type, dérivé.colorForToken(TokenType.NAMESPACE));
        // Et le thème de base reste intact (immuabilité).
        assertEquals(base.comment, base.colorForToken(TokenType.DOC_COMMENT));
    }
}

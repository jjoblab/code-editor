package jo.codeeditor.highlight;

import jo.codeeditor.highlight.tokenizer.CLikeTokenizer;
import jo.codeeditor.highlight.tokenizer.CssTokenizer;
import jo.codeeditor.highlight.tokenizer.JsonTokenizer;
import jo.codeeditor.highlight.tokenizer.LogTokenizer;
import jo.codeeditor.highlight.tokenizer.LuaTokenizer;
import jo.codeeditor.highlight.tokenizer.MarkdownTokenizer;
import jo.codeeditor.highlight.tokenizer.PropertiesTokenizer;
import jo.codeeditor.highlight.tokenizer.PythonTokenizer;
import jo.codeeditor.highlight.tokenizer.ShellTokenizer;
import jo.codeeditor.highlight.tokenizer.SmaliTokenizer;
import jo.codeeditor.highlight.tokenizer.SqlTokenizer;
import jo.codeeditor.highlight.tokenizer.TomlTokenizer;
import jo.codeeditor.highlight.tokenizer.XmlTokenizer;
import jo.codeeditor.highlight.tokenizer.YamlTokenizer;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

/**
 * Tokéniseur syntaxique incrémental par ligne (parser maison).
 * <p>
 * Effectue une analyse en une passe par ligne, produisant des LineSpan pour
 * chaque jeton. Gère des états inter-lignes pour les commentaires de bloc,
 * les chaînes XML, les chaînes brutes Kotlin, les commentaires/chaînes CSS,
 * les chaînes Shell entre apostrophes et les blocs de code clôturés Markdown.
 *
 * <p><b>Parser maison (intégré, sans :tm4e requis) :</b>
 * les langages suivants sont pris en charge nativement :
 * <ul>
 *   <li><b>Syntaxe C</b> (machine à états partagée dans {@link CLikeTokenizer#styleCLike}) :
 *       Java, Kotlin, JavaScript, TypeScript, C, C++, Go, Rust, Ruby,
 *       PHP, Swift, Dart, Groovy</li>
 *   <li><b>Spécialisés</b> : XML/HTML, CSS/SCSS/LESS, Markdown, Python,
 *       JSON, Lua, Shell/Bash, YAML, SQL, Properties, TOML, Smali, log</li>
 * </ul>
 *
 * <p>Pour une coloration plus riche (héritage de portées, injections,
 * constructions multi-lignes), le module optionnel {@code :tm4e} peut être
 * ajouté — il implémente {@link TextMateTokenizer} et s'enregistre via
 * {@link #setTextMateTokenizer(TextMateTokenizer)}. Quand TextMate signale
 * {@link TextMateTokenizer#isAvailable(String)} pour un langage, le
 * tokéniseur intégré est contourné pour ce langage.
 */
public class SyntaxHighlighter {

    /**
     * Tokéniseur TextMate optionnel. S'il est non null et signale
     * {@link TextMateTokenizer#isAvailable(String)} pour un langage,
     * {@link #styleLine} lui délègue au lieu du switch-case intégré.
     *
     * <p>Renseigné via {@link #setTextMateTokenizer(TextMateTokenizer)}
     * depuis le {@code ProjectActivity} du module {@code :app} (ou hook
     * de démarrage équivalent). Reste {@code null} dans les tests JVM
     * purs, donc le tokéniseur intégré est utilisé.
     */
    private static volatile TextMateTokenizer textMateTokenizer;

    /**
     * Enregistre un tokéniseur TextMate. À appeler une seule fois au
     * démarrage de l'application, avant toute création de vue éditeur.
     * Passer {@code null} pour désactiver.
     */
    public static void setTextMateTokenizer(TextMateTokenizer t) {
        textMateTokenizer = t;
    }

    /**
     * Tokénise une ligne unique, renvoyant les portions stylées et l'état
     * de sortie. Équivalent à {@code styleLine(line, entryState, language, true)} —
     * la délégation TextMate est permise (un tokéniseur enregistré qui
     * signale {@link TextMateTokenizer#isAvailable(String)} l'emporte).
     *
     * @param line       le texte de la ligne (sans le saut de ligne final)
     * @param entryState l'état du lexer en entrée de ligne
     * @param language   "java", "kotlin", "xml" ou "markdown"
     * @return StyledLine avec les portions et l'état de sortie
     */
    public StyledLine styleLine(String line, int entryState, String language) {
        return styleLine(line, entryState, language, true);
    }

    /**
     * Tokénise une ligne unique avec un coupe-circuit TextMate
     * <b>local à l'appelant</b>.
     *
     * <p>La protection contre les gros documents voyage via ce paramètre
     * par appel, calculé à partir de la taille du propre document de
     * l'appelant : {@code EditorSession} passe
     * {@code lineCount <= TEXTMATE_LINE_LIMIT} afin que deux sessions avec
     * des documents de tailles différentes ne puissent jamais interférer.</p>
     *
     * @param allowTextMate {@code false} contourne entièrement la voie
     *                      TextMate (garde-fou ANR gros document, décidé
     *                      par document)
     */
    public StyledLine styleLine(String line, int entryState, String language,
            boolean allowTextMate) {
        // Délégation TextMate (opt-in par langage), gardée par la
        // barrière allowTextMate propre à chaque appel.
        TextMateTokenizer tm = textMateTokenizer;
        if (allowTextMate && tm != null && tm.isAvailable(language)) {
            return tm.tokenize(line, entryState, language);
        }
        // Parser maison : répartition par langage, pilotée par le registre :
        // la famille de tokéniseur vient du profil de langage
        // (LanguageRegistry), donc les alias déclarés dans le profil
        // (py, md, htm, svg, ini, kt, rs… et tout langage enregistré par
        // un hôte) sont routés vers le bon tokéniseur au lieu de retomber
        // dans le chemin C-like générique avec l'ensemble de mots-clés Java.
        LanguageProfile profile = LanguageRegistry.forName(language);
        SyntaxFamily family = profile != null ? profile.family : SyntaxFamily.C_LIKE;
        switch (family) {
            case LOG:
                return LogTokenizer.styleLog(line);
            case MARKDOWN:
                return MarkdownTokenizer.styleMarkdown(line, entryState);
            case PYTHON:
                return PythonTokenizer.stylePython(line, entryState);
            case JSON:
                return JsonTokenizer.styleJson(line, entryState);
            case LUA:
                return LuaTokenizer.styleLua(line, entryState);
            case XML:
                // HTML retombe sur le tokéniseur XML quand TextMate est
                // désactivé (gros fichiers) ou indisponible. Le tokéniseur
                // XML gère balises, attributs, commentaires, CDATA et
                // entités HTML.
                return XmlTokenizer.styleXml(line, entryState);
            case CSS:
                return CssTokenizer.styleCss(line, entryState);
            case SHELL:
                return ShellTokenizer.styleShell(line, entryState);
            case YAML:
                return YamlTokenizer.styleYaml(line, entryState);
            case SQL:
                return SqlTokenizer.styleSql(line, entryState);
            case PROPERTIES:
                return PropertiesTokenizer.styleProperties(line, entryState);
            case TOML:
                return TomlTokenizer.styleToml(line, entryState);
            case SMALI:
                return SmaliTokenizer.styleSmali(line, entryState);
            case C_LIKE:
            default:
                // Les langages à syntaxe C partagent le chemin générique Java
                // mais reçoivent leur propre ensemble de mots-clés via
                // getKeywords(). Inclut : java, kotlin, javascript,
                // typescript, c, cpp, go, rust, ruby, php, swift, dart,
                // groovy — plus tout langage personnalisé enregistré par un
                // hôte avec SyntaxFamily.C_LIKE.
                return CLikeTokenizer.styleCLike(line, entryState, language);
        }
    }
}
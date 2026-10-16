package jo.codeeditor.textmate;

import jo.codeeditor.highlight.TokenType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mapping portées de grammaire → {@link TokenType} (ADR-002, C3 —
 * amorce du chantier C4 « schéma de portées »).
 *
 * <p>Les portées TextMate sont des hiérarchies pointées
 * ({@code comment.block.documentation.csharp}). Le mapper applique la
 * correspondance du segment le plus précis connu : il parcourt les
 * préfixes de la portée du plus long au plus court —
 * {@code comment.block.documentation} (→ {@code DOC_COMMENT}) avant
 * {@code comment} (→ {@code COMMENT}). Les types fins déjà présents
 * dans {@link TokenType} ({@code DOC_COMMENT}, {@code KEYWORD_CONTROL},
 * {@code RAW_STRING}, {@code ENTITY}…) sont exploités ; un thème qui ne
 * les distingue pas les voit hériter du champ parent.</p>
 *
 * <p>Table calibrée sur les catégories standard des grammaires de
 * l'écosystème (celles des portées « TextMate Language Grammars ») :
 * commentaire, chaîne, mot-clé, constante, entité, variable, support,
 * stockage, ponctuation, interpolation, markup. Une portée inconnue
 * retombe sur {@link TokenType#PLAIN}.</p>
 *
 * <p>C4 (schéma de portées pour les thèmes) : la couverture est
 * CALIBRÉE pour converger avec la voie intégrée — les interpolations
 * des template literals atterrissent en VARIABLE (comme
 * {@code addTemplateLiteralSpans} côté famille C), les propriétés
 * d'objet en PROPERTY (comme l'identifiant après point), le markup
 * Markdown sur les mêmes types que {@code MarkdownTokenizer}.</p>
 */
public final class ScopeMapper {

    private ScopeMapper() {}

    /** Correspondances préfixe → type (l'ordre d'insertion documente la priorité). */
    private static final Map<String, TokenType> BY_PREFIX = build();

    private static Map<String, TokenType> build() {
        Map<String, TokenType> m = new LinkedHashMap<>();
        // ── Commentaires : ligne, bloc, documentation ─────────────
        m.put("comment.block.documentation", TokenType.DOC_COMMENT);
        m.put("comment.block.javadoc", TokenType.DOC_COMMENT);
        m.put("comment.line.double-slash.documentation", TokenType.DOC_COMMENT);
        m.put("comment.block", TokenType.COMMENT);
        m.put("comment.line", TokenType.COMMENT);
        m.put("comment", TokenType.COMMENT);
        // ── Chaînes : variété et finesse ──────────────────────────
        m.put("string.quoted.triple", TokenType.RAW_STRING);
        m.put("string.quoted.other.literal", TokenType.RAW_STRING);
        m.put("string.quoted.double.verbatim", TokenType.RAW_STRING);
        m.put("string.quoted.single", TokenType.CHAR);
        m.put("string.regexp", TokenType.REGEXP);
        m.put("string", TokenType.STRING);
        // ── Échappements et entités ───────────────────────────────
        m.put("constant.character.escape", TokenType.ESCAPE);
        m.put("constant.character.entity", TokenType.ENTITY);
        // ── Mots-clés : contrôle, modificateur, other ─────────────
        // Calibrage C4 (convergence voie intégrée / inter-grammaires) :
        // les PRIMITIFS et les mots-clés de déclaration (function/class,
        // new, import/export) sont des MOTS-CLÉS côté intégré et côté
        // grammaire du dépôt (csharp) — « keyword.control.new » (java),
        // « storage.type.primitive » (java), « storage.type.function »
        // (javascript, kotlin def), « storage.type.class » (python) et
        // « keyword.control.import/export » (python, javascript)
        // atterrissent donc en KEYWORD/KEYWORD_MODIFIER, PAS en
        // KEYWORD_CONTROL/TYPE.
        m.put("keyword.control.import", TokenType.KEYWORD);
        m.put("keyword.control.export", TokenType.KEYWORD_MODIFIER);
        m.put("keyword.control.new", TokenType.KEYWORD);
        m.put("keyword.control", TokenType.KEYWORD_CONTROL);
        m.put("keyword.operator", TokenType.OPERATOR);
        m.put("keyword.other.modifier", TokenType.KEYWORD_MODIFIER);
        m.put("storage.modifier.import", TokenType.PROPERTY);
        m.put("storage.modifier", TokenType.KEYWORD_MODIFIER);
        m.put("keyword", TokenType.KEYWORD);
        m.put("storage.type.primitive", TokenType.KEYWORD);
        m.put("storage.type.function", TokenType.KEYWORD);
        m.put("storage.type.class", TokenType.KEYWORD);
        m.put("storage.type", TokenType.TYPE);
        m.put("storage", TokenType.KEYWORD);
        // ── Constantes et nombres ─────────────────────────────────
        m.put("constant.numeric", TokenType.NUMBER);
        m.put("constant.language", TokenType.CONSTANT);
        m.put("constant.character", TokenType.CHAR);
        m.put("constant", TokenType.CONSTANT);
        // ── Entités : fonctions, types, tags, noms ─────────────────
        m.put("entity.name.function", TokenType.FUNC);
        m.put("entity.name.type", TokenType.TYPE);
        m.put("entity.name.tag", TokenType.TYPE);
        m.put("entity.name.namespace", TokenType.NAMESPACE);
        // Décorateurs python : construct équivalent des annotations de
        // la voie intégrée (@Nom → ANNOTATION, famille C et python).
        m.put("entity.name.function.decorator", TokenType.ANNOTATION);
        m.put("punctuation.definition.decorator", TokenType.ANNOTATION);
        m.put("entity.name", TokenType.TYPE);
        m.put("entity.other.attribute-name", TokenType.PROPERTY);
        // ── Variables et paramètres ───────────────────────────────
        m.put("variable.language", TokenType.KEYWORD);
        m.put("variable.parameter", TokenType.VARIABLE);
        // Propriété d'objet (obj.champ) : convergence avec la voie
        // intégrée (identifiant après point → PROPERTY).
        m.put("variable.other.object.property", TokenType.PROPERTY);
        m.put("variable.other.property", TokenType.PROPERTY);
        m.put("variable.other", TokenType.VARIABLE);
        m.put("variable", TokenType.VARIABLE);
        // ── Support (bibliothèques) ───────────────────────────────
        m.put("support.function", TokenType.FUNC);
        m.put("support.type", TokenType.TYPE);
        m.put("support.constant", TokenType.CONSTANT);
        m.put("support.class", TokenType.TYPE);
        m.put("support.module", TokenType.TYPE);
        // ── Interpolations : convergence avec la voie intégrée ────
        // (les ${…} des template literals JS/TS sont VARIABLE côté
        // intégrée — les portées d'interpolation idem côté grammaires).
        m.put("meta.interpolation", TokenType.VARIABLE);
        m.put("meta.template.expression", TokenType.VARIABLE);
        m.put("punctuation.definition.template-expression", TokenType.VARIABLE);
        // ── Délimiteurs de définition : colorés comme le contenu ──
        m.put("punctuation.definition.comment", TokenType.COMMENT);
        m.put("punctuation.definition.string", TokenType.STRING);
        // ── Invalides : illégal (erreur) vs déprécié (avertissement)
        m.put("invalid.deprecated", TokenType.WARNING);
        // ── Markup (grammaires Markdown) : convergence avec la voie
        // intégrée (titres → KEYWORD, emphases → EMPHASIS, code
        // inline → STRING, liens → FUNC).
        m.put("markup.heading", TokenType.KEYWORD);
        m.put("markup.bold", TokenType.EMPHASIS);
        m.put("markup.italic", TokenType.EMPHASIS);
        m.put("markup.inline.raw", TokenType.STRING);
        m.put("markup.underline.link", TokenType.FUNC);
        m.put("string.other.link", TokenType.FUNC);
        // ── Divers ────────────────────────────────────────────────
        m.put("meta.annotation", TokenType.ANNOTATION);
        m.put("meta.preprocessor", TokenType.ANNOTATION);
        m.put("punctuation.definition.annotation", TokenType.ANNOTATION);
        m.put("meta.tag", TokenType.TYPE);
        m.put("punctuation", TokenType.PUNCT);
        m.put("invalid", TokenType.ERROR);
        return m;
    }

    /**
     * Mappe une portée (potentiellement multi-segments, potentiellement
     * LISTE de portées séparées par des espaces) vers le type le plus
     * précis connu. {@code null} ou vide → {@code PLAIN}.
     */
    public static TokenType tokenTypeFor(String scope) {
        if (scope == null || scope.isBlank()) return TokenType.PLAIN;
        String s = scope.trim();
        // Liste de portées (convention TextMate, C3-sexties) : une
        // règle peut porter PLUSIEURS portées séparées par des espaces
        // (« meta.property-name.css support.type.property-name.css »)
        // — l'externe d'abord, l'interne en dernier. Sans éclatement,
        // la chaîne ENTIÈRE était cherchée : aucune correspondance,
        // le jeton tombait PLAIN (noms de propriétés CSS, définitions
        // de variables JS, 125 portées python). L'INTERNE (la plus
        // précise) l'emporte ; inconnue, elle cède à l'externe.
        if (s.indexOf(' ') >= 0) {
            String[] parts = s.split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) {
                TokenType t = tokenTypeFor(parts[i]);
                if (t != TokenType.PLAIN) return t;
            }
            return TokenType.PLAIN;
        }
        // Préfixe le plus long d'abord : les segments se retirent un
        // à un jusqu'à une correspondance (comment.block.documentation
        // → comment.block → comment).
        String candidate = s;
        while (!candidate.isEmpty()) {
            TokenType t = BY_PREFIX.get(candidate);
            if (t != null) return t;
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) break;
            candidate = candidate.substring(0, dot);
        }
        return TokenType.PLAIN;
    }
}

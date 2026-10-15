package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Grammaire déclarative d'un tokéniseur (ADR-002, chantier C2) — la
 * table de règles consommée par {@link GenericTokenizer}.
 *
 * <p>La famille lexicale ({@code SyntaxFamily}) devient le
 * <b>sélecteur de template</b>, plus le lieu du code : chaque
 * tokéniseur intégré écrit à la main migre sur ce moteur au rythme
 * du cliquet de démantèlement (migration tokéniseur par tokéniseur,
 * la suite existante gardant ses assertions). Migrations livrées :
 * Properties ({@link #PROPERTIES}), TOML ({@link #TOML}). Les
 * suivantes étendront ce modèle au fur et à mesure — chaque champ
 * documente ce que le moteur sait exploiter aujourd'hui.</p>
 *
 * <p>Champs de l'étape C2-é1 (grammaires clé=valeur — properties,
 * ini, formats de configuration) :</p>
 * <ul>
 *   <li>{@link #lineComments} — préfixes de commentaire de ligne
 *       (uniquement en tête de ligne, AVANT le séparateur) ;</li>
 *   <li>{@link #keyValueSeparators} — caractères séparateurs
 *       clé/valeur ({@code =}, {@code :}) ;</li>
 *   <li>{@link #keyType}/{@link #separatorType}/{@link #valueType}
 *       — les types de jetons des trois zones ;</li>
 *   <li>{@link #escapesInValue} — échappements backslash en valeur
 *       ({@code \\n}, {@code \\t}, {@code \\uXXXX} — 6 caractères).</li>
 * </ul>
 *
 * <p>Champs de l'étape C2-é2 (grammaires à sections et valeurs à
 * jetons — toml, formats de configuration riches) :</p>
 * <ul>
 *   <li>{@link #commentsAnywhere} — vrai : le préfixe de commentaire
 *       s'applique aussi APRÈS le séparateur (un {@code #} en fin de
 *       ligne TOML commente) ;</li>
 *   <li>{@link #sections} — en-têtes {@code [nom]} / {@code [[nom]]}
 *       avant le séparateur, avec leurs deux types de jetons ;</li>
 *   <li>{@link #valueQuotes} — chaînes quotées en zone valeur
 *       (échappements internes en ESCAPE, guillemet orphelin en
 *       ponctuation de repli) ;</li>
 *   <li>{@link #valueNumberExtras} — jeu de caractères étendant les
 *       nombres en valeur (TOML : {@code . - : T Z} pour les dates) ;</li>
 *   <li>{@link #valueKeywords}/{@link #valueKeywordType} — mots-clés
 *       de valeur (booléens TOML) et leur type.</li>
 * </ul>
 *
 * <p>Dès qu'une règle de jeton de valeur est déclarée (chaîne,
 * nombre ou mot-clé), la zone valeur est tokénisée <b>jeton par
 * jeton</b> ({@link #tokenizesValue()}) et {@link #escapesInValue}
 * est ignoré — les échappements ne vivent plus que dans les chaînes
 * quotées.</p>
 *
 * <p>Immuable et thread-safe.</p>
 */
public final class TokenizerTemplate {

    /** Préfixes de commentaire de ligne (jamais vides dans un template actif). */
    public final List<String> lineComments;
    /** Caractères séparateurs clé/valeur (vide : pas de mode clé=valeur). */
    public final Set<Character> keyValueSeparators;
    /** Type de la zone clé (avant le séparateur). */
    public final TokenType keyType;
    /** Type du séparateur lui-même. */
    public final TokenType separatorType;
    /** Type de la zone valeur (après le séparateur). */
    public final TokenType valueType;
    /** Vrai : les séquences d'échappement backslash en valeur reçoivent ESCAPE. */
    public final boolean escapesInValue;

    /** Vrai : le préfixe de commentaire s'applique aussi après le
     * séparateur (TOML) — faux : uniquement en zone clé (Properties). */
    public final boolean commentsAnywhere;
    /** Caractère ouvrant une en-tête de section ({@code [}) — 0 : désactivé. */
    public final char sectionOpen;
    /** Caractère fermant une en-tête de section ({@code ]}). */
    public final char sectionClose;
    /** Type du nom de section (entre crochets). */
    public final TokenType sectionNameType;
    /** Type des crochets fermants (un seul jeton, largeur = nombre d'ouvrants). */
    public final TokenType sectionBracketType;
    /** Guillemets ouvrant une chaîne en zone valeur. */
    public final Set<Character> valueQuotes;
    /** Caractères acceptés DANS un nombre en valeur, au-delà des chiffres
     * (TOML : {@code . - : T Z} pour dates et heures). */
    public final Set<Character> valueNumberExtras;
    /** Mots-clés reconnus en zone valeur (ex. booléens TOML). */
    public final Set<String> valueKeywords;
    /** Type d'un mot-clé de valeur reconnu. */
    public final TokenType valueKeywordType;
    /** Type d'un nombre en valeur. */
    public final TokenType valueNumberType;

    private TokenizerTemplate(List<String> lineComments,
            Set<Character> keyValueSeparators, TokenType keyType,
            TokenType separatorType, TokenType valueType, boolean escapesInValue,
            boolean commentsAnywhere, char sectionOpen, char sectionClose,
            TokenType sectionNameType, TokenType sectionBracketType,
            Set<Character> valueQuotes, Set<Character> valueNumberExtras,
            Set<String> valueKeywords, TokenType valueKeywordType,
            TokenType valueNumberType) {
        this.lineComments = List.copyOf(lineComments);
        this.keyValueSeparators = Collections.unmodifiableSet(keyValueSeparators);
        this.keyType = keyType;
        this.separatorType = separatorType;
        this.valueType = valueType;
        this.escapesInValue = escapesInValue;
        this.commentsAnywhere = commentsAnywhere;
        this.sectionOpen = sectionOpen;
        this.sectionClose = sectionClose;
        this.sectionNameType = sectionNameType;
        this.sectionBracketType = sectionBracketType;
        this.valueQuotes = Collections.unmodifiableSet(valueQuotes);
        this.valueNumberExtras = Collections.unmodifiableSet(valueNumberExtras);
        this.valueKeywords = Collections.unmodifiableSet(valueKeywords);
        this.valueKeywordType = valueKeywordType;
        this.valueNumberType = valueNumberType;
    }

    /**
     * Template Properties intégré (migration C2-é1) : commentaires
     * {@code #}/{@code !}, séparateurs {@code =}/{@code :}, clé en
     * PROPERTY, valeur en STRING, échappements — reprend la grammaire
     * historique de {@code PropertiesTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate PROPERTIES = builder()
            .lineComments("#", "!")
            .keyValueSeparators('=', ':')
            .keyType(TokenType.PROPERTY)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.STRING)
            .escapesInValue(true)
            .build();

    /**
     * Template TOML intégré (migration C2-é2) : sections
     * {@code [table]}/{@code [[table]]}, paires {@code clé = valeur},
     * chaînes basiques et littérales, nombres/dates RFC 3339,
     * booléens en CONSTANT, commentaires {@code #} partout — reprend
     * la grammaire historique de {@code TomlTokenizer} octet pour
     * octet. Les mots-clés viennent du registre (source unique D5).
     */
    public static final TokenizerTemplate TOML = builder()
            .lineComments("#")
            .commentsAnywhere(true)
            .sections('[', ']', TokenType.TYPE, TokenType.PUNCT)
            .keyValueSeparators('=')
            .keyType(TokenType.PROPERTY)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.STRING)
            .valueQuotes('"', '\'')
            .valueNumberChars('.', '-', ':', 'T', 'Z')
            .valueKeywords(KeywordTables.TOML_KEYWORDS)
            .valueKeywordType(TokenType.CONSTANT)
            .build();

    /** Vrai si des en-têtes de section sont déclarées. */
    public boolean hasSections() {
        return sectionOpen != 0;
    }

    /**
     * Vrai si la zone valeur doit être tokénisée jeton par jeton
     * (chaînes, nombres, mots) plutôt qu'en un run mono-jeton —
     * déclenché par la déclaration d'une règle de jeton de valeur.
     */
    public boolean tokenizesValue() {
        return !valueQuotes.isEmpty() || !valueNumberExtras.isEmpty()
                || !valueKeywords.isEmpty();
    }

    /** Builder fluent. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder pour {@link TokenizerTemplate}. */
    public static final class Builder {
        private final List<String> lineComments = new ArrayList<>();
        private final Set<Character> keyValueSeparators = new HashSet<>();
        private TokenType keyType = TokenType.PROPERTY;
        private TokenType separatorType = TokenType.OPERATOR;
        private TokenType valueType = TokenType.STRING;
        private boolean escapesInValue = false;
        private boolean commentsAnywhere = false;
        private char sectionOpen = 0;
        private char sectionClose = 0;
        private TokenType sectionNameType = TokenType.TYPE;
        private TokenType sectionBracketType = TokenType.PUNCT;
        private final Set<Character> valueQuotes = new HashSet<>();
        private final Set<Character> valueNumberExtras = new HashSet<>();
        private final Set<String> valueKeywords = new HashSet<>();
        private TokenType valueKeywordType = TokenType.CONSTANT;
        private TokenType valueNumberType = TokenType.NUMBER;

        /** Préfixes de commentaire de ligne (entrées vides/nulles ignorées). */
        public Builder lineComments(String... prefixes) {
            for (String p : prefixes) {
                if (p != null && !p.isEmpty()) lineComments.add(p);
            }
            return this;
        }

        /** Caractères séparateurs clé/valeur. */
        public Builder keyValueSeparators(char... separators) {
            for (char c : separators) keyValueSeparators.add(c);
            return this;
        }

        /** Type de la zone clé. */
        public Builder keyType(TokenType type) {
            this.keyType = type != null ? type : TokenType.PROPERTY;
            return this;
        }

        /** Type du séparateur. */
        public Builder separatorType(TokenType type) {
            this.separatorType = type != null ? type : TokenType.OPERATOR;
            return this;
        }

        /** Type de la zone valeur. */
        public Builder valueType(TokenType type) {
            this.valueType = type != null ? type : TokenType.STRING;
            return this;
        }

        /** Échappements backslash en valeur (ESCAPE). */
        public Builder escapesInValue(boolean enabled) {
            this.escapesInValue = enabled;
            return this;
        }

        /** Commentaires applicables aussi après le séparateur. */
        public Builder commentsAnywhere(boolean enabled) {
            this.commentsAnywhere = enabled;
            return this;
        }

        /** En-têtes de section (ouvrant, fermant, type du nom, type des fermants). */
        public Builder sections(char open, char close, TokenType nameType,
                TokenType bracketType) {
            this.sectionOpen = open;
            this.sectionClose = close;
            this.sectionNameType = nameType != null ? nameType : TokenType.TYPE;
            this.sectionBracketType = bracketType != null ? bracketType : TokenType.PUNCT;
            return this;
        }

        /** Guillemets ouvrant une chaîne en zone valeur. */
        public Builder valueQuotes(char... quotes) {
            for (char q : quotes) valueQuotes.add(q);
            return this;
        }

        /** Caractères supplémentaires acceptés dans un nombre en valeur. */
        public Builder valueNumberChars(char... extras) {
            for (char c : extras) valueNumberExtras.add(c);
            return this;
        }

        /** Mots-clés reconnus en zone valeur (copie défensive). */
        public Builder valueKeywords(Set<String> keywords) {
            if (keywords != null) valueKeywords.addAll(keywords);
            return this;
        }

        /** Type d'un mot-clé de valeur reconnu. */
        public Builder valueKeywordType(TokenType type) {
            this.valueKeywordType = type != null ? type : TokenType.CONSTANT;
            return this;
        }

        /** Type d'un nombre en valeur. */
        public Builder valueNumberType(TokenType type) {
            this.valueNumberType = type != null ? type : TokenType.NUMBER;
            return this;
        }

        public TokenizerTemplate build() {
            return new TokenizerTemplate(
                    new ArrayList<>(lineComments),
                    new HashSet<>(keyValueSeparators),
                    keyType, separatorType, valueType, escapesInValue,
                    commentsAnywhere, sectionOpen, sectionClose,
                    sectionNameType, sectionBracketType,
                    new HashSet<>(valueQuotes), new HashSet<>(valueNumberExtras),
                    new HashSet<>(valueKeywords), valueKeywordType, valueNumberType);
        }
    }

    @Override
    public String toString() {
        return "TokenizerTemplate(comments=" + lineComments
                + ", separators=" + keyValueSeparators
                + ", key=" + keyType + ", sep=" + separatorType
                + ", value=" + valueType + ", escapes=" + escapesInValue
                + ", commentsAnywhere=" + commentsAnywhere
                + ", sections=" + (hasSections()
                        ? sectionOpen + "/" + sectionClose : "aucune")
                + ", valueQuotes=" + valueQuotes
                + ", valueNumberExtras=" + valueNumberExtras
                + ", valueKeywords=" + valueKeywords.size() + " mots"
                + ")";
    }
}

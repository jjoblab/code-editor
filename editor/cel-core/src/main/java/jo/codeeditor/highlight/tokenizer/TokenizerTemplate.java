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
 * la suite existante gardant ses assertions). Première migration :
 * Properties ({@link #PROPERTIES}). Les migrations suivantes
 * étendront ce modèle (sections, nombres, guillemets…) au fur et à
 * mesure — chaque champ documente ce que le moteur sait exploiter
 * aujourd'hui.</p>
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

    private TokenizerTemplate(List<String> lineComments,
            Set<Character> keyValueSeparators, TokenType keyType,
            TokenType separatorType, TokenType valueType, boolean escapesInValue) {
        this.lineComments = List.copyOf(lineComments);
        this.keyValueSeparators = Collections.unmodifiableSet(keyValueSeparators);
        this.keyType = keyType;
        this.separatorType = separatorType;
        this.valueType = valueType;
        this.escapesInValue = escapesInValue;
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

        public TokenizerTemplate build() {
            return new TokenizerTemplate(
                    new ArrayList<>(lineComments),
                    new HashSet<>(keyValueSeparators),
                    keyType, separatorType, valueType, escapesInValue);
        }
    }

    @Override
    public String toString() {
        return "TokenizerTemplate(comments=" + lineComments
                + ", separators=" + keyValueSeparators
                + ", key=" + keyType + ", sep=" + separatorType
                + ", value=" + valueType + ", escapes=" + escapesInValue + ")";
    }
}

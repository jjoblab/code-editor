package jo.codeeditor.languages;

import jo.codeeditor.edit.CommentSyntax;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Description immuable d'un langage (portage du
 * un profil de langue).
 *
 * <p>Un profil regroupe tout ce que l'éditeur doit savoir d'un langage
 * au-delà du SPI d'intelligence ({@code jo.codeeditor.lang.Language}) :
 * les ids auxquels il répond (nom canonique + alias), les extensions de
 * fichiers couvertes, son ensemble de mots-clés, sa {@link SyntaxFamily}
 * lexicale (qui sélectionne le tokenizer intégré), sa
 * {@link CommentSyntax} (qui pilote {@code toggleLineComment}/
 * {@code toggleBlockComment}) et — depuis le Lot 2 — ses
 * <b>traits d'édition</b> : caractères à auto-fermer (quotes,
 * crochets), stratégie de pliage.</p>
 *
 * <p>Les profils sont enregistrés dans le {@link LanguageRegistry}. Les
 * hôtes peuvent enregistrer leurs propres langages — y compris des
 * langages inconnus de l'éditeur — et le highlighter + les bascules de
 * commentaire les prennent en compte automatiquement :</p>
 *
 * <pre>{@code
 * LanguageProfile custom = LanguageProfile.builder("mylang")
 *     .family(SyntaxFamily.C_LIKE)
 *     .alias("ml")
 *     .extension("ml")
 *     .keywords("if", "else", "repeat", "until")
 *     .commentSyntax(new CommentSyntax("#", null, null))
 *     .autoCloseQuotes('"', '\'')
 *     .autoCloseBrackets('(', '{', '[')
 *     .foldStrategy(FoldStrategy.BRACE)
 *     .build();
 * LanguageRegistry.register(custom);
 * editorSession.setLanguage("mylang");
 * }</pre>
 *
 * <p>Les instances sont immuables et thread-safe.</p>
 */
public final class LanguageProfile {

    /** Id canonique en minuscules (ex. {@code "python"}). */
    public final String name;
    /** Tokenizer intégré qui gère ce langage. */
    public final SyntaxFamily family;
    /** Ids minuscules supplémentaires auxquels ce profil répond (ex. {@code "py"}). */
    public final Set<String> aliases;
    /** Extensions de fichiers en minuscules sans le point (ex. {@code "py"}). */
    public final Set<String> extensions;
    /** Ensemble de mots-clés consommé par le tokenizer C-like (peut être vide). */
    public final Set<String> keywords;
    /** Syntaxe de commentaire utilisée par les bascules de commentaire (jamais null). */
    public final CommentSyntax commentSyntax;
    /**
     * Caractères de guillemet à auto-fermer (Lot 2). Vide pour les
     * langages sans guillemets (texte brut, log, properties, toml).
     * Pour Java/Kotlin/C/JS/TS/etc. : {@code "}, {@code '} (et
     * {@code `} pour JS/TS). Pour Python : {@code "}, {@code '}
     * (triples incluses via la logique de SmartTyping).
     */
    public final Set<Character> autoCloseQuotes;
    /**
     * Caractères de crochet à auto-fermer (Lot 2). Vide pour les
     * langages sans structure de code (texte brut, log, properties).
     * Pour tous les langages de code : {@code (}, {@code {}, {@code [}.
     */
    public final Set<Character> autoCloseBrackets;
    /**
     * Stratégie de pliage (Lot 2). Jamais null — défaut
     * {@link FoldStrategy#BRACE} pour la famille C_LIKE,
     * {@link FoldStrategy#OFF} pour text/log/properties/toml,
     * autres valeurs par famille (voir
     * {@link Builder#foldStrategy}).
     */
    public final FoldStrategy foldStrategy;

    private LanguageProfile(String name, SyntaxFamily family,
            Set<String> aliases, Set<String> extensions,
            Set<String> keywords, CommentSyntax commentSyntax,
            Set<Character> autoCloseQuotes, Set<Character> autoCloseBrackets,
            FoldStrategy foldStrategy) {
        this.name = name;
        this.family = family;
        this.aliases = aliases;
        this.extensions = extensions;
        this.keywords = keywords;
        this.commentSyntax = commentSyntax;
        this.autoCloseQuotes = autoCloseQuotes;
        this.autoCloseBrackets = autoCloseBrackets;
        this.foldStrategy = foldStrategy;
    }

    /** Builder fluent — voir la javadoc de la classe pour un exemple d'utilisation. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** true quand {@code id} est le nom canonique ou un des alias. */
    public boolean answersTo(String id) {
        if (id == null) return false;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        return name.equals(norm) || aliases.contains(norm);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LanguageProfile)) return false;
        return name.equals(((LanguageProfile) o).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "LanguageProfile(" + name + ", family=" + family
                + ", aliases=" + aliases + ")";
    }

    /** Builder pour {@link LanguageProfile}. */
    public static final class Builder {
        private final String name;
        private final Set<String> aliases = new HashSet<>();
        private final Set<String> extensions = new HashSet<>();
        private final Set<String> keywords = new HashSet<>();
        private SyntaxFamily family = SyntaxFamily.C_LIKE;
        private CommentSyntax commentSyntax = CommentSyntax.C_STYLE;
        // Lot 2 : traits d'édition. Les valeurs par défaut sont
        // calculées dans build() à partir de la famille (C_LIKE →
        // quotes+brackets, XML → aucun crochet, etc.) pour préserver
        // la compatibilité avec les profils existants qui n'appellent
        // pas les nouvelles méthodes du builder.
        private Set<Character> autoCloseQuotes = null;
        private Set<Character> autoCloseBrackets = null;
        private FoldStrategy foldStrategy = null;

        private Builder(String name) {
            if (name == null) throw new IllegalArgumentException("name is null");
            String n = name.trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty()) throw new IllegalArgumentException("name is empty");
            this.name = n;
        }

        public Builder family(SyntaxFamily family) {
            this.family = family != null ? family : SyntaxFamily.C_LIKE;
            return this;
        }

        public Builder alias(String... ids) {
            aliases.addAll(normalize(ids));
            return this;
        }

        public Builder extension(String... exts) {
            for (String v : exts) {
                if (v == null) continue;
                String n = v.trim().toLowerCase(Locale.ROOT);
                if (n.startsWith(".")) n = n.substring(1); // tolère une entrée avec point
                if (!n.isEmpty()) extensions.add(n);
            }
            return this;
        }

        public Builder keywords(String... words) {
            keywords.addAll(Arrays.asList(words));
            return this;
        }

        public Builder keywordSet(Set<String> set) {
            if (set != null) keywords.addAll(set);
            return this;
        }

        public Builder commentSyntax(CommentSyntax syntax) {
            this.commentSyntax = syntax != null ? syntax : CommentSyntax.C_STYLE;
            return this;
        }

        /**
         * Lot 2 : caractères de guillemet à auto-fermer. Si non
         * appelé, la valeur par défaut dépend de la famille :
         * C_LIKE → {@code "}, {@code '} ; PYTHON, LUA, SHELL, SQL,
         * JSON → {@code "}, {@code '} ; XML, CSS, MARKDOWN →
         * {@code "} ; LOG, PROPERTIES, TOML, SMALI → aucun.
         * Passer {@code null} ou un tableau vide pour désactiver
         * explicitement l'auto-close des guillemets.
         */
        public Builder autoCloseQuotes(char... quotes) {
            this.autoCloseQuotes = new HashSet<>();
            for (char c : quotes) this.autoCloseQuotes.add(c);
            return this;
        }

        /**
         * Lot 2 : caractères de crochet à auto-fermer. Si non
         * appelé, la valeur par défaut dépend de la famille :
         * C_LIKE, PYTHON, LUA, JSON, SMALI, SQL → {@code (},
         * {@code {}, {@code [} ; XML, CSS, MARKDOWN, YAML,
         * SHELL, PROPERTIES, TOML, LOG → aucun.
         */
        public Builder autoCloseBrackets(char... brackets) {
            this.autoCloseBrackets = new HashSet<>();
            for (char c : brackets) this.autoCloseBrackets.add(c);
            return this;
        }

        /**
         * Lot 2 : stratégie de pliage. Si non appelé, la valeur par
         * défaut dépend de la famille : C_LIKE/JSON/SQL/SMALI →
         * {@link FoldStrategy#BRACE} ; XML/CSS → {@link FoldStrategy#TAG}
         * (CSS n'a pas de balises mais a des blocs { … } — TAG est
         * étendu pour CSS en BRACE) ; PYTHON/YAML →
         * {@link FoldStrategy#INDENT} ; LUA →
         * {@link FoldStrategy#KEYWORD} ; MARKDOWN →
         * {@link FoldStrategy#HEADING} ; LOG/PROPERTIES/TOML →
         * {@link FoldStrategy#OFF}.
         */
        public Builder foldStrategy(FoldStrategy strategy) {
            this.foldStrategy = strategy;
            return this;
        }

        public LanguageProfile build() {
            // Lot 2 : valeurs par défaut par famille (quand le builder
            // n'a pas été appelé explicitement pour ces traits).
            Set<Character> finalQuotes = autoCloseQuotes != null
                    ? autoCloseQuotes : defaultQuotes(family);
            Set<Character> finalBrackets = autoCloseBrackets != null
                    ? autoCloseBrackets : defaultBrackets(family);
            FoldStrategy finalFold = foldStrategy != null
                    ? foldStrategy : defaultFoldStrategy(family);
            return new LanguageProfile(name, family,
                    Collections.unmodifiableSet(new HashSet<>(aliases)),
                    Collections.unmodifiableSet(new HashSet<>(extensions)),
                    Collections.unmodifiableSet(new HashSet<>(keywords)),
                    commentSyntax,
                    Collections.unmodifiableSet(new HashSet<>(finalQuotes)),
                    Collections.unmodifiableSet(new HashSet<>(finalBrackets)),
                    finalFold);
        }

        /** Valeurs par défaut pour autoCloseQuotes selon la famille. */
        private static Set<Character> defaultQuotes(SyntaxFamily family) {
            Set<Character> s = new HashSet<>();
            switch (family) {
                case C_LIKE:
                case PYTHON:
                case LUA:
                case SQL:
                case JSON:
                    s.add('"');
                    s.add('\'');
                    break;
                case SHELL:
                    s.add('\'');
                    s.add('"');
                    break;
                case XML:
                case CSS:
                case MARKDOWN:
                    s.add('"');
                    break;
                case YAML:
                case PROPERTIES:
                case TOML:
                case SMALI:
                case LOG:
                default:
                    // Aucun — pas d'auto-close de guillemets.
                    break;
            }
            return s;
        }

        /** Valeurs par défaut pour autoCloseBrackets selon la famille. */
        private static Set<Character> defaultBrackets(SyntaxFamily family) {
            Set<Character> s = new HashSet<>();
            switch (family) {
                case C_LIKE:
                case PYTHON:
                case LUA:
                case JSON:
                case SMALI:
                case SQL:
                    s.add('(');
                    s.add('{');
                    s.add('[');
                    break;
                case XML:
                case CSS:
                case MARKDOWN:
                case YAML:
                case SHELL:
                case PROPERTIES:
                case TOML:
                case LOG:
                default:
                    // Aucun — pas d'auto-close de crochets.
                    break;
            }
            return s;
        }

        /** Valeurs par défaut pour foldStrategy selon la famille. */
        private static FoldStrategy defaultFoldStrategy(SyntaxFamily family) {
            switch (family) {
                case C_LIKE:
                case JSON:
                case SQL:
                case SMALI:
                    return FoldStrategy.BRACE;
                case XML:
                case CSS:
                    return FoldStrategy.TAG;
                case PYTHON:
                case YAML:
                    return FoldStrategy.INDENT;
                case LUA:
                    return FoldStrategy.KEYWORD;
                case MARKDOWN:
                    return FoldStrategy.HEADING;
                case SHELL:
                case PROPERTIES:
                case TOML:
                case LOG:
                default:
                    return FoldStrategy.OFF;
            }
        }

        private static Set<String> normalize(String... values) {
            Set<String> out = new HashSet<>();
            if (values != null) {
                for (String v : values) {
                    if (v == null) continue;
                    String n = v.trim().toLowerCase(Locale.ROOT);
                    if (!n.isEmpty()) out.add(n);
                }
            }
            return out;
        }
    }
}

package jo.codeeditor.languages;

import jo.codeeditor.edit.CommentSyntax;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

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
 * {@code toggleBlockComment}), ses <b>traits d'édition</b> (Lot 2) —
 * caractères à auto-fermer, stratégie de pliage — et, depuis S5 (lot
 * 2, J6), sa <b>détection de lignes sticky</b> : chaque langue décide
 * seule de ses en-têtes de bloc via {@link #isBlockHeaderLine}. Depuis
 * ADR-002 (chantier C1), il porte aussi la {@link CompletionGate} de
 * la complétion, les {@link #snippets} intégrés (D5 « source unique »),
 * la {@link NewlineStrategy} de saut de ligne et ses
 * {@link NewlineTrigger}s d'indentation.</p>
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
 *     .newlineStrategy(NewlineStrategy.KOTLIN)      // flèche when, pas de label case
 *     .newlineTrigger(NewlineTrigger.onSuffix(":")) // +1 après « clé: »
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
    /**
     * Test d'en-tête de bloc fourni par l'hôte (S5, lot 2 J6) — décide
     * quelles lignes deviennent sticky quand elles sortent du viewport.
     * Null → tables par défaut de la famille (C_LIKE : accolade +
     * mot-clé de définition ; Python : deux-points + mot-clé ; autres :
     * aucun). Le prédicat reçoit la ligne BRUTE (avec indent) ; il est
     * appelé une fois par ligne et par scan — il peut coûter autant
     * qu'un test de préfixe mais pas plus qu'un scan de document.
     */
    public final Predicate<CharSequence> stickyHeaderTest;
    /**
     * Politique de la porte de contexte de complétion (ADR-002, C1).
     * Jamais null. Défaut par famille : LOG → {@link CompletionGate#OFF}
     * (texte non-code), XML → {@link CompletionGate#ALWAYS} (le serveur
     * LSP tranche lui-même les zones muettes), toutes les autres →
     * {@link CompletionGate#STANDARD} (porte calibrée pour les littéraux
     * C/Java). Un hôte surcharge via
     * {@link Builder#completionGate(CompletionGate)}.
     */
    public final CompletionGate completionGate;
    /**
     * Snippets de complétion intégrée (ADR-002, C1 — D5 « source
     * unique »). Liste immuable ordonnée, vide par défaut. Markdown
     * déclare la liste historique (« # », « ## », « - », clôtures de
     * blocs de code…) ; un hôte peut déclarer les siens par langage —
     * la complétion intégrée les propose au même titre que les
     * mots-clés, sans code dédié dans l'UI.
     */
    public final List<String> snippets;
    /**
     * Stratégie de saut de ligne intelligent (ADR-002, C1 — étape 3).
     * Jamais null. Défaut calculé depuis la famille : XML →
     * {@link NewlineStrategy#XML} (indentation structurelle), toutes
     * les autres → {@link NewlineStrategy#C_LIKE}. Le profil
     * {@code kotlin} déclare {@link NewlineStrategy#KOTLIN} (flèche
     * when, pas de label case) ; un hôte surcharge via
     * {@link Builder#newlineStrategy(NewlineStrategy)} — c'est la
     * voie pour un langage XML qui veut la newline C, ou un langage
     * maison Kotlin-like.
     */
    public final NewlineStrategy newlineStrategy;
    /**
     * Déclencheurs d'indentation +1 au saut de ligne (ADR-002, C1 —
     * étape 3). Liste immuable, vide par défaut. Python déclare le
     * déclencheur « : » avec mots-clés de bloc, YAML le « : » sans
     * prérequis, Shell et Lua « then »/« do ». Un hôte ajoute les
     * siens via {@link Builder#newlineTrigger(NewlineTrigger...)} —
     * la newline C-like les consulte avant son comportement par
     * défaut, sans code dédié.
     */
    public final List<NewlineTrigger> newlineTriggers;
    /**
     * Guillemets ouvrant les littéraux de chaîne pour le contexte
     * lexical (ADR-002, C1-é4) — consommés par les prédicats
     * {@code CodeContext.isInString}/isInStringOrComment et leurs
     * gardes d'auto-close/complétion. Défaut uniforme {@code "} et
     * {@code '} (comportement historique du scanner, y compris pour
     * XML — les valeurs d'attribut sont des chaînes). Distinct du
     * trait d'édition {@link #autoCloseQuotes} : un langage peut
     * déclarer une backtick comme chaîne sans l'auto-fermer.
     */
    public final Set<Character> stringQuotes;
    /**
     * Ouvreur du commentaire de documentation (ADR-002, C1-é4), ou
     * null quand le langage n'en distingue pas. Défaut famille
     * C_LIKE : {@code /**}. Consommé par le motif « ouverture de doc +
     * Entrée » de la newline C-like (continuation) ; servira de
     * source de vérité au tokéniseur DOC_COMMENT (chantiers C2/C3).
     */
    public final String docCommentStart;
    /** Fermeur du commentaire de documentation (souvent = le fermeur de bloc), ou null. */
    public final String docCommentEnd;
    /**
     * Préfixe de continuation inséré au saut de ligne dans un
     * commentaire de bloc / de documentation (ADR-002, C1-é4), ou
     * null quand la newline doit rester simple. Défaut : «&#160;*&#160;» quand
     * la syntaxe de bloc est C ({@code /* *&#47;}), null sinon (XML,
     * Lua…). Un langage maison le déclare ({@code ///}…).
     */
    public final String blockCommentContinuation;

    private LanguageProfile(String name, SyntaxFamily family,
            Set<String> aliases, Set<String> extensions,
            Set<String> keywords, CommentSyntax commentSyntax,
            Set<Character> autoCloseQuotes, Set<Character> autoCloseBrackets,
            FoldStrategy foldStrategy, Predicate<CharSequence> stickyHeaderTest,
            CompletionGate completionGate, List<String> snippets,
            NewlineStrategy newlineStrategy, List<NewlineTrigger> newlineTriggers,
            Set<Character> stringQuotes, String docCommentStart, String docCommentEnd,
            String blockCommentContinuation) {
        this.name = name;
        this.family = family;
        this.aliases = aliases;
        this.extensions = extensions;
        this.keywords = keywords;
        this.commentSyntax = commentSyntax;
        this.autoCloseQuotes = autoCloseQuotes;
        this.autoCloseBrackets = autoCloseBrackets;
        this.foldStrategy = foldStrategy;
        this.stickyHeaderTest = stickyHeaderTest;
        this.completionGate = completionGate;
        this.snippets = snippets;
        this.newlineStrategy = newlineStrategy;
        this.newlineTriggers = newlineTriggers;
        this.stringQuotes = stringQuotes;
        this.docCommentStart = docCommentStart;
        this.docCommentEnd = docCommentEnd;
        this.blockCommentContinuation = blockCommentContinuation;
    }

    /** Builder fluent — voir la javadoc de la classe pour un exemple d'utilisation. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * S5 (lot 2, J6) — vrai si ce langage peut avoir des lignes sticky
     * (test SPI fourni, ou famille à tables par défaut). Le détecteur
     * l'utilise comme garde PÉRIMÉTRIQUE : une langue sans en-têtes
     * déclarés ne paie même pas le scan.
     */
    public boolean hasStickyHeaders() {
        return stickyHeaderTest != null
                || family == SyntaxFamily.C_LIKE
                || family == SyntaxFamily.PYTHON;
    }

    /**
     * S5 (lot 2, J6) — vrai si {@code line} ouvre un bloc de code
     * (candidat sticky). Le test de l'hôte ({@code stickyHeaderTest}) a
     * priorité ; sinon les tables par défaut de la famille :
     * C_LIKE → ligne portant {@code {} ET commençant par un mot-clé de
     * définition (class/fun/void…) ; Python → ligne finissant par
     * {@code :} ET commençant par un mot-clé (def/class/if…) ; autres
     * familles → faux. La ligne est BRUTE (indent incluse) — les tables
     * par défaut trimment en interne.
     */
    public boolean isBlockHeaderLine(CharSequence line) {
        if (line == null) return false;
        if (stickyHeaderTest != null) {
            Boolean r = stickyHeaderTest.test(line);
            return r != null && r;
        }
        String trimmed = line.toString().trim();
        if (trimmed.isEmpty()) return false;
        if (family == SyntaxFamily.C_LIKE) {
            return line.toString().contains("{") && isBlockHeaderCLike(trimmed);
        }
        if (family == SyntaxFamily.PYTHON) {
            return trimmed.endsWith(":") && isBlockHeaderPython(trimmed);
        }
        return false;
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
        // S5 (lot 2, J6) : détection sticky déléguée au SPI langue.
        private Predicate<CharSequence> stickyHeaderTest;
        // ADR-002 (C1) : politique de la porte de complétion — donnée
        // par langage, défaut calculé dans build() depuis la famille.
        private CompletionGate completionGate;
        // ADR-002 (C1, D5) : snippets de complétion intégrée — même
        // table que les mots-clés (source unique).
        private final List<String> snippets = new ArrayList<>();
        // ADR-002 (C1-é3) : stratégie de saut de ligne + déclencheurs
        // d'indentation — données par langage, défauts calculés dans
        // build() depuis la famille.
        private NewlineStrategy newlineStrategy;
        private final List<NewlineTrigger> newlineTriggers = new ArrayList<>();
        // ADR-002 (C1-é4) : délimiteurs du contexte lexical + doc.
        private Set<Character> stringQuotes;
        private String docCommentStart;
        private String docCommentEnd;
        private boolean docCommentOverridden;
        private String blockCommentContinuation;

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

        /**
         * S5 (lot 2, J6) : détection des lignes sticky déléguée à la
         * langue. Le prédicat reçoit la ligne BRUTE et décide seule si
         * elle ouvre un bloc ; {@code null} (défaut) retombe sur les
         * tables internes de la famille (C_LIKE/Python — les autres
         * familles n'ont pas d'en-têtes). C'est la voie "honnête" pour
         * les langues inconnues de l'éditeur : enregistrer un profil
         * avec son test d'en-têtes suffit, sans toucher au détecteur.
         * Passer {@code l -> false} pour désactiver explicitement le
         * sticky d'une langue C_LIKE/Python.
         */
        public Builder stickyHeaderTest(Predicate<CharSequence> test) {
            this.stickyHeaderTest = test;
            return this;
        }

        /**
         * ADR-002 (C1) : politique de la porte de contexte de complétion
         * pour CE langage. Si non appelé, la valeur par défaut dépend de
         * la famille : LOG → {@link CompletionGate#OFF} (fichier de log,
         * pas de complétion) ; XML → {@link CompletionGate#ALWAYS}
         * (porte chaîne/commentaire écartée — le fournisseur LSP tranche
         * lui-même les zones muettes : texte, fermantes, commentaires
         * {@code <!--}, PI {@code <?}) ; toutes les autres familles →
         * {@link CompletionGate#STANDARD} (porte calibrée pour les
         * littéraux C/Java). Passer {@code null} retombe sur le défaut
         * de famille.
         */
        public Builder completionGate(CompletionGate gate) {
            this.completionGate = gate;
            return this;
        }

        /**
         * ADR-002 (C1, D5) : snippets de complétion intégrée pour CE
         * langage (même table que les mots-clés — source unique). La
         * complétion intégrée les propose filtrés par préfixe, au
         * format snippet (badge « s », sans tri particulier — l'ordre
         * déclaré est l'ordre de proposition). Les entrées nulles ou
         * vides sont ignorées. Vide par défaut.
         */
        public Builder snippets(String... entries) {
            if (entries != null) {
                for (String s : entries) {
                    if (s != null && !s.isEmpty()) snippets.add(s);
                }
            }
            return this;
        }

        /**
         * ADR-002 (C1-é3) : stratégie de saut de ligne pour CE
         * langage. Si non appelé, la valeur par défaut dépend de la
         * famille : XML → {@link NewlineStrategy#XML} (indentation
         * structurelle des balises, alignement d'attributs) ; toutes
         * les autres → {@link NewlineStrategy#C_LIKE}. Le profil
         * intégré {@code kotlin} déclare {@link NewlineStrategy#KOTLIN}.
         * Passer {@code null} retombe sur le défaut de famille.
         */
        public Builder newlineStrategy(NewlineStrategy strategy) {
            this.newlineStrategy = strategy;
            return this;
        }

        /**
         * ADR-002 (C1-é3) : déclencheurs d'indentation +1 au saut de
         * ligne pour CE langage (fin de ligne {@code :}, {@code then},
         * {@code do}…, avec optionnellement des préfixes de
         * mots-clés de bloc). Consultés par la newline C-like AVANT
         * son comportement par défaut, dans l'ordre déclaré. Les
         * entrées nulles sont ignorées. Vide par défaut.
         */
        public Builder newlineTrigger(NewlineTrigger... triggers) {
            if (triggers != null) {
                for (NewlineTrigger t : triggers) {
                    if (t != null) newlineTriggers.add(t);
                }
            }
            return this;
        }

        /**
         * ADR-002 (C1-é4) : guillemets ouvrant les littéraux pour le
         * CONTEXTE LEXICAL (scanner de chaînes des prédicats
         * d'édition et de complétion). Si non appelé : {@code "} et
         * {@code '} (comportement historique, uniforme). Passer une
         * liste vide pour un langage sans chaîne (le scanner ne verra
         * plus aucun littéral). Distinct de
         * {@link #autoCloseQuotes(char...)} (trait d'édition).
         */
        public Builder stringQuotes(char... quotes) {
            this.stringQuotes = new HashSet<>();
            for (char c : quotes) this.stringQuotes.add(c);
            return this;
        }

        /**
         * ADR-002 (C1-é4) : délimiteur de commentaire de documentation
         * du langage (ex. {@code /**}/{@code *&#47;} en C-like,
         * {@code ///} pour un langage maison). Si non appelé : défaut
         * de famille (C_LIKE → {@code /**}/{@code *&#47;}, autres →
         * null). Passer {@code (null, null)} pour désactiver
         * explicitement.
         */
        public Builder docComment(String start, String end) {
            this.docCommentStart = start;
            this.docCommentEnd = end;
            this.docCommentOverridden = true;
            return this;
        }

        /**
         * ADR-002 (C1-é4) : préfixe de continuation inséré au saut de
         * ligne dans un commentaire de bloc/doc (null = newline
         * simple). Défaut : «&#160;*&#160;» quand le bloc est C
         * ({@code /* *&#47;}), null sinon.
         */
        public Builder blockCommentContinuation(String prefix) {
            this.blockCommentContinuation = prefix;
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
            CompletionGate finalGate = completionGate != null
                    ? completionGate : defaultCompletionGate(family);
            List<String> finalSnippets = Collections.unmodifiableList(
                    new ArrayList<>(snippets));
            NewlineStrategy finalNewline = newlineStrategy != null
                    ? newlineStrategy : defaultNewlineStrategy(family);
            List<NewlineTrigger> finalTriggers = Collections.unmodifiableList(
                    new ArrayList<>(newlineTriggers));
            Set<Character> finalStringQuotes = stringQuotes != null
                    ? stringQuotes : defaultStringQuotes();
            String finalDocStart;
            String finalDocEnd;
            if (docCommentOverridden) {
                finalDocStart = docCommentStart;
                finalDocEnd = docCommentEnd;
            } else {
                finalDocStart = family == SyntaxFamily.C_LIKE ? "/**" : null;
                finalDocEnd = family == SyntaxFamily.C_LIKE ? "*/" : null;
            }
            String finalContinuation = blockCommentContinuation != null
                    ? blockCommentContinuation
                    : ("/*".equals(commentSyntax.blockStart) ? " * " : null);
            return new LanguageProfile(name, family,
                    Collections.unmodifiableSet(new HashSet<>(aliases)),
                    Collections.unmodifiableSet(new HashSet<>(extensions)),
                    Collections.unmodifiableSet(new HashSet<>(keywords)),
                    commentSyntax,
                    Collections.unmodifiableSet(new HashSet<>(finalQuotes)),
                    Collections.unmodifiableSet(new HashSet<>(finalBrackets)),
                    finalFold,
                    stickyHeaderTest,
                    finalGate,
                    finalSnippets,
                    finalNewline,
                    finalTriggers,
                    Collections.unmodifiableSet(new HashSet<>(finalStringQuotes)),
                    finalDocStart,
                    finalDocEnd,
                    finalContinuation);
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
                    // Lot 2/3 : Java, Kotlin, C/C++, Go, etc. ont
                    // des imports en haut de fichier qui méritent
                    // un pli. La stratégie IMPORTS détecte les
                    // imports ET les blocs d'accolades.
                    return FoldStrategy.IMPORTS;
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

        /** Défaut de porte de complétion selon la famille (ADR-002, C1). */
        private static CompletionGate defaultCompletionGate(SyntaxFamily family) {
            switch (family) {
                case LOG:
                    return CompletionGate.OFF;
                case XML:
                    return CompletionGate.ALWAYS;
                default:
                    return CompletionGate.STANDARD;
            }
        }

        /** Défaut de stratégie de saut de ligne selon la famille (ADR-002, C1-é3). */
        private static NewlineStrategy defaultNewlineStrategy(SyntaxFamily family) {
            return family == SyntaxFamily.XML
                    ? NewlineStrategy.XML : NewlineStrategy.C_LIKE;
        }

        /** Défaut uniforme des guillemets du contexte lexical (ADR-002, C1-é4). */
        private static Set<Character> defaultStringQuotes() {
            Set<Character> s = new HashSet<>();
            s.add('"');
            s.add('\'');
            return s;
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

    // ── S5 : tables d'en-têtes par défaut (déplacées du détecteur) ──

    /** En-têtes de classe/méthode en C_LIKE (déplacé de
     *  StickyLineDetector — comportement inchangé). */
    private static boolean isBlockHeaderCLike(String trimmed) {
        String[] keywords = {
            "class ", "interface ", "enum ", "public ", "private ",
            "protected ", "static ", "fun ", "void ", "int ", "long ",
            "double ", "float ", "boolean ", "char ", "byte ", "short ",
            "def ", "func ", "fn ", "function ", "var ", "val ",
            "async ", "suspend ", "override ", "open ", "abstract ",
            "final ", "internal ", "const ", "let ", "export ",
            "@interface "
        };
        for (String kw : keywords) {
            if (trimmed.startsWith(kw)) return true;
        }
        return false;
    }

    /** En-têtes def/class/if/for/while/with/try/except/else/finally
     *  (déplacé de StickyLineDetector — comportement inchangé). */
    private static boolean isBlockHeaderPython(String trimmed) {
        String[] keywords = {
            "def ", "class ", "if ", "elif ", "for ", "while ",
            "with ", "try:", "except ", "else:", "finally:",
            "async def "
        };
        for (String kw : keywords) {
            if (trimmed.startsWith(kw)) return true;
        }
        return false;
    }
}

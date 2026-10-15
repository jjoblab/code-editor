package jo.codeeditor.textmate;

import jo.codeeditor.textmate.internal.JsonLite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grammaire déclarative chargée depuis un fichier JSON (ADR-002, C3).
 *
 * <p>Format inspiré des grammaires TextMate, restreint au sous-ensemble
 * que le moteur Java de {@link GrammarTokenizer} sait exécuter avec
 * {@link java.util.regex} :</p>
 * <pre>{@code
 * {
 *   "scopeName": "source.mylang",
 *   "patterns": [ ... ],
 *   "repository": { "comments": {...}, "strings": {...} }
 * }
 * }</pre>
 *
 * <p>Chaque règle est l'une de :</p>
 * <ul>
 *   <li><b>match</b> — {@code {"match": "regex", "name": "portée",
 *       "captures": {"1": {"name": "…"}}}} ;</li>
 *   <li><b>begin/end</b> — {@code {"begin": "…", "end": "…",
 *       "name": "…", "contentName": "…", "beginCaptures": {…},
 *       "endCaptures": {…}, "patterns": [ … sous-règles … ]}} — les
 *       blocs multilignes (commentaires de bloc, chaînes à triple
 *       guillemet…) ; les backreferences {@code \1} de {@code end}
 *       sont résolues avec les groupes du {@code begin} correspondant ;</li>
 *   <li><b>include</b> — {@code {"include": "#règle"}} (repository de
 *       la même grammaire), {@code "$self"} (patterns racine) ou
 *       {@code "$base"} (même sémantique, grammaire sans inclusion
 *       externe).</li>
 * </ul>
 *
 * <p>Les expressions doivent être compatibles
 * {@link java.util.regex} (pas de dialecte Oniguruma complet — les
 * grammaires embarquées du dépôt respectent ce sous-ensemble ; une
 * grammaire tierce avec des constructions exotiques échoue au
 * chargement avec un message explicite, jamais en cours de
 * coloration).</p>
 *
 * <p>Immuable et thread-safe une fois construite.</p>
 */
public final class TextMateGrammar {

    /** Portée racine (ex. {@code source.csharp}) — informative. */
    public final String scopeName;
    /** Nom lisible (ex. {@code C#}) — informatif. */
    public final String name;
    /** Règles racine (l'ordre détermine la priorité à position égale). */
    public final List<Rule> patterns;
    /** Règles nommées du repository, adressées par {@code include "#…"}. */
    public final Map<String, Rule> repository;

    private TextMateGrammar(String scopeName, String name,
            List<Rule> patterns, Map<String, Rule> repository) {
        this.scopeName = scopeName;
        this.name = name;
        this.patterns = List.copyOf(patterns);
        this.repository = Map.copyOf(repository);
    }

    /**
     * Parse une source de grammaire JSON/JSONC. Leve une
     * {@link GrammarException} (avec position) sur grammaire invalide
     * ou regex incompilable — au chargement, jamais en coloration.
     */
    public static TextMateGrammar parse(String source) {
        Map<String, Object> root;
        try {
            root = JsonLite.parseObject(source);
        } catch (JsonLite.JsonLiteException e) {
            throw new GrammarException("grammaire illisible : " + e.getMessage(), e);
        }
        String scopeName = JsonLite.str(root.get("scopeName"));
        if (scopeName == null || scopeName.isBlank()) {
            throw new GrammarException("scopeName manquant");
        }
        String name = JsonLite.str(root.get("name"));
        Map<String, Rule> repository = new LinkedHashMap<>();
        Map<String, Object> repo = JsonLite.obj(root.get("repository"));
        for (Map.Entry<String, Object> e : repo.entrySet()) {
            repository.put(e.getKey(),
                    new RuleParser(JsonLite.obj(e.getValue()), e.getKey()).parse());
        }
        List<Rule> patterns = new ArrayList<>();
        for (Object o : JsonLite.list(root.get("patterns"))) {
            patterns.add(new RuleParser(JsonLite.obj(o), null).parse());
        }
        return new TextMateGrammar(scopeName, name, patterns, repository);
    }

    /** Échec de chargement d'une grammaire (source ou regex invalide). */
    public static final class GrammarException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** @param message diagnostic avec règle fautive */
        public GrammarException(String message) {
            super(message);
        }

        /** @param message diagnostic ; @param cause cause technique */
        public GrammarException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Modèle de règle ──────────────────────────────────────────

    /**
     * Règle compilée. Une règle est soit un <b>match</b>, soit un
     * <b>begin/end</b>, soit un <b>include</b> (référence textuelle
     * {@code #règle}/{@code $self}/{@code $base}, résolue par le moteur
     * au moment de la tokénisation).
     */
    public static final class Rule {
        /** Nom déclaratif de la règle (repository) pour les diagnostics. */
        public final String ruleName;
        /** Regex de match simple, ou null pour begin/end / include. */
        public final Pattern match;
        /** Regex d'ouverture de bloc, ou null. */
        public final Pattern begin;
        /** Regex de fermeture de bloc (backrefs \1..n résolues au vol), ou null. */
        public final String end;
        /** Portée de la zone matched (match) ou de la construction (begin/end). */
        public final String name;
        /** Portée du CONTENU entre begin et end (ex. string.quoted). */
        public final String contentName;
        /** Captures nommées du match (index → portée). */
        public final Map<Integer, String> captures;
        /** Captures du begin (index → portée). */
        public final Map<Integer, String> beginCaptures;
        /** Captures du end (index → portée). */
        public final Map<Integer, String> endCaptures;
        /** Sous-règles actives entre begin et end. */
        public final List<Rule> patterns;
        /** Référence include ({@code #règle}/{@code $self}/{@code $base}), ou null. */
        public final String include;

        Rule(String ruleName, Pattern match, Pattern begin, String end, String name,
                String contentName, Map<Integer, String> captures,
                Map<Integer, String> beginCaptures, Map<Integer, String> endCaptures,
                List<Rule> patterns, String include) {
            this.ruleName = ruleName;
            this.match = match;
            this.begin = begin;
            this.end = end;
            this.name = name;
            this.contentName = contentName;
            this.captures = Map.copyOf(captures);
            this.beginCaptures = Map.copyOf(beginCaptures);
            this.endCaptures = Map.copyOf(endCaptures);
            this.patterns = List.copyOf(patterns);
            this.include = include;
        }

        @Override
        public String toString() {
            return "Rule(" + (ruleName != null ? ruleName : "?")
                    + (match != null ? ", match" : begin != null ? ", begin/end"
                            : include != null ? ", include " + include : ", groupe")
                    + ")";
        }
    }

    // ── Parseur de règles ────────────────────────────────────────

    private static final class RuleParser {
        private final Map<String, Object> src;
        private final String ruleName;

        RuleParser(Map<String, Object> src, String ruleName) {
            this.src = src;
            this.ruleName = ruleName;
        }

        Rule parse() {
            String include = JsonLite.str(src.get("include"));
            if (include != null) {
                // Inclusion externe non supportée (source.xxx autre
                // grammaire) : signalée au chargement, pas ignorée en
                // silence (une grammaire qui compte dessus produirait
                // une coloration fausse).
                if (include.startsWith("#") || "$self".equals(include)
                        || "$base".equals(include)) {
                    return new Rule(ruleName, null, null, null, null, null,
                            Map.of(), Map.of(), Map.of(), List.of(), include);
                }
                throw new GrammarException("include externe non supporté ("
                        + include + ") — règle " + ruleName);
            }
            String match = JsonLite.str(src.get("match"));
            String begin = JsonLite.str(src.get("begin"));
            String end = JsonLite.str(src.get("end"));
            String name = JsonLite.str(src.get("name"));
            String contentName = JsonLite.str(src.get("contentName"));
            if (match != null) {
                return new Rule(ruleName, compile(match, ruleName), null, null, name,
                        null, parseCaptures(src.get("captures"), ruleName),
                        Map.of(), Map.of(), List.of(), null);
            }
            if (begin != null || end != null) {
                if (begin == null || end == null) {
                    throw new GrammarException("règle begin/end incomplète : "
                            + ruleName + " (begin=" + begin + ", end=" + end + ")");
                }
                List<Rule> sub = new ArrayList<>();
                for (Object o : JsonLite.list(src.get("patterns"))) {
                    sub.add(new RuleParser(JsonLite.obj(o), ruleName + ".patterns").parse());
                }
                return new Rule(ruleName, null, compile(begin, ruleName), end, name,
                        contentName, Map.of(),
                        parseCaptures(src.get("beginCaptures"), ruleName),
                        parseCaptures(src.get("endCaptures"), ruleName),
                        sub, null);
            }
            // Groupe de patterns sans match (règle conteneur).
            List<Rule> sub = new ArrayList<>();
            for (Object o : JsonLite.list(src.get("patterns"))) {
                sub.add(new RuleParser(JsonLite.obj(o), ruleName + ".patterns").parse());
            }
            return new Rule(ruleName, null, null, null, name, null,
                    Map.of(), Map.of(), Map.of(), sub, null);
        }

        private static Pattern compile(String regex, String ruleName) {
            try {
                return Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new GrammarException("regex invalide dans la règle " + ruleName
                        + " : « " + regex + " » (" + e.getMessage() + ")", e);
            }
        }

        private static Map<Integer, String> parseCaptures(Object value, String ruleName) {
            Map<Integer, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : JsonLite.obj(value).entrySet()) {
                try {
                    int index = Integer.parseInt(e.getKey().trim());
                    String scope = JsonLite.str(JsonLite.obj(e.getValue()).get("name"));
                    if (scope != null) out.put(index, scope);
                } catch (NumberFormatException nfe) {
                    throw new GrammarException("index de capture invalide ("
                            + e.getKey() + ") dans la règle " + ruleName);
                }
            }
            return out;
        }
    }

}

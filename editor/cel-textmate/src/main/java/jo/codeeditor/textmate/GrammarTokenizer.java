package jo.codeeditor.textmate;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.textmate.TextMateGrammar.Rule;

import jo.codeeditor.textmate.regex.CompiledRegex;
import jo.codeeditor.textmate.regex.DialectNormalizer;
import jo.codeeditor.textmate.regex.EncodedLine;
import jo.codeeditor.textmate.regex.LineMatcher;
import jo.codeeditor.textmate.regex.MatchBudget;
import jo.codeeditor.textmate.regex.RegexCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Moteur d'application d'une {@link TextMateGrammar} ligne par ligne
 * (ADR-002, C3) — le contrat du crochet
 * {@code SyntaxHighlighter.setTextMateTokenizer}.
 *
 * <h2>Sémantique</h2>
 * <ul>
 *   <li><b>Leftmost-first</b> : le scan avance position par position ;
 *       à chaque position, la première règle (ordre de déclaration)
 *       qui matche ANCRÉE là l'emporte — le premier candidat trouvé
 *       EST le leftmost, et à position égale l'ordre de déclaration
 *       tranche (sémantique TextMate).</li>
 *   <li><b>Matchs de largeur nulle</b> : les fermantes en lookahead
 *       ({@code (?=\))} python, {@code ($)}) DÉPILENT et retentent à
 *       la MÊME position (le parent peut consommer) ; les ouvrantes en
 *       lookahead (wrappers des grammaires réelles) EMPILENT et
 *       retentent aussi —
 *       une garde anti-récursion par (règle, position) et un
 *       disjoncteur bornent les grammaires dégénérées.</li>
 *   <li><b>Pile de contextes</b> : une règle begin/end non fermée en
 *       fin de ligne empile un cadre ; les lignes suivantes
 *       recommencent avec les sous-règles du sommet (et la recherche
 *       de son {@code end} en candidate prioritaire). La pile se
 *       propage entre les lignes via l'{@code exitState} opaque du
 *       contrat.</li>
 *   <li><b>« \n » virtuel</b> : la ligne est prolongée d'un terminateur
 *       virtuel invisible — les fermantes {@code \n}/{@code $} des
 *       règles de ligne (commentaire java) MATCHENT en fin de ligne et
 *       referment leur cadre, sans que le terminateur ne fuite dans
 *       les spans.</li>
 *   <li><b>Idiome {@code (?!\G)}</b> : les wrappers « blancs de tête +
 *       lookahead » des grammaires réelles (java/html/lua) portent leur
 *       garde par le CADRE (la fermante ne matche qu'au-delà du début
 *       du contenu) — en java.util.regex, {@code \G} vaut toujours la
 *       position courante et l'idiome ne matcherait jamais.</li>
 *   <li><b>Portée du contenu</b> : le texte entre begin et end reçoit
 *       le {@code contentName} de la règle (une chaîne multiligne non
 *       fermée colore toute la ligne suivante en STRING).</li>
 *   <li><b>Backreferences</b> : les {@code \\1} du {@code end} sont
 *       résolues avec les groupes du {@code begin} correspondant
 *       (chaîne triple Python, heredoc…) — compilées UNE fois à
 *       l'ouverture du cadre.</li>
 * </ul>
 *
 * <h2>Modèle d'état (contrat TextMateTokenizer)</h2>
 * <ul>
 *   <li>{@code entryState == 0} → pile vide (début de fichier) ;</li>
 *   <li>{@code entryState > 0} → identifiant opaque de pile,
 *       précédemment renvoyé comme {@code exitState} par ce même
 *       moteur sur la même grammaire. Les piles sont <b>internées</b>
 *       par contenu : re-tokéniser la même ligne avec le même
 *       entryState produit le même exitState — la condition de
 *       stabilité du contrat, qui arrête la re-tokénisation de
 *       l'aval.</li>
 * </ul>
 *
 * <p>Thread-safe : le seul état partagé est la table d'interning
 * (synchronisée) ; le moteur peut être appelé depuis le thread de
 * restyle.</p>
 */
public final class GrammarTokenizer {

    /**
     * Garde anti-boucle (plan 10, D4) : plafond d'étapes de largeur nulle
     * consécutives à la même position avant d'avancer d'un caractère —
     * valeur calée au banc (P3), sans nouveau plan.
     */
    private static final int MAX_ZERO_WIDTH_STEPS = 50;

    /**
     * Garde anti-interruptions (plan 10, D4 — complément C2) : au-delà de
     * ce nombre d'essais interrompus par le budget POUR UN MÊME MOTIF sur
     * UNE ligne, le motif est sauté pour le reste de la ligne — un motif
     * adversarial ne consomme pas « positions × budget » (il échouait de
     * toute façon : aucune régression de rendu n'est possible).
     */
    private static final int MAX_INTERRUPTIONS_PER_LINE = 3;

    /** Fermante ne matchant jamais : les cadres à clause while (sans
     * end) n'ont PAS de fermante en ligne — la clause est une garde de
     * début de ligne (C3-quinquies). Natif dans le dialecte ((?!) =
     * lookahead vide négatif) : la traduction spécifique disparaît
     * avec le traducteur (plan 10). */
    private static final CompiledRegex NEVER = CompiledRegex.compile("(?!)");

    /** Cadre de pile : règle begin/end ouverte, end compilé (backrefs
     * résolues). */
    private static final class Frame {
        final Rule rule;
        /** Fermante résolue (backrefs substituées par les groupes du
         * begin) — source normalisée = clé de {@link RegexCache} et
         * identité du cadre (l'égalité compare les SOURCES, pas les
         * objets compilés). */
        final CompiledRegex endPattern;
        /** Position de fin du begin (début du contenu) — ancre de
         * l'idiome {@code (?!\G)} : la fermante ne matche qu'une fois
         * le scan DÉPASSÉ ce point (idiome des wrappers
         * « blancs de tête + lookahead », java/html/lua). */
        final int contentStart;
        /** Vrai quand le end source commence par {@code (?!\G)} (idiome
         * wrapper) — le préfixe est retiré du pattern compilé, la
         * garde sémantique est portée par le cadre. */
        final boolean notGEnd;
        /** Clause while compilée (C3-quinquies) : garde de
         * CONTINUATION DE LIGNE, évaluée au DÉBUT de chaque ligne
         * suivante — null pour les cadres à fermante. Un cadre while
         * n'a PAS de fermante en ligne (endPattern ne matche jamais),
         * il se poursuit tant que le while matche en tête de ligne et
         * se dépile sinon (markdown : bloc de code clôturé par la
         * ligne de clôture, citation poursuivie par son préfixe). */
        final CompiledRegex whilePattern;

        Frame(Rule rule, CompiledRegex endPattern, CompiledRegex whilePattern,
                int contentStart, boolean notGEnd) {
            this.rule = rule;
            this.endPattern = endPattern;
            this.whilePattern = whilePattern;
            this.contentStart = contentStart;
            this.notGEnd = notGEnd;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Frame)) return false;
            Frame f = (Frame) o;
            // Identité de règle (immuable, partagée) + end compilé
            // équivalent (même source résolue — comparaison par
            // pattern().source(), les regex compilées n'implantent
            // pas equals) + même ancre de contenu (l'idiome (?!\G)
            // rend deux poussées de la même règle à des positions
            // différentes SÉMANTIQUEMENT distinctes).
            return rule == f.rule
                    && contentStart == f.contentStart
                    && notGEnd == f.notGEnd
                    && Objects.equals(endPattern.source(), f.endPattern.source())
                    && Objects.equals(whilePattern == null ? null : whilePattern.source(),
                            f.whilePattern == null ? null : f.whilePattern.source());
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * System.identityHashCode(rule) + contentStart)
                    + (notGEnd ? 1 : 0)) + endPattern.source().hashCode()
                    + (whilePattern == null ? 0 : whilePattern.source().hashCode());
        }
    }

    /** Interning des piles : contenu → identifiant stable. */
    private final Map<List<Frame>, Integer> pileIds = new HashMap<>();
    private final Map<Integer, List<Frame>> pilesById = new HashMap<>();
    private int nextId = 1;

    /** Tokénise une ligne avec la grammaire (entrée/sortie d'état du contrat). */
    public StyledLine tokenize(TextMateGrammar grammar, String line, int entryState) {
        return tokenize(grammar, line, entryState, Map.of());
    }

    /**
     * Tokénise avec registre externe et budget d'essai PAR DÉFAUT
     * ({@link jo.codeeditor.textmate.regex.MatchBudget#MATCH_TIMEOUT_NS}).
     */
    public StyledLine tokenize(TextMateGrammar grammar, String line, int entryState,
            Map<String, TextMateGrammar> externals) {
        return tokenize(grammar, line, entryState, externals,
                jo.codeeditor.textmate.regex.MatchBudget.MATCH_TIMEOUT_NS);
    }

    /**
     * Tokénise une ligne avec registre externe et budget d'essai
     * EXPLICITE (plan 11, D2) : la demande interactive au fil UI passe
     * un budget réduit, la passe d'arrière-plan garde le budget plein.
     * Le repli en non-correspondance reste identique — seule la durée
     * maximale d'un essai change.
     */
    public StyledLine tokenize(TextMateGrammar grammar, String line, int entryState,
            Map<String, TextMateGrammar> externals, long timeoutNs) {
        List<Frame> stack = resolve(entryState);
        List<LineSpan> spans = new ArrayList<>();
        tokenizeLine(new Ctx(grammar, line, externals, timeoutNs), stack, spans);
        // Fusion des spans adjacents de même type (le rendu n'a pas
        // besoin de la granularité règle par règle).
        return new StyledLine(merge(spans), entryState, intern(stack));
    }

    // ── Résolution d'états ───────────────────────────────────────

    private synchronized List<Frame> resolve(int entryState) {
        if (entryState == 0) return new ArrayList<>();
        List<Frame> pile = pilesById.get(entryState);
        // État inconnu (grammaire changée, ré-enregistrement) : pile
        // vide — repli sûr plutôt qu'une exception en coloration.
        return pile != null ? new ArrayList<>(pile) : new ArrayList<>();
    }

    private synchronized int intern(List<Frame> pile) {
        if (pile.isEmpty()) return 0;
        Integer existing = pileIds.get(pile);
        if (existing != null) return existing;
        List<Frame> canonical = List.copyOf(pile);
        int id = nextId++;
        pileIds.put(canonical, id);
        pilesById.put(id, canonical);
        return id;
    }

    // ── Moteur d'une ligne ───────────────────────────────────────

    /**
     * Contexte d'une ligne : matchers RÉUTILISÉS (un par motif —
     * l'allocation d'un matcher par règle×position dominait le coût
     * des grammaires réelles) et includes résolus MÉMOÏSÉS (la
     * résolution allouait une règle synthétique à chaque tentative).
     * Chaque essai est budgété par la couture (plan 10, règle d'or 5)
     * ; la sémantique « matcher frais » (lookbehind sur le contexte
     * gauche, ^ et \A en tête de ligne, \G à la position d'essai) est
     * native dans le moteur.
     */
    private static final class Ctx {
        final TextMateGrammar grammar;
        final String line;
        /** Ligne + « \n » VIRTUEL encodée pour le moteur (plan 10) :
        * les fermantes « \n »/« $ » des règles de ligne MATCHENT en fin
        * de ligne sans fuiter dans les spans (positions émises
         * BORNÉES à la ligne réelle). */
        final EncodedLine encoded;
        final Map<String, TextMateGrammar> externals;
        /** Identité des motifs compilés (partagés) : l'IdentitéHashMap
         * évite le recalcul du hash profond à chaque lookup. */
        final Map<CompiledRegex, LineMatcher> matchers = new java.util.IdentityHashMap<>();
        final Map<Rule, Rule> resolvedIncludes = new java.util.IdentityHashMap<>();
        /** Règles begin DE LARGEUR NULLE déjà poussées à chaque
         * position (BitSet par règle, index = position) : un lookahead
         * begin qui vient de pousser un cadre ne doit pas se
         * ré-empiler à la MÊME position (récursion dégénérée des
         * grammaires tierces à wrapper lookahead — java class, python
         * docstring). */
        final Map<Rule, java.util.BitSet> emptyPushedAt = new java.util.IdentityHashMap<>();
        /** Essais interrompus par le budget, par motif (garde
         * anti-interruptions D4) : au seuil, le motif est sauté pour
         * le reste de la ligne (il échouait de toute façon). */
        final Map<CompiledRegex, int[]> interruptions = new java.util.IdentityHashMap<>();
        /** Motifs sourds pour le reste de la ligne (garde D4). */
        final Map<CompiledRegex, Boolean> sourds = new java.util.IdentityHashMap<>();
        /** Fin du DERNIER begin de la ligne (C3-sexties) : l'ancre
         * réelle des idiomes {@code \G}/{@code (?!\G)} en tête de
         * begin — java.util.regex évalue {@code \G} au début de
         * région (reset+region), i.e. TOUJOURS la position courante.
         * {@code -1} = aucun begin cette ligne (les idiomes en tête
         * ne peuvent pas matcher au démarrage d'une ligne). */
        int lastBeginEnd = -1;

        /** Budget d'essai de CE contexte (plan 11, D2) — réduit au fil
         * UI par l'appelant interactif, plein en passe d'arrière-plan. */
        final long timeoutNs;

        Ctx(TextMateGrammar grammar, String line,
                Map<String, TextMateGrammar> externals, long timeoutNs) {
            this.grammar = grammar;
            this.line = line;
            this.encoded = EncodedLine.of(line);
            this.externals = externals;
            this.timeoutNs = timeoutNs;
        }

        LineMatcher matcher(CompiledRegex pattern) {
            LineMatcher m = matchers.get(pattern);
            if (m == null) {
                m = pattern.matcher(encoded, timeoutNs);
                matchers.put(pattern, m);
            }
            return m;
        }

        /** Match ANCRÉ à pos : équivalent sémantique du matcher frais
         * lancé à pos sur la ligne (plus le « \n » virtuel), chaque
         * essai budgété par la couture (règle d'or 5). Un motif sourdu
         * (garde anti-interruptions D4) ne tente plus : il échouait de
         * toute façon, et « positions × budget » ne doit pas partir en
         * fumée sur un motif adversarial. */
        boolean matchAt(CompiledRegex pattern, int pos, boolean allowEmpty) {
            if (sourds.containsKey(pattern)) return false;
            LineMatcher m = matcher(pattern);
            if (!m.matchAt(pos)) {
                if (m.wasInterrupted()) {
                    int[] n = interruptions.get(pattern);
                    if (n == null) { n = new int[1]; interruptions.put(pattern, n); }
                    if (++n[0] >= MAX_INTERRUPTIONS_PER_LINE) {
                        sourds.put(pattern, Boolean.TRUE);
                    }
                }
                return false;
            }
            return allowEmpty || m.end() > m.start();
        }

        /** Une règle begin de largeur nulle a-t-elle déjà poussé à pos ? */
        boolean pushedEmptyAt(Rule rule, int pos) {
            java.util.BitSet bs = emptyPushedAt.get(rule);
            return bs != null && bs.get(pos);
        }

        /** Marque la poussée vide (rule, pos) — dédoublonnage par ligne. */
        void markEmptyPush(Rule rule, int pos) {
            emptyPushedAt.computeIfAbsent(rule, r -> new java.util.BitSet()).set(pos);
        }
    }

    /**
     * Boucle d'une ligne. Le stepping avance position par position ;
     * à chaque pos, la première règle (ordre de déclaration) qui
     * matche ANCRÉE à pos l'emporte. Les matchs DE LARGEUR NULLE
     * (begin/end en lookahead — sémantique TextMate : java
     * {@code (?={\s*class...)} , python {@code (?=\'''})}) ne
     * consomment RIEN : une fermante vide DÉPILE et RETENTE À LA MÊME
     * POSITION (le parent peut alors consommer — ex. le « ) » vu par
     * l'{@code end} du cadre function-call, le « } » du corps de
     * classe) ; une ouvrante vide EMPILE et retente aussi (les
     * sous-règles du wrapper prennent le relais). Un disjoncteur
     * borne les oscillations dégénérées pousser/dépiler sans
     * consommation.
     */
    private void tokenizeLine(Ctx ctx, List<Frame> stack, List<LineSpan> spans) {
        String line = ctx.line;
        int limit = line.length();
        int pos = 0;
        // Début du run de texte non encore stylé (le repli de contenu
        // couvre les trous entre règles).
        int textStart = 0;
        // Clauses while (C3-quinquies) : au DÉBUT de ligne, le sommet
        // de pile porteur d'un while est testé — la clause est une
        // GARDE DE CONTINUATION (sémantique TextMate), pas une
        // fermante en ligne. Échec → le cadre ne s'applique PAS à
        // cette ligne (dépilement, en cascade vers le nouveau sommet :
        // la ligne de clôture ``` dépile le bloc de code embarqué, la
        // fermante du bloc parent la matche alors normalement).
        // Succès NON VIDE → le préfixe de continuation est consommé
        // (le « > » d'une citation) et émis avec la portée de la règle
        // (whileCaptures absent des grammaires réelles — markdown de
        // grammaires réelles : 64 while, 0 whileCaptures — simplification
        // documentée).
        while (!stack.isEmpty()) {
            Frame top = stack.get(stack.size() - 1);
            if (top.whilePattern == null) break;
            if (ctx.matchAt(top.whilePattern, 0, true)) {
                LineMatcher m = ctx.matcher(top.whilePattern);
                if (m.end() > 0) {
                    emitWithCaptures(m, Map.of(), top.rule.name, spans, limit);
                    pos = Math.min(m.end(), limit);
                    textStart = pos;
                }
                break;
            }
            stack.remove(stack.size() - 1);
        }
        // Étapes de largeur nulle consécutives (disjoncteur).
        int zeroWidth = 0;
        // La boucle couvre AUSSI pos == limit : le « \n » virtuel s'y
        // trouve — les fermantes « \n »/« $ » des règles de ligne
        // DOIVENT pouvoir matcher, sinon leur cadre ne se referme
        // jamais et le contexte avale le reste du fichier.
        while (pos <= limit) {
            Step step = nextStep(ctx, pos, stack);
            if (step == null) {
                pos++;
                zeroWidth = 0;
                continue;
            }
            // Le run non stylé depuis textStart reçoit la portée de
            // contenu du sommet (ou PLAIN à la racine) — et le run est
            // CONSOMMÉ par l'émission (une étape vide qui retente à la
            // même position ne doit pas le ré-émettre).
            if (pos > textStart) {
                spans.add(new LineSpan(textStart, pos, contentType(stack)));
                textStart = pos;
            }
            int after = apply(step, stack, spans, limit);
            if (!step.isEnd && step.rule.begin != null) {
                // Ancre des idiomes \G en tête de begin (C3-sexties) :
                // la fin du begin vient d'être consommée — c'est ELLE
                // que \G désigne au prochain tour (bornée à la ligne,
                // le « \n » virtuel n'ancre rien).
                ctx.lastBeginEnd = Math.min(after, limit);
            }
            if (after > step.start) {
                // Match consommateur : avancer et reprendre le run.
                pos = Math.min(after, limit + 1);
                textStart = Math.min(after, limit);
                zeroWidth = 0;
            } else {
                // Match vide (poussé ou dépile) : RETENTER à la même
                // position — chaque étape a changé la pile (progression
                // garantie). Disjoncteur : au-delà de MAX_ZERO_WIDTH_STEPS
                // étapes vides consécutives, avancer d'un (grammaire dégénérée).
                if (zeroWidth++ < MAX_ZERO_WIDTH_STEPS) {
                    pos = step.start;
                } else {
                    pos = step.start + 1;
                    zeroWidth = 0;
                }
            }
            if (pos > limit) break;
        }
        // Le reliquat de ligne reçoit la portée de contenu du sommet
        // (ou PLAIN à la racine).
        if (textStart < limit) {
            spans.add(new LineSpan(textStart, limit, contentType(stack)));
        }
    }

    /**
     * La première règle (ordre de déclaration) qui matche À pos : le
     * stepping avance caractère par caractère — le premier candidat
     * trouvé EST le leftmost, et à position égale l'ordre de
     * déclaration tranche (sémantique TextMate). Le end du sommet de
     * pile est essayé EN PREMIER : il gagne les égalités.
     */
    private Step nextStep(Ctx ctx, int pos, List<Frame> stack) {
        if (!stack.isEmpty()) {
            Frame top = stack.get(stack.size() - 1);
            // Idiome (?!\G) : la fermante ne matche qu'au-delà du début
            // du contenu (en TextMate, \G = fin du match précédent — la
            // valeur au moment de l'évaluation de la fermante ; en
            // java.util.regex \G vaut TOUJOURS la position courante,
            // l'idiome ne matcherait jamais). Le reste du end (souvent
            // vide) compile et matche normalement après la garde.
            if (top.notGEnd
                    ? pos > top.contentStart && ctx.matchAt(top.endPattern, pos, true)
                    : ctx.matchAt(top.endPattern, pos, true)) {
                return new Step(ctx.matcher(top.endPattern), top.rule, true,
                        ctx.line.length());
            }
            for (Rule sub : top.rule.patterns) {
                Step s = tryRule(sub, ctx, pos, 0);
                if (s != null) return s;
            }
            return null;
        }
        for (Rule root : ctx.grammar.patterns) {
            Step s = tryRule(root, ctx, pos, 0);
            if (s != null) return s;
        }
        return null;
    }

    /** Évalue une règle (et ses includes) à la recherche d'un match À pos. */
    private Step tryRule(Rule rule, Ctx ctx, int pos, int depth) {
        if (rule.include != null) {
            Rule resolved = resolveInclude(rule, ctx);
            if (resolved == null || resolved == rule) return null; // absent ou cycle
            // Garde de profondeur : une inclusion externe mutuelle ne
            // doit jamais provoquer de récursion infinie.
            if (depth >= 50) return null;
            return tryRule(resolved, ctx, pos, depth + 1);
        }
        if (rule.match != null) {
            if (ctx.matchAt(rule.match, pos, false)) {
                return new Step(ctx.matcher(rule.match), rule, false,
                        ctx.line.length());
            }
            return null;
        }
        if (rule.begin != null) {
            // Dédoublonnage : un begin de largeur nulle déjà poussé à
            // cette position ne se ré-empile pas (récursion dégénérée
            // des wrappers lookahead des grammaires réelles).
            boolean empty = ctx.pushedEmptyAt(rule, pos);
            // Idiomes \G / (?!\G) en tête de begin (C3-sexties) :
            // la garde d'ancre est portée par le moteur — \G ne
            // matche qu'à la fin du DERNIER begin, (?!\G) partout
            // ailleurs (la tête de balise \G d'un wrapper html ne se
            // ré-empile plus après son « > », le cadre du contenu
            // (?!\G) s'ouvre enfin).
            if (rule.beginG && pos != ctx.lastBeginEnd) return null;
            if (rule.beginNotG && pos == ctx.lastBeginEnd) return null;
            if (!empty && ctx.matchAt(rule.begin, pos, true)) {
                if (ctx.matcher(rule.begin).end() == ctx.matcher(rule.begin).start()) {
                    ctx.markEmptyPush(rule, pos);
                }
                return new Step(ctx.matcher(rule.begin), rule, false,
                        ctx.line.length());
            }
            return null;
        }
        // Règle conteneur (groupe de patterns) : le premier enfant qui
        // matche à pos — l'ordre de déclaration tranche (à position
        // égale, le premier déclaré gagne, sémantique TextMate).
        for (Rule sub : rule.patterns) {
            Step s = tryRule(sub, ctx, pos, depth);
            if (s != null) return s;
        }
        return null;
    }

    /** Include résolu MÉMOÏSÉ par ligne (l'allocation d'une règle
     * synthétique par tentative dominait le coût des grammaires
     * réelles, java et python notamment). */
    private Rule resolveInclude(Rule rule, Ctx ctx) {
        Rule cached = ctx.resolvedIncludes.get(rule);
        if (cached != null) return cached;
        // Grammaire propriétaire de la règle (C3-quinquies) : les #ref
        // d'une règle INVITÉE (markdown → source.java) se résolvent
        // dans le repository de l'invitée — l'hôte ne les connaît pas.
        TextMateGrammar owner = rule.owner != null ? rule.owner : ctx.grammar;
        Rule resolved = resolveIncludeUncached(rule, owner, ctx.externals);
        ctx.resolvedIncludes.put(rule, resolved);
        return resolved;
    }

    private Rule resolveIncludeUncached(Rule rule, TextMateGrammar grammar,
            Map<String, TextMateGrammar> externals) {
        String ref = rule.include;
        if ("$self".equals(ref) || "$base".equals(ref)) {
            // Règle conteneur synthétique exposant les patterns racine.
            return new Rule("$" + ref, null, null, null, null, null, null,
                    Map.of(), Map.of(), Map.of(), grammar.patterns, null, grammar);
        }
        if (ref.startsWith("#")) {
            Rule target = grammar.repository.get(ref.substring(1));
            if (target == rule) return rule; // self-include → stop
            return target;
        }
        // Include externe (C3-bis) : résolu par le registre — absent,
        // la règle ne matche rien (dégradation documentée).
        TextMateGrammar foreign = externals.get(ref);
        if (foreign == null) return null;
        return new Rule(ref, null, null, null, null, null, null,
                Map.of(), Map.of(), Map.of(), foreign.patterns, null, foreign);
    }

    /** Applique le step : produit les spans et met à jour la pile.
     * Toutes les positions sont BORNÉES à la ligne réelle (le « \n »
     * virtuel du contexte ne doit jamais fuiter dans les spans). */
    private int apply(Step step, List<Frame> stack, List<LineSpan> spans,
            int limit) {
        Rule rule = step.rule;
        if (step.isEnd) {
            // Fermeture du sommet de pile : span du end (endCaptures)
            // + dépilement.
            Frame top = stack.remove(stack.size() - 1);
            emitWithCaptures(step.matcher, top.rule.endCaptures, top.rule.name,
                    spans, limit);
            return step.end;
        }
        if (rule.match != null) {
            emitWithCaptures(step.matcher, rule.captures, rule.name, spans, limit);
            return step.end;
        }
        if (rule.begin != null) {
            // Ouverture de bloc : résoudre les backrefs du end avec
            // les groupes du begin, compiler UNE fois (cache LRU de la
            // couture — plan 10 §3.2), empiler, émettre le begin — puis
            // rendre la main à la boucle : le contenu (sous-règles et
            // end) est évalué au tour SUIVANT, en leftmost, pour qu'un
            // échappement situé AVANT le fermateur soit consommé par la
            // sous-règle (ex. \" dans une chaîne) et ne précipite pas la
            // fermeture.
            // Idiome (?!\G) : préfixe retiré du motif (garde portée
            // par le cadre), ancre = fin du begin.
            boolean notG = rule.end != null && rule.end.startsWith("(?!\\G)");
            String endSrc = notG && rule.end != null
                    ? rule.end.substring(6) : rule.end;
            CompiledRegex endPat = endSrc != null
                    ? resolveEnd(endSrc, step.matcher) : NEVER;
            // Clause while : compilée avec les backrefs du begin (la
            // garde de continuation peut référencer les groupes de
            // l'ouverture, comme une fermante).
            CompiledRegex whilePat = rule.whileSrc != null
                    ? resolveEnd(rule.whileSrc, step.matcher) : null;
            // Plafond de profondeur : une grammaire dégénérée ne doit
            // pas empiler sans fin — au-delà, le begin est émis mais
            // le contenu s'écoule dans le cadre parent (dégradation
            // bornée, jamais de OOM).
            if (stack.size() < 100) {
                stack.add(new Frame(rule, endPat, whilePat,
                        step.matcher.end(), notG));
            }
            emitWithCaptures(step.matcher, rule.beginCaptures, rule.name,
                    spans, limit);
            return step.end;
        }
        return step.end;
    }

    /**
     * Compile la fermante/garde avec les backreferences {@code \1..\9}
     * substituées par les groupes du begin (quoted literals), via le
     * cache LRU de la couture — deux ouvertures du même cadre avec la
     * même capture partagent le motif compilé.
     */
    private static CompiledRegex resolveEnd(String end, LineMatcher begin) {
        int n = begin.groupCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end.length(); i++) {
            char c = end.charAt(i);
            if (c == '\\' && i + 1 < end.length() && end.charAt(i + 1) >= '1'
                    && end.charAt(i + 1) <= '9') {
                int group = end.charAt(i + 1) - '0';
                if (group <= n) {
                    String g = begin.group(group);
                    if (g != null) {
                        sb.append(DialectNormalizer.escapeLiteral(g));
                        i++;
                        continue;
                    }
                }
            }
            sb.append(c);
        }
        try {
            return RegexCache.getOrCompile(sb.toString());
        } catch (RuntimeException e) {
            // Garde du contrat « jamais d'échec en coloration » : une
            // fermante au dialecte intraduisible ne ferme jamais (le
            // bloc porte l'état inter-lignes jusqu'au rebouclage).
            MatchBudget.logEndCompileFailure(sb.toString(), e);
            return NEVER;
        }
    }

    /**
     * Émet les spans d'un match : captures nommées si présentes, sinon
     * la portée de la règle.
     *
     * <p>Deux idiomes des grammaires réelles sont honorés :</p>
     * <ul>
     *   <li><b>capture VIDE</b> ({@code (:)(:*)} sans second « : » —
     *       pseudo-classes CSS, groupes optionnels) : elle ne colore
     *       RIEN. L'inflation à un caractère posait un span fantôme
     *       (une portée {@code invalid.illegal} tombait sur le
     *       caractère SUIVANT, un séparateur de paramètres recevait le
     *       scope d'un opérateur optionnel non participante) ;</li>
     *   <li><b>captures IMBRIQUÉES de même plage</b> ({@code ((@))} —
     *       décorateurs python, {@code ((self)|(cls))} — paramètres
     *       spéciaux) : TextMate superpose les deux portées, le modèle
     *       plat garde UNE — la plus INTERNE (indice le plus élevé,
     *       traitée en dernier) remplace l'externe.</li>
     * </ul>
     */
    private void emitWithCaptures(LineMatcher m, Map<Integer, String> captures,
            String name, List<LineSpan> spans, int limit) {
        int start = Math.min(m.start(), limit);
        int end = Math.min(m.end(), limit);
        if (end <= start) return; // match vide : rien à émettre
        if (captures.isEmpty()) {
            spans.add(new LineSpan(start, end, ScopeMapper.tokenTypeFor(name)));
            return;
        }
        // Émettre les groupes capturés dans l'ordre ; les trous entre
        // captures reçoivent la portée de la règle.
        int cursor = start;
        List<Integer> indices = new ArrayList<>(captures.keySet());
        indices.sort(Integer::compare);
        for (int idx : indices) {
            if (idx < 0 || idx > m.groupCount()) continue;
            int gs = Math.min(m.start(idx), limit);
            int ge = Math.min(m.end(idx), limit);
            if (ge <= gs) continue; // capture vide ou non participante
            // Le trou AVANT la capture reçoit la portée de la règle
            // (posé d'abord : la liste reste triée par position).
            if (gs > cursor) {
                spans.add(new LineSpan(cursor, gs, ScopeMapper.tokenTypeFor(name)));
            }
            replaceSameRange(spans, new LineSpan(gs, ge,
                    ScopeMapper.tokenTypeFor(captures.get(idx))));
            cursor = Math.max(cursor, ge);
        }
        if (cursor < end) {
            spans.add(new LineSpan(cursor, end, ScopeMapper.tokenTypeFor(name)));
        }
    }

    /** Remplace un span déjà émis de MÊME plage par {@code cap} —
     * l'idiome des captures imbriquées ((@)) : le plus interne gagne. */
    private static void replaceSameRange(List<LineSpan> spans, LineSpan cap) {
        for (int i = spans.size() - 1; i >= 0; i--) {
            LineSpan s = spans.get(i);
            if (s.startCol == cap.startCol && s.endCol == cap.endCol) {
                spans.set(i, cap);
                return;
            }
        }
        spans.add(cap);
    }

    /** Portée de contenu du sommet de pile, ou PLAIN. */
    private TokenType contentType(List<Frame> stack) {
        if (stack.isEmpty()) return TokenType.PLAIN;
        Frame top = stack.get(stack.size() - 1);
        TokenType t = ScopeMapper.tokenTypeFor(top.rule.contentName);
        return t != TokenType.PLAIN ? t : ScopeMapper.tokenTypeFor(top.rule.name);
    }

    /** Fusionne les spans adjacents de même type, retire les vides. */
    private static List<LineSpan> merge(List<LineSpan> spans) {
        List<LineSpan> out = new ArrayList<>(spans.size());
        for (LineSpan s : spans) {
            if (s.endCol <= s.startCol) continue;
            if (!out.isEmpty()) {
                LineSpan last = out.get(out.size() - 1);
                if (last.type == s.type && last.endCol == s.startCol) {
                    out.set(out.size() - 1, new LineSpan(last.startCol, s.endCol, last.type));
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    /** Candidat de match retenu à une position — positions BORNÉES à
     * la ligne réelle (le « \n » virtuel peut être consommé par la
     * fermante sans fuiter dans les spans). */
    private static final class Step {
        final LineMatcher matcher;
        final Rule rule;
        /** Vrai quand le match est le end du sommet de pile (dépilement). */
        final boolean isEnd;
        /** Début du match, borné à la ligne. */
        final int start;
        /** Fin du match, bornée à la ligne. */
        final int end;

        Step(LineMatcher matcher, Rule rule, boolean isEnd, int limit) {
            this.matcher = matcher;
            this.rule = rule;
            this.isEnd = isEnd;
            this.start = Math.min(matcher.start(), limit);
            this.end = Math.min(matcher.end(), limit);
        }
    }
}

package jo.codeeditor.textmate;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.textmate.TextMateGrammar.Rule;

import jo.codeeditor.textmate.internal.RegexCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Moteur d'application d'une {@link TextMateGrammar} ligne par ligne
 * (ADR-002, C3) — le contrat du crochet
 * {@code SyntaxHighlighter.setTextMateTokenizer}.
 *
 * <h2>Sémantique</h2>
 * <ul>
 *   <li><b>Leftmost-first</b> : à chaque position, la règle dont le
 *       match commence le plus tôt gagne ; à égalité, l'ordre de
 *       déclaration des patterns départage (sémantique TextMate).</li>
 *   <li><b>Pile de contextes</b> : une règle begin/end non fermée en
 *       fin de ligne empile un cadre ; les lignes suivantes
 *       recommencent avec les sous-règles du sommet (et la recherche
 *       de son {@code end} en candidate prioritaire). La pile se
 *       propage entre les lignes via l'{@code exitState} opaque du
 *       contrat.</li>
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

    /** Cadre de pile : règle begin/end ouverte, end compilé (backrefs résolues). */
    private static final class Frame {
        final Rule rule;
        /** Pattern end résolu (backrefs substituées par les groupes du begin). */
        final Pattern endPattern;

        Frame(Rule rule, Pattern endPattern) {
            this.rule = rule;
            this.endPattern = endPattern;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Frame)) return false;
            Frame f = (Frame) o;
            // Identité de règle (immuable, partagée) + end compilé
            // équivalent (même source résolue — comparaison par
            // pattern().source(), les regex compilées n'implantent
            // pas equals).
            return rule == f.rule
                    && Objects.equals(endPattern.pattern(), f.endPattern.pattern());
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(rule) + endPattern.pattern().hashCode();
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
     * Tokénise une ligne avec la grammaire et un registre de grammaires
     * EXTERNES (C3-bis, compatibilité sora-editor) : les includes
     * {@code source.css} et consorts de la grammaire y sont résolus —
     * portée par portée, la carte est {@code scopeName → grammaire}
     * (l'hôte construit la vue depuis ses grammaires enregistrées).
     * Une référence absente du registre ne matche RIEN : la grammaire
     * mère rend le texte avec ses règles locales — dégradation
     * documentée (html sans css colorie les balises, pas le contenu
     * des {@code <style>}).
     */
    public StyledLine tokenize(TextMateGrammar grammar, String line, int entryState,
            Map<String, TextMateGrammar> externals) {
        List<Frame> stack = resolve(entryState);
        List<LineSpan> spans = new ArrayList<>();
        tokenizeLine(grammar, line, stack, spans, externals);
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

    private void tokenizeLine(TextMateGrammar grammar, String line,
            List<Frame> stack, List<LineSpan> spans, Map<String, TextMateGrammar> externals) {
        int pos = 0;
        while (pos < line.length()) {
            Step step = nextStep(grammar, line, pos, stack, externals);
            if (step == null) {
                // Aucune règle ne matche sur le reste : le texte
                // restant reçoit la portée de contenu du sommet (ou
                // PLAIN à la racine).
                spans.add(new LineSpan(pos, line.length(), contentType(stack)));
                return;
            }
            int start = step.matcher.start();
            if (start > pos) {
                spans.add(new LineSpan(pos, start, contentType(stack)));
            }
            pos = apply(step, stack, spans);
            if (pos <= start) {
                // Garde anti-boucle (match vide) : avancer d'un.
                pos = start + 1;
            }
        }
    }

    /** Le plus proche match parmi les règles candidates à la position courante. */
    private Step nextStep(TextMateGrammar grammar, String line, int pos,
            List<Frame> stack, Map<String, TextMateGrammar> externals) {
        Step best = null;
        // Candidats : au sommet d'un begin/end — le end (prioritaire,
        // comme TextMate) puis les sous-règles ; sinon les patterns racine.
        if (!stack.isEmpty()) {
            Frame top = stack.get(stack.size() - 1);
            Matcher m = top.endPattern.matcher(line);
            if (m.find(pos)) {
                best = new Step(m, top.rule, true);
            }
            for (Rule sub : top.rule.patterns) {
                best = better(best, tryRule(sub, grammar, line, pos, externals, 0));
            }
        } else {
            for (Rule root : grammar.patterns) {
                best = better(best, tryRule(root, grammar, line, pos, externals, 0));
            }
        }
        return best;
    }

    /** Évalue une règle (et ses includes) à la recherche d'un match depuis pos. */
    private Step tryRule(Rule rule, TextMateGrammar grammar, String line, int pos,
            Map<String, TextMateGrammar> externals, int depth) {
        if (rule.include != null) {
            Rule resolved = resolveInclude(rule, grammar, externals);
            if (resolved == null || resolved == rule) return null; // absent ou cycle
            // Garde de profondeur : une inclusion externe mutuelle ne
            // doit jamais provoquer de récursion infinie.
            if (depth >= 50) return null;
            return tryRule(resolved, grammar, line, pos, externals, depth + 1);
        }
        if (rule.match != null) {
            Matcher m = rule.match.matcher(line);
            if (m.find(pos) && m.end() > m.start()) {
                return new Step(m, rule, false);
            }
            return null;
        }
        if (rule.begin != null) {
            Matcher m = rule.begin.matcher(line);
            if (m.find(pos)) {
                return new Step(m, rule, false);
            }
            return null;
        }
        // Règle conteneur (groupe de patterns) : chercher dans ses enfants.
        Step best = null;
        for (Rule sub : rule.patterns) {
            best = better(best, tryRule(sub, grammar, line, pos, externals, depth));
        }
        return best;
    }

    private Rule resolveInclude(Rule rule, TextMateGrammar grammar,
            Map<String, TextMateGrammar> externals) {
        String ref = rule.include;
        if ("$self".equals(ref) || "$base".equals(ref)) {
            // Règle conteneur synthétique exposant les patterns racine.
            return new Rule("$" + ref, null, null, null, null, null,
                    Map.of(), Map.of(), Map.of(), grammar.patterns, null);
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
        return new Rule(ref, null, null, null, null, null,
                Map.of(), Map.of(), Map.of(), foreign.patterns, null);
    }

    private Step better(Step a, Step b) {
        if (b == null) return a;
        if (a == null) return b;
        if (b.matcher.start() < a.matcher.start()) return b;
        return a; // leftmost ; à égalité, la première déclarée (a) gagne
    }

    /** Applique le step : produit les spans et met à jour la pile. */
    private int apply(Step step, List<Frame> stack, List<LineSpan> spans) {
        Rule rule = step.rule;
        if (step.isEnd) {
            // Fermeture du sommet de pile : span du end (endCaptures)
            // + dépilement.
            Frame top = stack.remove(stack.size() - 1);
            emitWithCaptures(step.matcher, top.rule.endCaptures, top.rule.name, spans);
            return step.matcher.end();
        }
        if (rule.match != null) {
            emitWithCaptures(step.matcher, rule.captures, rule.name, spans);
            return step.matcher.end();
        }
        if (rule.begin != null) {
            // Ouverture de bloc : résoudre les backrefs du end avec
            // les groupes du begin, compiler UNE fois, empiler, émettre
            // le begin — puis rendre la main à la boucle : le contenu
            // (sous-règles et end) est évalué au tour SUIVANT, en
            // leftmost, pour qu'un échappement situé AVANT le
            // fermateur soit consommé par la sous-règle (ex. \" dans
            // une chaîne) et ne précipite pas la fermeture.
            Pattern endPat = resolveEnd(rule.end, step.matcher);
            stack.add(new Frame(rule, endPat));
            emitWithCaptures(step.matcher, rule.beginCaptures, rule.name, spans);
            return step.matcher.end();
        }
        return step.matcher.end();
    }

    /**
     * Compile le pattern end avec les backreferences {@code \1..\9}
     * substituées par les groupes du begin (quoted literals).
     */
    private static Pattern resolveEnd(String end, Matcher begin) {
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
                        sb.append(Pattern.quote(g));
                        i++;
                        continue;
                    }
                }
            }
            sb.append(c);
        }
        try {
            return Pattern.compile(RegexCompat.translate(sb.toString()));
        } catch (PatternSyntaxException e) {
            // Garde du contrat « jamais d'échec en coloration » : une
            // fermante au dialecte intraduisible ne ferme jamais (le
            // bloc porte l'état inter-lignes jusqu'au rebouclage).
            return Pattern.compile("(?!)");
        }
    }

    /**
     * Émet les spans d'un match : captures nommées si présentes, sinon
     * la portée de la règle.
     */
    private void emitWithCaptures(Matcher m, Map<Integer, String> captures,
            String name, List<LineSpan> spans) {
        int start = m.start();
        int end = m.end();
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
            int gs = m.start(idx);
            int ge = m.end(idx);
            if (gs < 0) continue; // groupe non participante
            if (gs > cursor) {
                spans.add(new LineSpan(cursor, gs, ScopeMapper.tokenTypeFor(name)));
            }
            int gEnd = Math.max(ge, gs + 1);
            spans.add(new LineSpan(gs, gEnd, ScopeMapper.tokenTypeFor(captures.get(idx))));
            cursor = Math.max(cursor, gEnd);
        }
        if (cursor < end) {
            spans.add(new LineSpan(cursor, end, ScopeMapper.tokenTypeFor(name)));
        }
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

    /** Candidat de match retenu à une position. */
    private static final class Step {
        final Matcher matcher;
        final Rule rule;
        /** Vrai quand le match est le end du sommet de pile (dépilement). */
        final boolean isEnd;

        Step(Matcher matcher, Rule rule, boolean isEnd) {
            this.matcher = matcher;
            this.rule = rule;
            this.isEnd = isEnd;
        }
    }
}

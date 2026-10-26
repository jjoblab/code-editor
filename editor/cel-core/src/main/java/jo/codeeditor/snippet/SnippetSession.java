package jo.codeeditor.snippet;

import jo.codeeditor.shift.EditSpan;

import java.util.*;

/**
 * Session de snippet basée sur les tab-stops.
 * Gère les plages de placeholders liés/miroirs pour l'insertion de
 * snippets de code. Reprend le design du {@code SnippetSession.kt} de
 * l'éditeur.
 */
public class SnippetSession {

    /**
     * Un tab stop avec début, fin et indices liés.
     * Les tab stops sont ordonnés par leur index d'arrêt (0 = position
     * finale).
     */
    public static final class TabStop {
        public int start;
        public int end;
        public final int index;
        public final String placeholder;
        /** Indices des autres tab stops dont le texte reflète celui-ci. */
        public final List<Integer> linked;
        /**
         * Les options du champ de choix (« {@code ${1|a,b,c|}} », 4.5.0) —
         * vide pour un champ libre. La première option est le texte
         * initial du champ ; les suivantes sont proposées dans le popup
         * de choix.
         *
         * @since 4.5.0
         */
        public final List<String> choices;

        public TabStop(int start, int end, int index, String placeholder, List<Integer> linked) {
            this(start, end, index, placeholder, linked, Collections.emptyList());
        }

        /**
         * Un tab stop avec options de choix (4.5.0) — le constructeur
         * historique délègue avec un champ libre.
         *
         * @param choices les options proposées, éventuellement vide
         * @since 4.5.0
         */
        public TabStop(int start, int end, int index, String placeholder,
                List<Integer> linked, List<String> choices) {
            this.start = start;
            this.end = end;
            this.index = index;
            this.placeholder = placeholder != null ? placeholder : "";
            this.linked = linked != null ? Collections.unmodifiableList(linked) : Collections.emptyList();
            this.choices = choices != null
                    ? Collections.unmodifiableList(new ArrayList<>(choices)) : Collections.emptyList();
        }

        public int length() { return end - start; }

        @Override
        public String toString() {
            return "TabStop(" + index + ": [" + start + "," + end + ") \"" + placeholder + "\")";
        }
    }

    private final List<TabStop> stops;
    private int currentIndex;
    /**
     * Le texte du snippet SANS les marqueurs de tab stops ($1, ${1:ph}…)
     * — celui à insérer dans le document. Renseigné par
     * {@link #parse(String, int)} ; vide pour une session construite
     * manuellement.
     */
    private final String plainText;

    /**
     * Crée une session de snippet à partir d'une liste de tab stops.
     * Les arrêts doivent être triés par index (décroissant), avec $0
     * comme arrêt final.
     */
    public SnippetSession(List<TabStop> stops) {
        this(stops, "");
    }

    private SnippetSession(List<TabStop> stops, String plainText) {
        this.stops = new ArrayList<>(stops);
        // Ordre de NAVIGATION : index croissant, $0 (position finale)
        // visité en dernier — convention LSP des snippets ($1, $2, …
        // puis $0). L'ancien tri décroissant visitait $2 avant $1 :
        // invisible tant que la classe n'était pas câblée (D-01).
        this.stops.sort((a, b) -> Integer.compare(orderKey(a.index), orderKey(b.index)));
        this.currentIndex = this.stops.isEmpty() ? 0 : this.stops.get(0).index;
        this.plainText = plainText != null ? plainText : "";
    }

    /** Clé d'ordre de navigation : $0 (index 0) est traité en dernier. */
    private static int orderKey(int index) {
        return index == 0 ? Integer.MAX_VALUE : index;
    }

    /** Index réel correspondant à une clé d'ordre. */
    private static int indexFromKey(int key) {
        return key == Integer.MAX_VALUE ? 0 : key;
    }

    /**
     * Le texte du snippet sans les marqueurs de tab stops — à insérer
     * dans le document à la plage de remplacement.
     */
    public String plainText() {
        return plainText;
    }

    /**
     * Analyse une chaîne de snippet simple et crée une session.
     * Gère la syntaxe $1, $2, ... et ${1:placeholder}.
     *
     * @param snippet le texte du snippet
     * @param baseOffset l'offset où le snippet est inséré
     * @return une SnippetSession, ou null si aucun tab stop
     */
    public static SnippetSession parse(String snippet, int baseOffset) {
        return parse(snippet, baseOffset, null);
    }

    /** Un arrêt brut collecté à la première passe (avant groupement). */
    private static final class RawStop {
        final int index;
        final int start;
        final int end;
        List<String> choices = Collections.emptyList();

        RawStop(int index, int start, int end) {
            this.index = index;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Analyse un snippet avec variables de choix et macros.
     *
     * <p>En plus des marqueurs historiques ({@code $N},
     * {@code ${N:placeholder}}, miroirs par index répété), deux
     * familles sont reconnues depuis 4.5.0 :</p>
     *
     * <ul>
     *   <li><b>Choix</b> — « {@code ${1|alpha,beta,gamma|}} » : champ
     *       initialisé à la première option, les autres proposées dans
     *       le popup de choix ({@link TabStop#choices}). Les options
     *       sont séparées par des virgules et ne peuvent pas contenir
     *       « {@code |}} » ; le marqueur se termine par « {@code |}} » ;</li>
     *   <li><b>Macros</b> — « {@code $FILE$} », « {@code $DATE$} » et
     *       « {@code $USER$} » : résolues à l'expansion depuis le
     *       contexte (nom du fichier, auteur, date) ; sans contexte —
     *       ou champ non renseigné — la macro est conservée
     *       LITTÉRALEMENT, jamais effacée silencieusement.</li>
     * </ul>
     *
     * @param snippet le texte du snippet
     * @param baseOffset l'offset où le snippet est inséré
     * @param macros le contexte de résolution (null = macros littérales)
     * @return une SnippetSession, ou null si aucun tab stop
     * @since 4.5.0
     */
    public static SnippetSession parse(String snippet, int baseOffset, SnippetMacroContext macros) {
        List<TabStop> stops = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int pos = 0;

        // Première passe : collecte tous les tab stops
        List<RawStop> rawStops = new ArrayList<>();
        while (pos < snippet.length()) {
            if (snippet.charAt(pos) == '$') {
                // Macros $FILE$ / $DATE$ / $USER$ : le nom canonique en
                // majuscules, borné par un $ final. Inconnu ou sans
                // contexte : le marqueur entier reste littéral.
                int macroLen = macroMarkerLength(snippet, pos);
                if (macroLen > 0) {
                    String resolved = macroValue(snippet, pos, macros);
                    if (resolved != null) {
                        plain.append(resolved);
                    } else {
                        plain.append(snippet, pos, pos + macroLen);
                    }
                    pos += macroLen;
                    continue;
                }
                pos++;
                if (pos >= snippet.length()) break;

                if (snippet.charAt(pos) == '{') {
                    // ${N:placeholder} ou ${N|a,b,c|}
                    pos++;
                    int numStart = pos;
                    while (pos < snippet.length() && Character.isDigit(snippet.charAt(pos))) pos++;
                    int index = Integer.parseInt(snippet.substring(numStart, pos));
                    String placeholder = "";
                    List<String> choices = Collections.emptyList();
                    if (pos < snippet.length() && snippet.charAt(pos) == ':') {
                        pos++;
                        int phStart = pos;
                        int depth = 1;
                        while (pos < snippet.length() && depth > 0) {
                            if (snippet.charAt(pos) == '{') depth++;
                            else if (snippet.charAt(pos) == '}') depth--;
                            if (depth > 0) pos++;
                        }
                        placeholder = snippet.substring(phStart, pos);
                    } else if (pos < snippet.length() && snippet.charAt(pos) == '|') {
                        // Champ de choix : options jusqu'au « |} » final,
                        // séparées par des virgules. Le champ est
                        // initialisé à la PREMIÈRE option. Le « } » qui
                        // ferme le marqueur N'est JAMAIS consommé par le
                        // scan (même garde que le placeholder « : »).
                        pos++;
                        int optStart = pos;
                        int depth = 1;
                        while (pos < snippet.length() && depth > 0) {
                            char c = snippet.charAt(pos);
                            if (c == '{') {
                                depth++;
                            } else if (c == '}') {
                                depth--;
                                if (depth == 0) break;
                            } else if (c == '|' && depth == 1
                                    && pos + 1 < snippet.length()
                                    && snippet.charAt(pos + 1) == '}') {
                                break;
                            }
                            pos++;
                        }
                        choices = splitChoices(snippet.substring(optStart, pos));
                        if (!choices.isEmpty()) {
                            placeholder = choices.get(0);
                        }
                        // Consomme le « |} » final, ou le « } » isolé
                        // d'un marqueur malformé.
                        if (pos < snippet.length() && snippet.charAt(pos) == '|') pos++;
                    }
                    if (pos < snippet.length() && snippet.charAt(pos) == '}') pos++;

                    int start = baseOffset + plain.length();
                    plain.append(placeholder);
                    int end = baseOffset + plain.length();
                    RawStop raw = new RawStop(index, start, end);
                    raw.choices = choices;
                    rawStops.add(raw);

                } else if (Character.isDigit(snippet.charAt(pos))) {
                    // $N
                    int numStart = pos;
                    while (pos < snippet.length() && Character.isDigit(snippet.charAt(pos))) pos++;
                    int index = Integer.parseInt(snippet.substring(numStart, pos));

                    int start = baseOffset + plain.length();
                    int end = start;
                    rawStops.add(new RawStop(index, start, end));
                } else {
                    plain.append('$');
                }
            } else {
                plain.append(snippet.charAt(pos));
                pos++;
            }
        }

        // Groupe par index pour la liaison
        Map<Integer, List<RawStop>> grouped = new HashMap<>();
        for (RawStop rs : rawStops) {
            grouped.computeIfAbsent(rs.index, k -> new ArrayList<>()).add(rs);
        }

        // Construit les arrêts : la première occurrence (primaire) est
        // ajoutée EN PREMIER — l'ordre stable du tri de navigation la
        // garde en tête de son index, donc current() désigne bien le
        // champ que le caret sélectionne (première occurrence dans le
        // texte) ; le reste est lié (miroir).
        for (var entry : grouped.entrySet()) {
            int index = entry.getKey();
            List<RawStop> group = entry.getValue();
            RawStop primary = group.get(0);
            stops.add(new TabStop(primary.start, primary.end, index, "",
                    Collections.emptyList(), primary.choices));
            for (int i = 1; i < group.size(); i++) {
                // Arrêts liés (miroirs de la première occurrence).
                RawStop lnk = group.get(i);
                stops.add(new TabStop(lnk.start, lnk.end, index, "", Collections.emptyList()));
            }
        }

        if (stops.isEmpty()) return null;
        return new SnippetSession(stops, plain.toString());
    }

    /** Coupe les options d'un champ de choix (virgules, vides ignorées). */
    private static List<String> splitChoices(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    /** Les noms de macros canoniques, en majuscules. */
    private static final String[] MACRO_NAMES = {"FILE", "DATE", "USER"};

    /**
     * La longueur du marqueur macro qui démarre à {@code pos}
     * (« $NAME$ » tout compris), 0 si ce n'est pas une macro canonique.
     */
    private static int macroMarkerLength(String snippet, int pos) {
        if (pos >= snippet.length() || snippet.charAt(pos) != '$') return 0;
        for (String name : MACRO_NAMES) {
            int len = 1 + name.length() + 1; // $NAME$
            if (pos + len <= snippet.length()
                    && snippet.regionMatches(pos + 1, name, 0, name.length())
                    && snippet.charAt(pos + len - 1) == '$') {
                return len;
            }
        }
        return 0;
    }

    /**
     * La valeur résolue de la macro dont le marqueur démarre à
     * {@code pos} — null si le contexte est absent ou si le champ
     * correspondant n'est pas renseigné (marqueur conservé littéral).
     */
    private static String macroValue(String snippet, int pos, SnippetMacroContext macros) {
        if (macros == null) return null;
        String name = snippet.substring(pos + 1, macroMarkerLength(snippet, pos) + pos - 1);
        switch (name) {
            case "FILE":
                return macros.fileName();
            case "USER":
                return macros.userName();
            case "DATE":
                return macros.resolveDate();
            default:
                return null;
        }
    }

    /**
     * Renvoie le tab stop courant, ou null si terminé.
     */
    public TabStop current() {
        for (TabStop s : stops) {
            if (s.index == currentIndex) return s;
        }
        return null;
    }

    /**
     * Passe au tab stop suivant (ordre de navigation : index
     * croissant, $0 en dernier). Renvoie le nouvel arrêt courant, ou
     * null quand la navigation est épuisée (l'appelant termine la
     * session — l'arrêt $0, s'il existe, EST retourné avant : c'est à
     * lui que la session se clôt).
     */
    public TabStop next() {
        // Trouve l'index de clé d'ordre strictement supérieure le plus
        // proche (le $0 a la clé la plus grande : il est le dernier).
        int cur = orderKey(currentIndex);
        int best = Integer.MAX_VALUE;
        for (TabStop s : stops) {
            int k = orderKey(s.index);
            if (k > cur && k < best) best = k;
        }
        if (best == Integer.MAX_VALUE && cur != Integer.MAX_VALUE) {
            // Le $0 existe-t-il ? (cur != MAX garantit qu'on ne l'a pas
            // déjà atteint)
            for (TabStop s : stops) {
                if (s.index == 0) {
                    currentIndex = 0;
                    return s;
                }
            }
        }
        if (best == Integer.MAX_VALUE) return null;
        currentIndex = indexFromKey(best);
        return current();
    }

    /**
     * Passe au tab stop précédent (ordre de navigation inverse).
     * Renvoie le nouvel arrêt courant, ou null au premier champ.
     */
    public TabStop prev() {
        int cur = orderKey(currentIndex);
        int best = Integer.MIN_VALUE;
        for (TabStop s : stops) {
            int k = orderKey(s.index);
            if (k < cur && k > best) best = k;
        }
        if (best == Integer.MIN_VALUE) return null;
        currentIndex = indexFromKey(best);
        return current();
    }

    /**
     * Notifie la session d'une édition pour ré-ancrer les plages.
     */
    public void onEdit(EditSpan span) {
        for (TabStop s : stops) {
            int newStart = mapStart(s.start, span);
            int newEnd = mapEnd(s.end, span);
            s.start = newStart;
            s.end = newEnd;
        }
    }

    /**
     * Renvoie toutes les plages de champs (début, fin) pour la coloration.
     */
    public List<int[]> fieldRanges() {
        List<int[]> ranges = new ArrayList<>();
        for (TabStop s : stops) {
            ranges.add(new int[]{s.start, s.end});
        }
        return ranges;
    }

    /**
     * Synchronise les placeholders liés avec le texte de l'arrêt courant.
     * Si l'arrêt courant a des arrêts liés, leurs plages sont mises à
     * jour pour correspondre.
     *
     * @param text le texte courant du document
     */
    public void mirrorCurrent(String text) {
        TabStop cur = current();
        if (cur == null) return;
        String curText = text.substring(cur.start, cur.end);
        for (TabStop s : stops) {
            if (s.index == cur.index && s != cur) {
                // Ceci est un miroir de l'arrêt courant
                int newLen = curText.length();
                s.end = s.start + newLen;
            }
        }
    }

    /**
     * Termine la session de snippet et renvoie la position finale du
     * caret ($0).
     */
    public int finish() {
        for (TabStop s : stops) {
            if (s.index == 0) return s.start;
        }
        // Sans $0, renvoie la fin du dernier arrêt
        int maxEnd = 0;
        for (TabStop s : stops) {
            maxEnd = Math.max(maxEnd, s.end);
        }
        return maxEnd;
    }

    /**
     * Renvoie la liste de tous les tab stops.
     */
    public List<TabStop> getStops() {
        return Collections.unmodifiableList(stops);
    }

    /**
     * Renvoie l'index du tab stop courant.
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    // Aides de mapping
    private static int mapStart(int offset, EditSpan span) {
        if (offset <= span.start) return offset;
        if (offset <= span.start + span.removed) return span.start + span.added;
        return offset + span.delta();
    }

    private static int mapEnd(int offset, EditSpan span) {
        if (offset < span.start) return offset;
        if (offset < span.start + span.removed) return span.start;
        return offset + span.delta();
    }
}

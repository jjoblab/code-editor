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

        public TabStop(int start, int end, int index, String placeholder, List<Integer> linked) {
            this.start = start;
            this.end = end;
            this.index = index;
            this.placeholder = placeholder != null ? placeholder : "";
            this.linked = linked != null ? Collections.unmodifiableList(linked) : Collections.emptyList();
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
        List<TabStop> stops = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int pos = 0;
        Map<Integer, List<Integer>> linkedMap = new HashMap<>();

        // Première passe : collecte tous les tab stops
        List<int[]> rawStops = new ArrayList<>(); // [index, startPos, endPos, placeholderStart, placeholderEnd]
        while (pos < snippet.length()) {
            if (snippet.charAt(pos) == '$') {
                pos++;
                if (pos >= snippet.length()) break;

                if (snippet.charAt(pos) == '{') {
                    // ${N:placeholder}
                    pos++;
                    int numStart = pos;
                    while (pos < snippet.length() && Character.isDigit(snippet.charAt(pos))) pos++;
                    int index = Integer.parseInt(snippet.substring(numStart, pos));
                    String placeholder = "";
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
                    }
                    if (pos < snippet.length() && snippet.charAt(pos) == '}') pos++;

                    int start = baseOffset + plain.length();
                    plain.append(placeholder);
                    int end = baseOffset + plain.length();
                    rawStops.add(new int[]{index, start, end, 0, 0});

                } else if (Character.isDigit(snippet.charAt(pos))) {
                    // $N
                    int numStart = pos;
                    while (pos < snippet.length() && Character.isDigit(snippet.charAt(pos))) pos++;
                    int index = Integer.parseInt(snippet.substring(numStart, pos));

                    int start = baseOffset + plain.length();
                    int end = start;
                    rawStops.add(new int[]{index, start, end, 0, 0});
                } else {
                    plain.append('$');
                }
            } else {
                plain.append(snippet.charAt(pos));
                pos++;
            }
        }

        // Groupe par index pour la liaison
        Map<Integer, List<int[]>> grouped = new HashMap<>();
        for (int[] rs : rawStops) {
            grouped.computeIfAbsent(rs[0], k -> new ArrayList<>()).add(rs);
        }

        // Construit les arrêts : la première occurrence (primaire) est
        // ajoutée EN PREMIER — l'ordre stable du tri de navigation la
        // garde en tête de son index, donc current() désigne bien le
        // champ que le caret sélectionne (première occurrence dans le
        // texte) ; le reste est lié (miroir).
        for (var entry : grouped.entrySet()) {
            int index = entry.getKey();
            List<int[]> group = entry.getValue();
            int[] primary = group.get(0);
            stops.add(new TabStop(primary[1], primary[2], index, "", Collections.emptyList()));
            for (int i = 1; i < group.size(); i++) {
                // Arrêts liés (miroirs de la première occurrence).
                int[] lnk = group.get(i);
                stops.add(new TabStop(lnk[1], lnk[2], index, "", Collections.emptyList()));
            }
        }

        if (stops.isEmpty()) return null;
        return new SnippetSession(stops, plain.toString());
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

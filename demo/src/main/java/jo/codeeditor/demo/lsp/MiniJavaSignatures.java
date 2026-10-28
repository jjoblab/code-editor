package jo.codeeditor.demo.lsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moteur d'aide de signature du mini Java de la démo — les VRAIES
 * signatures des méthodes appelées, pas une carte de démonstration.
 *
 * <p>Trois sources de candidats, dans l'ordre :</p>
 * <ol>
 *   <li><b>La bibliothèque embarquée</b> — les méthodes usuelles du JDK
 *       (impression, maths, chaînes, bâtisseurs, collections, objets),
 *       récepteur par récepteur : {@code System.out.println(…)} sert
 *       les surcharges d'impression, {@code Math.max(…)} les maths,
 *       {@code sb.append(…)} les surcharges du bâtisseur…</li>
 *   <li><b>Le buffer lui-même</b> — les méthodes DÉCLARÉES dans le
 *       fichier ouvert (avec leur javadoc {@code @param} quand elle
 *       existe), que l'appel soit nu ({@code calculer(}) ou qualifié
 *       par une variable ({@code voiture.démarrer(}) ou un nom de
 *       classe ({@code Voiture.créer(}).</li>
 *   <li><b>Le repli par nom</b> — un appel nu dont le nom ne matche
 *       aucune méthode déclarée cherche dans toute la bibliothèque
 *       ({@code append(} trouve le bâtisseur même sans récepteur).</li>
 * </ol>
 *
 * <p>Pur JVM, aucune dépendance Android : testable directement en
 * JUnit (voir {@code MiniJavaSignaturesTest}) et partagé par le mini
 * serveur LSP ({@code textDocument/signatureHelp}) et le résolveur
 * local de l'analyseur de la démo — les deux chemins servent les
 * mêmes signatures.</p>
 */
public final class MiniJavaSignatures {

    private MiniJavaSignatures() {}

    // ════════════════════════════════════════════════════════════════
    // Types de sortie
    // ════════════════════════════════════════════════════════════════

    /** Une surcharge candidate : libellé complet, documentation, paramètres. */
    public static class Entry {
        /** Libellé affiché dans le popup, ex. {@code println(String x)}. */
        public final String label;
        /** Documentation de la méthode (une phrase, sections {@code @} permises). */
        public final String documentation;
        /** Libellés des paramètres, ex. {@code String x}. */
        public final List<String> params;
        /** Documentation de chaque paramètre (même ordre que {@link #params}). */
        public final List<String> paramDocs;

        Entry(String label, String documentation,
                List<String> params, List<String> paramDocs) {
            this.label = label;
            this.documentation = documentation;
            this.params = params;
            this.paramDocs = paramDocs;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Résolution à la position du caret : surcharges + paramètre actif. */
    public static final class Result {
        /** Surcharges candidates (au moins une). */
        public final List<Entry> signatures;
        /** Index (base 0) du paramètre en cours de frappe. */
        public final int activeParameter;

        Result(List<Entry> signatures, int activeParameter) {
            this.signatures = signatures;
            this.activeParameter = activeParameter;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Bibliothèque embarquée — table compacte
    // ════════════════════════════════════════════════════════════════

    // récepteur | méthode | libellé | doc | docs des paramètres (séparées par |)
    private static final String[][] TABLE = {
        {"System.out", "println", "println()", "Imprime un saut de ligne.", ""},
        {"System.out", "println", "println(String x)", "Imprime la chaîne puis saute une ligne.", "le texte à imprimer"},
        {"System.out", "println", "println(int x)", "Imprime l'entier puis saute une ligne.", "l'entier à imprimer"},
        {"System.out", "println", "println(long x)", "Imprime l'entier long puis saute une ligne.", "l'entier long à imprimer"},
        {"System.out", "println", "println(char x)", "Imprime le caractère puis saute une ligne.", "le caractère à imprimer"},
        {"System.out", "println", "println(boolean x)", "Imprime le booléen puis saute une ligne.", "le booléen à imprimer"},
        {"System.out", "println", "println(double x)", "Imprime le décimal puis saute une ligne.", "le décimal à imprimer"},
        {"System.out", "println", "println(Object x)", "Imprime la représentation de l'objet puis saute une ligne.", "l'objet à imprimer"},
        {"System.out", "print", "print(String x)", "Imprime la chaîne sans sauter de ligne.", "le texte à imprimer"},
        {"System.out", "print", "print(int x)", "Imprime l'entier sans sauter de ligne.", "l'entier à imprimer"},
        {"System.out", "print", "print(char x)", "Imprime le caractère sans sauter de ligne.", "le caractère à imprimer"},
        {"System.out", "print", "print(boolean x)", "Imprime le booléen sans sauter de ligne.", "le booléen à imprimer"},
        {"System.out", "print", "print(Object x)", "Imprime la représentation de l'objet sans sauter de ligne.", "l'objet à imprimer"},
        {"Math", "abs", "abs(int a)", "Valeur absolue de l'entier.", "l'entier"},
        {"Math", "abs", "abs(double a)", "Valeur absolue du décimal.", "le décimal"},
        {"Math", "max", "max(int a, int b)", "Le plus grand des deux entiers.", "le premier entier|le second entier"},
        {"Math", "max", "max(double a, double b)", "Le plus grand des deux décimaux.", "le premier décimal|le second décimal"},
        {"Math", "min", "min(int a, int b)", "Le plus petit des deux entiers.", "le premier entier|le second entier"},
        {"Math", "min", "min(double a, double b)", "Le plus petit des deux décimaux.", "le premier décimal|le second décimal"},
        {"Math", "pow", "pow(double base, double exposant)", "Élève la base à l'exposant.", "la base|l'exposant"},
        {"Math", "sqrt", "sqrt(double a)", "Racine carrée du décimal.", "le décimal"},
        {"Math", "round", "round(double a)", "Arrondit au long le plus proche.", "le décimal à arrondir"},
        {"Math", "floor", "floor(double a)", "Arrondit vers le bas (entier décimal).", "le décimal"},
        {"Math", "ceil", "ceil(double a)", "Arrondit vers le haut (entier décimal).", "le décimal"},
        {"Math", "random", "random()", "Un décimal pseudo-aléatoire dans [0, 1[.", ""},
        {"String", "substring", "substring(int début)", "La sous-chaîne depuis l'index donné jusqu'à la fin.", "l'index de début (inclus)"},
        {"String", "substring", "substring(int début, int fin)", "La sous-chaîne entre les deux index.", "l'index de début (inclus)|l'index de fin (exclus)"},
        {"String", "charAt", "charAt(int index)", "Le caractère à l'index donné.", "l'index du caractère"},
        {"String", "indexOf", "indexOf(String s)", "L'index de la première occurrence, -1 si absente.", "la sous-chaîne cherchée"},
        {"String", "indexOf", "indexOf(String s, int depuis)", "L'index de la première occurrence à partir d'un index.", "la sous-chaîne cherchée|l'index de départ"},
        {"String", "lastIndexOf", "lastIndexOf(String s)", "L'index de la dernière occurrence, -1 si absente.", "la sous-chaîne cherchée"},
        {"String", "equals", "equals(Object autre)", "Vrai si les deux chaînes ont les mêmes caractères.", "l'autre objet à comparer"},
        {"String", "equalsIgnoreCase", "equalsIgnoreCase(String autre)", "Vrai si égales sans casse.", "l'autre chaîne"},
        {"String", "replace", "replace(CharSequence cible, CharSequence remplacement)", "Remplace chaque occurrence de la cible.", "le texte à remplacer|le texte de remplacement"},
        {"String", "split", "split(String regex)", "Découpe la chaîne selon l'expression régulière.", "le séparateur (expression régulière)"},
        {"String", "trim", "trim()", "La chaîne sans les espaces de tête et de queue.", ""},
        {"String", "toUpperCase", "toUpperCase()", "La chaîne en majuscules.", ""},
        {"String", "toLowerCase", "toLowerCase()", "La chaîne en minuscules.", ""},
        {"String", "length", "length()", "Le nombre de caractères.", ""},
        {"String", "isEmpty", "isEmpty()", "Vrai si la chaîne ne contient aucun caractère.", ""},
        {"String", "isBlank", "isBlank()", "Vrai si la chaîne est vide ou ne contient que des espaces.", ""},
        {"String", "contains", "contains(CharSequence s)", "Vrai si la séquence apparaît dans la chaîne.", "la séquence cherchée"},
        {"String", "startsWith", "startsWith(String préfixe)", "Vrai si la chaîne commence par le préfixe.", "le préfixe testé"},
        {"String", "endsWith", "endsWith(String suffixe)", "Vrai si la chaîne finit par le suffixe.", "le suffixe testé"},
        {"String", "concat", "concat(String autre)", "La concaténation des deux chaînes.", "la chaîne à concaténer"},
        {"String", "compareTo", "compareTo(String autre)", "Négatif, nul ou positif selon l'ordre lexicographique.", "l'autre chaîne"},
        {"String", "repeat", "repeat(int n)", "La chaîne répétée n fois.", "le nombre de répétitions"},
        {"String", "valueOf", "valueOf(Object o)", "La représentation textuelle de l'objet.", "l'objet à convertir"},
        {"String", "valueOf", "valueOf(int i)", "La représentation textuelle de l'entier.", "l'entier à convertir"},
        {"String", "valueOf", "valueOf(double d)", "La représentation textuelle du décimal.", "le décimal à convertir"},
        {"String", "format", "format(String modèle, Object... args)", "Une chaîne formatée selon le modèle.", "le modèle de format|les arguments du modèle"},
        {"String", "join", "join(CharSequence séparateur, CharSequence... éléments)", "Joint les éléments avec le séparateur.", "le séparateur|les éléments à joindre"},
        {"StringBuilder", "append", "append(String s)", "Ajoute la chaîne à la fin du bâtisseur.", "la chaîne à ajouter"},
        {"StringBuilder", "append", "append(int i)", "Ajoute l'entier à la fin du bâtisseur.", "l'entier à ajouter"},
        {"StringBuilder", "append", "append(char c)", "Ajoute le caractère à la fin du bâtisseur.", "le caractère à ajouter"},
        {"StringBuilder", "append", "append(double d)", "Ajoute le décimal à la fin du bâtisseur.", "le décimal à ajouter"},
        {"StringBuilder", "append", "append(boolean b)", "Ajoute le booléen à la fin du bâtisseur.", "le booléen à ajouter"},
        {"StringBuilder", "append", "append(Object o)", "Ajoute la représentation de l'objet.", "l'objet à ajouter"},
        {"StringBuilder", "insert", "insert(int offset, String s)", "Insère la chaîne à l'offset donné.", "la position d'insertion|la chaîne à insérer"},
        {"StringBuilder", "reverse", "reverse()", "Le bâtisseur inversé caractère par caractère.", ""},
        {"StringBuilder", "length", "length()", "Le nombre de caractères actuels.", ""},
        {"StringBuilder", "charAt", "charAt(int index)", "Le caractère à l'index donné.", "l'index du caractère"},
        {"StringBuilder", "delete", "delete(int début, int fin)", "Supprime les caractères entre les deux index.", "l'index de début (inclus)|l'index de fin (exclus)"},
        {"StringBuilder", "setLength", "setLength(int n)", "Fixe la longueur, complétée ou tronquée.", "la nouvelle longueur"},
        {"StringBuilder", "toString", "toString()", "Le contenu courant en chaîne.", ""},
        {"List", "add", "add(E e)", "Ajoute l'élément à la fin de la liste.", "l'élément à ajouter"},
        {"List", "add", "add(int index, E e)", "Insère l'élément à la position donnée.", "la position d'insertion|l'élément à insérer"},
        {"List", "addAll", "addAll(Collection c)", "Ajoute tous les éléments de la collection à la fin.", "la collection à ajouter"},
        {"List", "get", "get(int index)", "L'élément à la position donnée.", "la position de l'élément"},
        {"List", "set", "set(int index, E e)", "Remplace l'élément à la position et renvoie l'ancien.", "la position à remplacer|le nouvel élément"},
        {"List", "remove", "remove(int index)", "Supprime et renvoie l'élément à la position.", "la position à supprimer"},
        {"List", "remove", "remove(Object o)", "Supprime la première occurrence de l'objet.", "l'objet à supprimer"},
        {"List", "size", "size()", "Le nombre d'éléments.", ""},
        {"List", "isEmpty", "isEmpty()", "Vrai si la liste ne contient aucun élément.", ""},
        {"List", "contains", "contains(Object o)", "Vrai si la liste contient l'objet.", "l'objet cherché"},
        {"List", "indexOf", "indexOf(Object o)", "La position de la première occurrence, -1 sinon.", "l'objet cherché"},
        {"List", "clear", "clear()", "Supprime tous les éléments.", ""},
        {"List", "sort", "sort(Comparator c)", "Trie la liste selon le comparateur.", "le comparateur du tri"},
        {"Map", "put", "put(K clé, V valeur)", "Associe la valeur à la clé, renvoie l'ancienne.", "la clé|la valeur à associer"},
        {"Map", "get", "get(Object clé)", "La valeur associée à la clé, null sinon.", "la clé cherchée"},
        {"Map", "getOrDefault", "getOrDefault(Object clé, V défaut)", "La valeur associée à la clé, ou la valeur par défaut.", "la clé cherchée|la valeur par défaut"},
        {"Map", "remove", "remove(Object clé)", "Supprime et renvoie la valeur associée à la clé.", "la clé à supprimer"},
        {"Map", "containsKey", "containsKey(Object clé)", "Vrai si la clé est présente.", "la clé cherchée"},
        {"Map", "containsValue", "containsValue(Object valeur)", "Vrai si la valeur est présente.", "la valeur cherchée"},
        {"Map", "size", "size()", "Le nombre d'associations.", ""},
        {"Map", "isEmpty", "isEmpty()", "Vrai si la carte ne contient aucune association.", ""},
        {"Map", "keySet", "keySet()", "L'ensemble des clés.", ""},
        {"Map", "values", "values()", "La collection des valeurs.", ""},
        {"Objects", "requireNonNull", "requireNonNull(T o)", "Renvoie l'objet, lève une exception s'il est null.", "l'objet à vérifier"},
        {"Objects", "requireNonNull", "requireNonNull(T o, String message)", "Renvoie l'objet, lève l'exception avec le message s'il est null.", "l'objet à vérifier|le message de l'exception"},
        {"Objects", "equals", "equals(Object a, Object b)", "Vrai si les deux objets sont égaux (null-safe).", "le premier objet|le second objet"},
        {"Objects", "hash", "hash(Object... valeurs)", "Un code de hachage combiné des valeurs.", "les valeurs à combiner"},
    };

    /** Récepteurs de la bibliothèque → (méthode → surcharges). */
    private static final Map<String, Map<String, List<Entry>>> PAR_RÉCEPTEUR = new HashMap<>();
    /** Index par nom nu → toutes les surcharges de la bibliothèque. */
    private static final Map<String, List<Entry>> PAR_NOM = new LinkedHashMap<>();

    static {
        for (String[] t : TABLE) {
            List<String> params = new ArrayList<>();
            List<String> docs = new ArrayList<>();
            int open = t[2].indexOf('(');
            int close = t[2].lastIndexOf(')');
            if (open >= 0 && close > open + 1) {
                for (String p : t[2].substring(open + 1, close).split(",")) {
                    String label = p.trim();
                    if (!label.isEmpty()) {
                        params.add(label);
                        docs.add("");
                    }
                }
            }
            String[] pd = t[4].isEmpty() ? new String[0] : t[4].split("\\|");
            for (int i = 0; i < pd.length && i < docs.size(); i++) {
                if (!pd[i].isEmpty()) docs.set(i, pd[i]);
            }
            Entry e = new Entry(t[2], t[3], params, docs);
            PAR_RÉCEPTEUR.computeIfAbsent(t[0], k -> new HashMap<>())
                    .computeIfAbsent(t[1], k -> new ArrayList<>()).add(e);
            PAR_NOM.computeIfAbsent(t[1], k -> new ArrayList<>()).add(e);
        }
        // Alias : ArrayList sert la table List, HashMap la table Map,
        // System.err la table d'impression.
        PAR_RÉCEPTEUR.put("ArrayList", PAR_RÉCEPTEUR.get("List"));
        PAR_RÉCEPTEUR.put("HashMap", PAR_RÉCEPTEUR.get("Map"));
        PAR_RÉCEPTEUR.put("System.err", PAR_RÉCEPTEUR.get("System.out"));
    }

    // ════════════════════════════════════════════════════════════════
    // Résolution à la position du caret
    // ════════════════════════════════════════════════════════════════

    /**
     * Résout l'aide de signature du call-site vivant sous le caret.
     *
     * @param text  texte complet du document
     * @param caret offset du caret
     * @return les surcharges candidates et le paramètre actif, ou null
     *         si le caret n'est pas dans un appel ou qu'aucune
     *         signature n'est connue pour la méthode appelée.
     */
    public static Result resolve(String text, int caret) {
        if (text == null || caret <= 0 || caret > text.length()) return null;
        int open = trouverOuvrante(text, caret);
        if (open < 0) return null;
        String[] appel = récepteurEtNom(text, open);
        if (appel == null) return null;
        List<Entry> sigs = candidats(text, appel[0], appel[1]);
        if (sigs.isEmpty()) return null;
        return new Result(sigs, compterVirgules(text, open, caret));
    }

    /**
     * Trouve la '(' non appariée de l'appel englobant le caret — la
     * PLUS INTERNE des parenthèses encore ouvertes (l'appel EN COURS
     * DE FRAPPE : dans {@code println(max(1, } c'est {@code max} qui
     * vit sous le caret, pas {@code println}), avec une PILE dont
     * chaque fermante dépile — les appels déjà fermés
     * ({@code println(max(1, 2), } laissent la place à l'appel
     * externe), et chaînes et commentaires masqués ; les frontières
     * d'instruction ({@code ;} {@code {}) vident la pile.
     */
    static int trouverOuvrante(String text, int caret) {
        boolean[] masque = masquer(text, caret);
        int[] pile = new int[64];
        int sommet = 0;
        for (int i = 0; i < caret; i++) {
            if (masque[i]) continue;
            char c = text.charAt(i);
            if (c == ';' || c == '{') {
                sommet = 0; // frontière d'instruction
            } else if (c == '(') {
                if (sommet < pile.length) pile[sommet++] = i;
            } else if (c == ')') {
                if (sommet > 0) sommet--;
            }
        }
        return sommet > 0 ? pile[sommet - 1] : -1;
    }

    /**
     * Masque chaînes et commentaires : true aux positions où le
     * caractère ne compte PAS (dans un littéral ou un commentaire).
     */
    private static boolean[] masquer(String text, int limite) {
        boolean[] out = new boolean[limite];
        boolean inString = false, inLine = false, inBlock = false;
        char quote = '\0';
        for (int i = 0; i < limite; i++) {
            char c = text.charAt(i);
            if (inLine) {
                out[i] = true;
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                out[i] = true;
                if (c == '*' && i + 1 < limite && text.charAt(i + 1) == '/') {
                    out[i + 1] = true;
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out[i] = true;
                if (c == '\\') {
                    if (i + 1 < limite) out[i + 1] = true;
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < limite && text.charAt(i + 1) == '/') {
                out[i] = true;
                inLine = true;
                if (i + 1 < limite) out[i + 1] = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < limite && text.charAt(i + 1) == '*') {
                out[i] = true;
                inBlock = true;
                if (i + 1 < limite) out[i + 1] = true;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out[i] = true;
            }
        }
        return out;
    }

    /**
     * Décompose l'appel : la chaîne pointée complète avant la '(' —
     * {@code System.out.println} — donne {@code ["System.out",
     * "println"]}, un appel nu {@code calculer} donne
     * {@code [null, "calculer"]}. Null si aucun identifiant.
     */
    private static String[] récepteurEtNom(String text, int open) {
        int end = open;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        List<String> segments = new ArrayList<>();
        int cursor = end;
        while (cursor > 0) {
            int start = cursor;
            while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1))
                    || text.charAt(start - 1) == '_')) {
                start--;
            }
            if (start == cursor) break;
            segments.add(0, text.substring(start, cursor));
            cursor = start;
            while (cursor > 0 && Character.isWhitespace(text.charAt(cursor - 1))) cursor--;
            if (cursor > 0 && text.charAt(cursor - 1) == '.') {
                cursor--;
                while (cursor > 0 && Character.isWhitespace(text.charAt(cursor - 1))) cursor--;
            } else {
                break;
            }
        }
        if (segments.isEmpty()) return null;
        if (segments.size() == 1) return new String[]{null, segments.get(0)};
        StringBuilder recv = new StringBuilder(segments.get(0));
        for (int i = 1; i < segments.size() - 1; i++) recv.append('.').append(segments.get(i));
        return new String[]{recv.toString(), segments.get(segments.size() - 1)};
    }

    /** Compte les virgules de profondeur 0 entre la '(' et le caret. */
    static int compterVirgules(String text, int open, int caret) {
        boolean[] masque = masquer(text, caret);
        int depth = 0, commas = 0;
        for (int i = open + 1; i < caret; i++) {
            if (masque[i]) continue;
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) commas++;
        }
        return commas;
    }

    // ════════════════════════════════════════════════════════════════
    // Candidats : bibliothèque, buffer, repli par nom
    // ════════════════════════════════════════════════════════════════

    private static List<Entry> candidats(String text, String récepteur, String nom) {
        if (récepteur != null) {
            // 1. Récepteur de la bibliothèque (System.out, Math, String…).
            Map<String, List<Entry>> table = PAR_RÉCEPTEUR.get(récepteur);
            if (table != null) {
                List<Entry> out = table.get(nom);
                if (out != null) return out;
                return new ArrayList<>();
            }
            // 2. Variable déclarée dans le buffer → son type.
            Buffer buffer = new Buffer(text);
            String type = buffer.variables.get(récepteur);
            if (type != null) {
                List<Entry> biblio = PAR_RÉCEPTEUR.containsKey(type)
                        ? PAR_RÉCEPTEUR.get(type).get(nom)
                        : null;
                if (biblio != null) return biblio;
                // Type déclaré dans le fichier → ses méthodes propres.
                List<Entry> propres = buffer.méthodesDe(type, nom);
                if (!propres.isEmpty()) return propres;
                return new ArrayList<>();
            }
            // 3. Classe déclarée (appel statique) ou this.
            if ("this".equals(récepteur) && buffer.premièreClasse != null) {
                return buffer.méthodesDe(buffer.premièreClasse, nom);
            }
            return buffer.méthodesDe(récepteur, nom);
        }
        // Appel nu : les méthodes déclarées du buffer d'abord, puis le
        // repli par nom dans la bibliothèque (println, append, max…).
        Buffer buffer = new Buffer(text);
        List<Entry> propres = buffer.méthodesParNom(nom);
        if (!propres.isEmpty()) return propres;
        List<Entry> biblio = PAR_NOM.get(nom);
        return biblio != null ? biblio : new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════
    // Analyse légère du buffer — classes, méthodes, variables
    // ════════════════════════════════════════════════════════════════

    private static final Pattern CLASSE = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern DÉCL_VAR = Pattern.compile(
            "(?:(?:public|private|protected|static|final)\\s+)?"
            + "([A-Z][\\w.$]*)(?:\\s*<[^<>]*>)?\\s*(?:\\[\\])?\\s+(\\w+)\\s*(?:=[^=]|;|,|\\))");
    private static final Pattern DÉCL_NEW = Pattern.compile(
            "\\b(\\w+)\\s*=\\s*new\\s+([A-Z][\\w.$]*)");
    private static final Pattern JAVADOC_PARAM = Pattern.compile(
            "@param\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s+([^*@\\n]+)");
    private static final java.util.Set<String> MOTS_CLÉS = new java.util.HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "return", "new", "do",
            "try", "else", "throw", "assert", "synchronized", "case", "super", "this"));

    /**
     * Un identifiant Java — Unicode : les lettres accentuées sont des
     * noms de méthode légitimes ({@code créer}, {@code démarrer}).
     */
    private static boolean estIdentifiant(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    /** Une méthode déclarée dans le buffer. */
    private static final class MéthodeBuffer extends Entry {
        final String classe;
        MéthodeBuffer(String classe, String label, String doc,
                List<String> params, List<String> paramDocs) {
            super(label, doc, params, paramDocs);
            this.classe = classe;
        }
    }

    /** Vue légère du buffer : méthodes, variables, classes. */
    private static final class Buffer {
        final List<MéthodeBuffer> méthodes = new ArrayList<>();
        final Map<String, String> variables = new HashMap<>();
        final String premièreClasse;

        Buffer(String text) {
            String classeCourante = null;
            String première = null;
            boolean[] masque = masquer(text, text.length());
            // La javadoc se lit sur le texte BRUT (masquée dans la
            // grille), les déclarations sur la grille masquée.
            String[] lignes = text.split("\n", -1);
            Map<String, String> paramsEnAttente = new HashMap<>();
            int offset = 0;
            boolean dansBloc = false;
            for (String ligne : lignes) {
                String grille = masqueLigne(text, masque, offset, ligne.length());
                String brute = ligne.trim();
                // Javadoc @param — collectionnées jusqu'à la déclaration.
                Matcher jp = JAVADOC_PARAM.matcher(brute);
                if (dansBloc && jp.find()) {
                    paramsEnAttente.put(jp.group(1), jp.group(2).trim());
                }
                if (brute.contains("/*")) dansBloc = true;
                if (brute.contains("*/")) {
                    dansBloc = brute.indexOf("/*") < 0
                            && brute.lastIndexOf("*/") > brute.lastIndexOf("/*");
                }
                Matcher mc = CLASSE.matcher(grille);
                if (mc.find()) {
                    classeCourante = mc.group(1);
                    if (première == null) première = classeCourante;
                }
                MéthodeBuffer m = méthodeDeLigne(grille, classeCourante, paramsEnAttente);
                if (m != null) méthodes.add(m);
                if (m != null || brute.endsWith(";") || brute.endsWith("}")) {
                    paramsEnAttente.clear();
                }
                // Variables déclarées — TOUTES les occurrences de la
                // ligne (une déclaration par instruction, même plusieurs
                // par ligne) : Type nom = / ; / , — les types génériques
                // simples (List<String>) sont réduits à leur base.
                Matcher dv = DÉCL_VAR.matcher(grille);
                while (dv.find()) variables.put(dv.group(2), dv.group(1));
                Matcher dn = DÉCL_NEW.matcher(grille);
                while (dn.find()) variables.putIfAbsent(dn.group(1), dn.group(2));
                offset += ligne.length() + 1;
            }
            premièreClasse = première;
        }

        /** La grille masquée d'une ligne (positions locales). */
        private static String masqueLigne(String text, boolean[] masque, int offset, int len) {
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len && offset + i < text.length(); i++) {
                sb.append(masque[offset + i] ? ' ' : text.charAt(offset + i));
            }
            return sb.toString();
        }

        /**
         * Une ligne de déclaration de méthode : un identifiant avant
         * des parenthèses APPARIÉES, suivies d'un bloc {@code {} —
         * même ouvert et refermé sur la même ligne
         * ({@code void f() { return null; }}) — sans tête de contrôle
         * (if/for/while…) ni affectation. Les paramètres sont le texte
         * brut entre parenthèses (aplati).
         */
        private static MéthodeBuffer méthodeDeLigne(String grille, String classe,
                Map<String, String> paramsDocs) {
            String t = grille.trim();
            if (t.isEmpty()) return null;
            int open = t.indexOf('(');
            if (open < 0) return null;
            int depth = 0;
            int close = -1;
            for (int i = open; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            if (close < 0) return null;
            // La queue de la fermente : un bloc (le corps, même
            // refermé sur la même ligne) ou une clause throws — une
            // instruction nue (« ; » d'appel, « , » d'argument) n'est
            // PAS une déclaration.
            String queue = t.substring(close + 1).trim();
            if (!queue.startsWith("{") && !queue.startsWith("throws")) return null;
            String tête = t.substring(0, open).trim();
            if (tête.isEmpty() || tête.contains("=")) return null;
            String[] mots = tête.split("\\s+");
            String nom = mots[mots.length - 1];
            if (!estIdentifiant(nom) || MOTS_CLÉS.contains(nom)) return null;
            // Une tête d'un seul mot : constructeur de la classe
            // courante only — tout autre mot seul est un appel nu.
            if (mots.length == 1 && (classe == null || !nom.equals(classe))) {
                return null;
            }
            String args = t.substring(open + 1, close).trim();
            List<String> params = new ArrayList<>();
            List<String> docs = new ArrayList<>();
            if (!args.isEmpty()) {
                int d = 0, start = 0;
                for (int i = 0; i <= args.length(); i++) {
                    char c = i < args.length() ? args.charAt(i) : ',';
                    if (c == '(' || c == '[' || c == '{' || c == '<') d++;
                    else if (c == ')' || c == ']' || c == '}' || c == '>') d--;
                    else if ((c == ',' && d == 0) || i == args.length()) {
                        String p = args.substring(start, i).trim();
                        if (!p.isEmpty()) {
                            params.add(p);
                            String[] pm = p.split("\\s+");
                            docs.add(paramsDocs.getOrDefault(
                                    pm[pm.length - 1].replace("...", ""), ""));
                        }
                        start = i + 1;
                    }
                }
            }
            StringBuilder libellé = new StringBuilder(nom).append('(');
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) libellé.append(", ");
                libellé.append(params.get(i));
            }
            return new MéthodeBuffer(classe, libellé.append(')').toString(),
                    "Méthode " + nom + " déclarée dans ce fichier.",
                    params, docs);
        }

        /** Les méthodes déclarées d'une classe donnée, par nom. */
        List<Entry> méthodesDe(String classe, String nom) {
            List<Entry> out = new ArrayList<>();
            for (MéthodeBuffer m : méthodes) {
                if (nom.equals(méthodeNom(m.label)) && classe != null
                        && classe.equals(m.classe)) {
                    out.add(m);
                }
            }
            return out;
        }

        /** Toutes les méthodes déclarées portant ce nom, toutes classes. */
        List<Entry> méthodesParNom(String nom) {
            List<Entry> out = new ArrayList<>();
            for (MéthodeBuffer m : méthodes) {
                if (nom.equals(méthodeNom(m.label))) out.add(m);
            }
            return out;
        }

        /** Le nom avant la '(' du libellé. */
        private static String méthodeNom(String label) {
            int open = label.indexOf('(');
            if (open <= 0) return label;
            String avant = label.substring(0, open);
            int point = avant.lastIndexOf('.');
            return point >= 0 ? avant.substring(point + 1) : avant;
        }
    }
}

package jo.codeeditor.demo.lsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les INDICES LAMBDA du mini Java de la démo — le type fantôme de
 * chaque paramètre de lambda, tissé dans la ligne au moment de la
 * lecture.
 *
 * <p>Un lambda Java déclare ses paramètres SANS type
 * ({@code noms.forEach(n -> …)}) : le type vit dans l'interface
 * fonctionnelle attendue par l'appel. Ce moteur le retrouve et le
 * sert en inlay hint : {@code String} s'affiche en pâle DEVANT le
 * {@code n} — l'indice visuel des éditeurs de bureau, sur mobile.</p>
 *
 * <p>La résolution est CONSERVATRICE — jamais de type inventé :</p>
 * <ol>
 *   <li>le lambda doit être un ARGUMENT D'APPEL (le {@code ->} vit
 *       dans les parenthèses d'un appel résoluble ; les flèches de
 *       {@code switch} sont repoussées par le token qui précède
 *       l'introduisant) ;</li>
 *   <li>la signature de la méthode appelée vient du MÊME moteur que
 *       l'aide de signature ({@link MiniJavaSignatures} — bibliothèque
 *       embarquée, méthodes déclarées, repli classpath par réflexion
 *       avec ses types génériques réels) ;</li>
 *   <li>le paramètre à la position du lambda doit être une interface
 *       fonctionnelle CONNUE ({@code Consumer}, {@code Predicate},
 *       {@code Function}, {@code Comparator}…) et son CÂBLAGE doit
 *       compter autant de paramètres lambda que l'introduisant ;</li>
 *   <li>le type de chaque paramètre prend l'argument générique
 *       EXPLICITE du fonctionnel s'il est concret, sinon le générique
 *       de la DÉCLARATION du récepteur ({@code List<String> noms} →
 *       l'argument 0 vaut {@code String}) ; sans l'un ni l'autre,
 *       PAS d'indice (zéro devinette).</li>
 * </ol>
 *
 * <p>Pur JVM, aucune dépendance Android : testable directement en
 * JUnit, servi par le mini serveur ({@code textDocument/inlayHint},
 * borné aux lignes visibles — le client ne demande que la plage à
 * l'écran).</p>
 */
public final class MiniJavaLambdas {

    private MiniJavaLambdas() {}

    // ════════════════════════════════════════════════════════════════
    // Types de sortie
    // ════════════════════════════════════════════════════════════════

    /**
     * Un indice — le type d'un paramètre de lambda : l'offset du NOM
     * (le texte fantôme s'insère juste devant), le type résolu et la
     * phrase complète du tooltip (appui long).
     */
    public static final class Indice {
        /** L'offset du début du NOM du paramètre dans le document. */
        public final int offset;
        /** Le type résolu, ex. {@code String}. */
        public final String type;
        /** La phrase du tooltip : le paramètre, son type, la source. */
        public final String info;

        Indice(int offset, String type, String info) {
            this.offset = offset;
            this.type = type;
            this.info = info;
        }

        @Override
        public String toString() {
            return type + "@" + offset;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Interfaces fonctionnelles connues → câblage des paramètres
    // ════════════════════════════════════════════════════════════════

    /**
     * Le CÂBLAGE des interfaces fonctionnelles usuelles : pour chaque
     * paramètre de lambda, l'INDEX de l'argument générique qui le
     * porte. {@code Consumer<T>} attend UN paramètre porté par
     * l'argument 0 ; {@code Comparator<T>} en attend DEUX, portés par
     * le MÊME argument 0 ; {@code BiFunction<T, U, R>} deux (l'argument
     * 2 est le RETOUR, jamais un paramètre) ; {@code Runnable} aucun.
     * Une interface absente du câblage ne sert RIEN.
     */
    private static final Map<String, int[]> FONCTIONNELLES = new HashMap<>();

    static {
        FONCTIONNELLES.put("Consumer", new int[]{0});
        FONCTIONNELLES.put("BiConsumer", new int[]{0, 1});
        FONCTIONNELLES.put("Predicate", new int[]{0});
        FONCTIONNELLES.put("BiPredicate", new int[]{0, 1});
        FONCTIONNELLES.put("Function", new int[]{0});
        FONCTIONNELLES.put("BiFunction", new int[]{0, 1});
        FONCTIONNELLES.put("Supplier", new int[]{});
        FONCTIONNELLES.put("UnaryOperator", new int[]{0});
        FONCTIONNELLES.put("BinaryOperator", new int[]{0, 0});
        FONCTIONNELLES.put("Comparator", new int[]{0, 0});
        FONCTIONNELLES.put("Runnable", new int[]{});
        FONCTIONNELLES.put("Callable", new int[]{});
    }

    // ════════════════════════════════════════════════════════════════
    // Point d'entrée
    // ════════════════════════════════════════════════════════════════

    /**
     * Les indices des paramètres lambda dont la FLÈCHE vit dans la
     * plage demandée — liste vide si la plage ne contient aucun lambda
     * résoluble.
     *
     * @param text  texte complet du document
     * @param début offset de début de la plage (inclusive)
     * @param fin   offset de fin de la plage (exclusive)
     */
    public static List<Indice> indices(String text, int début, int fin) {
        List<Indice> out = new ArrayList<>();
        if (text == null || début < 0 || fin <= début) return out;
        boolean[] masque = masquer(text, text.length());
        // La flèche « -> » vit dans la grille (jamais dans une chaîne
        // ni un commentaire — masqués).
        for (int i = début; i < fin - 1 && i < text.length() - 1; i++) {
            if (masque[i] || masque[i + 1]) continue;
            if (text.charAt(i) == '-' && text.charAt(i + 1) == '>') {
                indicesDUnLambda(text, masque, i, out);
            }
        }
        return out;
    }

    /**
     * Les indices d'UN lambda : lit l'introduisant de paramètres avant
     * la flèche, résout l'appel englobant, substitue les génériques.
     */
    private static void indicesDUnLambda(String text, boolean[] masque,
            int flèche, List<Indice> out) {
        // ── 1. L'introduisant : « (a, b) » ou l'identifiant nu. ──
        int p = flèche;
        while (p > 0 && Character.isWhitespace(text.charAt(p - 1))) p--;
        List<int[]> paramètres = null; // {début du nom, fin du nom}
        int introduisant = -1;
        if (p > 0 && text.charAt(p - 1) == ')') {
            // La paire de parenthèses de l'introduisant.
            int depth = 0;
            int open = -1;
            for (int i = p - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == ')') depth++;
                else if (c == '(') {
                    depth--;
                    if (depth == 0) {
                        open = i;
                        break;
                    }
                }
            }
            if (open < 0) return;
            paramètres = paramètresDéclarés(text, masque, open + 1, p - 1);
            introduisant = open;
        } else if (p > 0
                && Character.isJavaIdentifierPart(text.charAt(p - 1))) {
            int fin = p;
            int début = p;
            while (début > 0
                    && Character.isJavaIdentifierPart(text.charAt(début - 1))) {
                début--;
            }
            if (début == fin
                    || !Character.isJavaIdentifierStart(text.charAt(début))) {
                return;
            }
            paramètres = new ArrayList<>();
            paramètres.add(new int[]{début, fin});
            introduisant = début;
        } else {
            return; // « () -> » : rien à deviner, rien à afficher.
        }
        if (paramètres == null || paramètres.isEmpty()) return;

        // ── 2. Le garde-fou anti-switch : le token AVANT ──
        // l'introduisant doit être une frontière d'argument — « ( »,
        // « , », « = » ou « { » — pas un mot (« case X -> » est
        // repoussé ici).
        int k = introduisant;
        while (k > 0 && Character.isWhitespace(text.charAt(k - 1))) k--;
        if (k == 0) return;
        char avant = text.charAt(k - 1);
        if (avant != '(' && avant != ',' && avant != '=' && avant != '{') {
            return;
        }

        // ── 3. L'appel englobant : l'ouvrante non fermée avant la
        //       flèche (la pile de trouverOuvrante dépile à chaque
        //       ')', les frontières d'instruction la vident). ──
        int open = MiniJavaSignatures.trouverOuvrante(text, flèche);
        if (open < 0 || open >= introduisant) return;
        String[] appel = MiniJavaSignatures.récepteurEtNom(text, open);
        if (appel == null) return;

        // ── 4. La position du lambda dans les arguments. ──
        int indexArgument = argumentPosition(text, masque, open, introduisant);
        if (indexArgument < 0) return;

        // ── 5. La signature : le paramètre à cette position doit
        //       câbler une interface fonctionnelle CONNUE, avec le
        //       BON nombre de paramètres lambda. ──
        List<MiniJavaSignatures.Entry> entrées = MiniJavaSignatures.candidats(
                text, appel[0], appel[1]);
        if (entrées.isEmpty()) return;
        int[] câblage = null;
        String[] argsExplicites = null;
        String libelléFonctionnel = null;
        for (MiniJavaSignatures.Entry e : entrées) {
            if (indexArgument >= e.params.size()) continue;
            String libellé = e.params.get(indexArgument);
            String type = typeDuLibellé(libellé);
            if (type == null) continue;
            int[] connus = FONCTIONNELLES.get(baseDuType(type));
            if (connus == null || connus.length != paramètres.size()) continue;
            câblage = connus;
            argsExplicites = argsGénériques(type);
            libelléFonctionnel = libellé;
            break;
        }
        if (câblage == null) return;

        // ── 6. Substitution : chaque paramètre prend l'argument
        //       générique EXPLICITE du fonctionnel s'il est concret,
        //       sinon le générique correspondant de la DÉCLARATION du
        //       récepteur — jamais rien d'inventé. ──
        String[] déclarés = génériquesDuRécepteur(text, masque, appel[0]);
        String[] types = new String[paramètres.size()];
        for (int i = 0; i < paramètres.size(); i++) {
            int idx = câblage[i];
            String t = null;
            if (argsExplicites != null && idx < argsExplicites.length
                    && estTypeConcret(argsExplicites[idx])) {
                t = argsExplicites[idx];
            }
            if (t == null && déclarés != null && idx < déclarés.length
                    && estTypeConcret(déclarés[idx])) {
                t = déclarés[idx];
            }
            if (t == null) return; // une inconnue : AUCUN indice
            types[i] = t;
        }

        // ── 7. Les indices : un par paramètre de lambda. ──
        for (int i = 0; i < paramètres.size(); i++) {
            int[] nom = paramètres.get(i);
            String nomP = text.substring(nom[0], nom[1]);
            String info = nomP + " : " + types[i] + " — paramètre de lambda, "
                    + "type inféré de " + (libelléFonctionnel != null
                            ? libelléFonctionnel.trim() : "l'appel") + ".";
            out.add(new Indice(nom[0], types[i], info));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Lectures
    // ════════════════════════════════════════════════════════════════

    /**
     * Les paramètres DÉCLARÉS SANS TYPE d'un introduisant
     * {@code (a, b)} : les morceaux identifiants nus des positions de
     * profondeur 0. Un paramètre typé ({@code (String s)}) est EXCLU —
     * son type est déjà écrit, l'indice n'apporterait rien. Null si
     * vide.
     */
    private static List<int[]> paramètresDéclarés(String text, boolean[] masque,
            int début, int fin) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = début;
        for (int i = début; i <= fin; i++) {
            char c = i < fin ? text.charAt(i) : ',';
            if (i < fin && masque[i]) continue;
            if (c == '(' || c == '[' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '>') depth--;
            else if (c == ',' && depth == 0) {
                int s = start;
                int e = i;
                while (s < e && Character.isWhitespace(text.charAt(s))) s++;
                while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
                String morceau = text.substring(s, e);
                if (!morceau.isEmpty() && estIdentifiant(morceau)) {
                    out.add(new int[]{s, e});
                }
                start = i + 1;
            }
        }
        return out;
    }

    /**
     * L'index d'argument (base 0) de la position donnée dans la liste
     * d'arguments de l'ouvrante : les virgules de profondeur 0 avant
     * l'introduisant. -1 si la position n'est pas cohérente.
     */
    private static int argumentPosition(String text, boolean[] masque,
            int open, int position) {
        int depth = 0;
        int commas = 0;
        for (int i = open + 1; i < position && i < text.length(); i++) {
            if (masque[i]) continue;
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) commas++;
        }
        return depth >= 0 ? commas : -1;
    }

    /**
     * La PARTIE TYPE d'un libellé de paramètre : tout sauf le dernier
     * mot — {@code Consumer<E> action} → {@code Consumer<E>},
     * {@code Comparator c} → {@code Comparator} ; null si le libellé
     * est un identifiant nu (pas de type écrit).
     */
    private static String typeDuLibellé(String libellé) {
        String s = libellé.trim();
        int fin = s.length();
        while (fin > 0
                && Character.isJavaIdentifierPart(s.charAt(fin - 1))) {
            fin--;
        }
        if (fin == s.length()) return null;
        String type = s.substring(0, fin).trim();
        return type.isEmpty() ? null : type;
    }

    /** La base d'un type écrit : avant les génériques, SANS paquet
     *  ({@code java.util.function.Consumer<String>} → {@code Consumer}). */
    private static String baseDuType(String type) {
        String base = type;
        int ouvrant = base.indexOf('<');
        if (ouvrant >= 0) base = base.substring(0, ouvrant);
        int point = base.lastIndexOf('.');
        return point >= 0 ? base.substring(point + 1) : base;
    }

    /**
     * Les arguments GÉNÉRIQUES écrits d'un type ({@code Consumer<? super E>}
     * → {@code ["E"]}) — jokers retirés ; null s'il n'y en a pas.
     */
    private static String[] argsGénériques(String type) {
        int ouvrant = type.indexOf('<');
        if (ouvrant < 0) return null;
        int fermant = type.lastIndexOf('>');
        if (fermant <= ouvrant) return null;
        String[] args = type.substring(ouvrant + 1, fermant).split(",");
        for (int i = 0; i < args.length; i++) {
            args[i] = args[i].trim()
                    .replace("? super ", "").replace("? extends ", "")
                    .replace("?", "").trim();
        }
        return args;
    }

    /**
     * Un type est-il CONCRET ? Une variable de type ({@code E},
     * {@code T}, {@code K}, {@code V}, {@code U}, {@code R}) ne
     * l'est pas — une seule lettre.
     */
    private static boolean estTypeConcret(String t) {
        if (t == null || t.isEmpty()) return false;
        if (t.length() == 1) return false;
        return !t.contains(" ");
    }

    /** « Type<Nom, Nom> variable » — les génériques CAPTURÉS. */
    private static final Pattern DÉCL_GÉNÉRIQUES = Pattern.compile(
            "([A-Z][\\w.$]*)\\s*(<[^<>]*>)?\\s+([\\p{L}_][\\p{L}\\p{N}_]*)"
                    + "\\s*(?:=[^=]|;|,|\\))");

    /**
     * Les arguments GÉNÉRIQUES de la déclaration du récepteur
     * ({@code List<String> noms} → {@code ["String"]},
     * {@code Map<String, Integer>} → deux) — null si le récepteur
     * n'est pas une variable déclarée AVEC génériques. La lecture se
     * fait sur la GRILLE MASQUÉE : jamais de génériques fantômes
     * sortis d'une chaîne ou d'un commentaire.
     */
    private static String[] génériquesDuRécepteur(String text,
            boolean[] masque, String récepteur) {
        if (récepteur == null) return null;
        StringBuilder grille = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            grille.append(masque[i] ? ' ' : text.charAt(i));
        }
        Matcher m = DÉCL_GÉNÉRIQUES.matcher(grille);
        while (m.find()) {
            if (!récepteur.equals(m.group(3))) continue;
            String args = m.group(2);
            if (args == null) return null;
            String[] out = args.substring(1, args.length() - 1).split(",");
            for (int i = 0; i < out.length; i++) {
                out[i] = out[i].trim()
                        .replace("? super ", "").replace("? extends ", "")
                        .replace("?", "").trim();
            }
            return out;
        }
        return null;
    }

    /** Un identifiant Java Unicode complet. */
    private static boolean estIdentifiant(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    /** Masque chaînes et commentaires (offsets préservés). */
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
                if (c == '*' && i + 1 < limite
                        && text.charAt(i + 1) == '/') {
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

    /** La table des fonctionnelles — exposition test (copie). */
    static Map<String, int[]> fonctionnelles() {
        return new HashMap<>(FONCTIONNELLES);
    }

    /** Exclusion des tests : la liste des bases connues. */
    static List<String> basesConnues() {
        return Arrays.asList("Consumer", "BiConsumer", "Predicate",
                "BiPredicate", "Function", "BiFunction", "Supplier",
                "UnaryOperator", "BinaryOperator", "Comparator",
                "Runnable", "Callable");
    }
}

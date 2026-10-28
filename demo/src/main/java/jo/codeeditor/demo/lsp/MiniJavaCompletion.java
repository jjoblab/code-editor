package jo.codeeditor.demo.lsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moteur de complétion CONTEXTUELLE du mini Java de la démo — trois
 * familles de candidats qu'un serveur à base fixe ne peut pas produire
 * et que les éditeurs de référence servent :
 *
 * <ol>
 *   <li><b>Constantes d'enum</b> — {@code Couleur.} sert les constantes
 *       déclarées ({@code ROUGE}, {@code VERT}… — kind LSP EnumMember,
 *       badge « # » du popup), les méthodes propres de l'enum déclarées
 *       dans le fichier, puis les deux méthodes de protocole
 *       {@code values()} et {@codevalueOf(String)}. Le récepteur peut
 *       être le nom de l'enum ({@code Couleur.}) ou une VARIABLE de ce
 *       type ({@code Couleur c = …; c.}).</li>
 *   <li><b>Attributs d'annotation</b> — le caret dans les parenthèses
 *       d'une annotation déclarée dans le buffer
 *       ({@code @interface Trace { … }}) sert ses attributs avec le
 *       texte d'insertion {@code nom=} : {@code @Trace(nom = "x", |}
 *       propose les attributs pas encore posés (type et valeur par
 *       défaut au détail). Une petite table couvre les annotations du
 *       JDK à attribut ({@code value} de {@code @SuppressWarnings}…).</li>
 *   <li><b>Arguments nommés</b> — le caret à une POSITION D'ARGUMENT
 *       d'un appel ({@code calculer(|)}) sert les noms des paramètres
 *       de la surcharge résolue, insertion {@code base=}. La
 *       résolution réutilise {@link MiniJavaSignatures} (bibliothèque
 *       embarquée + méthodes déclarées + javadoc {@code @param}) : les
 *       arguments nommés et l'aide de signature racontent la MÊME
 *       histoire, paramètre actif compris. Les noms déjà posés avant
 *       le caret ({@code foo(a = 1, |} ne re-propose pas {@code a=})
 *       sont exclus ; les parenthèses de DÉCLARATION
 *       ({@code void f(|)}), les têtes de contrôle ({@code if (}) et
 *       les positions de valeur ({@code foo(a = |}) ne déclenchent
 *       rien.</li>
 * </ol>
 *
 * <p><b>Méthode :</b> détection par scan arrière masqué (chaînes et
 * commentaires ne comptent jamais), puis une vue légère du buffer
 * (enums avec leurs constantes et méthodes, annotations avec leurs
 * attributs, variables typées) construite en régions sur le texte
 * masqué — même discipline que {@link MiniJavaSignatures} : pur JVM,
 * aucune dépendance Android, testable directement en JUnit, et
 * PARTAGÉ par le mini serveur LSP ({@code textDocument/completion}) et
 * le moteur maison de la démo — les deux chemins servent les mêmes
 * candidats.</p>
 */
public final class MiniJavaCompletion {

    private MiniJavaCompletion() {}

    // ════════════════════════════════════════════════════════════════
    // Types de sortie
    // ════════════════════════════════════════════════════════════════

    /**
     * Un candidat contextuel — les champs du protocole LSP que le
     * popup sait honorer : kind numérique (badge par genre), détail,
     * texte d'insertion (peut différer du libellé : {@code nom=}) et
     * tag de raffinement transporté par le champ {@code data}.
     */
    public static final class Candidate {
        /** Libellé affiché (et clé de filtrage du popup). */
        public final String label;
        /** Kind LSP (EnumMember = 20, Property = 10, Variable = 6…). */
        public final int kind;
        /** Détail sous le libellé (type, protocole, signature). */
        public final String detail;
        /** Documentation (bande doc du popup). */
        public final String documentation;
        /** Texte inséré à l'acceptation ({@code nom=} pour les nommés). */
        public final String insertText;
        /** Raffinement de kind transporté par le champ data LSP. */
        public final String tag;

        Candidate(String label, int kind, String detail, String documentation,
                String insertText, String tag) {
            this.label = label;
            this.kind = kind;
            this.detail = detail;
            this.documentation = documentation;
            this.insertText = insertText;
            this.tag = tag;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Kind LSP : membre d'enum (badge « # »). */
    static final int KIND_ENUM_MEMBER = 20;
    /** Kind LSP : propriété (badge « F »). */
    static final int KIND_PROPERTY = 10;
    /** Kind LSP : méthode (badge « M » accent). */
    static final int KIND_METHOD = 2;
    /** Kind LSP : variable (badge « v »). */
    static final int KIND_VARIABLE = 6;

    // ════════════════════════════════════════════════════════════════
    // Point d'entrée
    // ════════════════════════════════════════════════════════════════

    /**
     * Les candidats contextuels à la position du caret — liste VIDE si
     * aucun des trois contextes ne s'applique (l'appelant garde alors
     * sa base habituelle).
     *
     * @param text  texte complet du document
     * @param caret offset du caret
     * @return les candidats dans l'ordre d'affichage : constantes
     *         d'enum avant leurs méthodes, attributs restants,
     *         arguments nommés de la surcharge résolue
     */
    public static List<Candidate> candidates(String text, int caret) {
        List<Candidate> out = new ArrayList<>();
        if (text == null || caret <= 0 || caret > text.length()) return out;
        Vue vue = new Vue(text);
        // 1. Membres d'enum : le qualificatif avant le point nomme un
        //    enum (ou une variable d'un type enum du buffer).
        if (compléterEnum(text, caret, vue, out)) return out;
        // 2. Attributs d'annotation : le caret vit dans les
        //    parenthèses d'une @annotation.
        if (compléterAnnotation(text, caret, vue, out)) return out;
        // 3. Arguments nommés : le caret vit à une position d'argument
        //    d'un appel dont la signature est connue.
        compléterArgumentsNommés(text, caret, out);
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // 1. Constantes d'enum
    // ════════════════════════════════════════════════════════════════

    /**
     * Le caractère avant le caret (espaces sautées) est-il un « . » ?
     * Le qualificatif — la chaîne d'identifiants et de points AVANT —
     * est alors lu en arrière ({@code Couleur} ou {@code demo.Couleur}).
     * Null si le point n'existe pas ou si le qualificatif est un
     * littéral numérique ({@code 1.}).
     */
    private static String qualificatifAvantPoint(String text, int caret) {
        int i = caret;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        if (i == 0 || text.charAt(i - 1) != '.') return null;
        i--;
        int end = i;
        int start = i;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.') {
                start--;
            } else {
                break;
            }
        }
        if (start == end) return null;
        String qual = text.substring(start, end);
        // Un récepteur finit par un identifiant (« a..b » est une faute
        // de frappe) qui n'est pas un littéral numérique (« 1. »).
        if (qual.endsWith(".") || qual.startsWith(".")) return null;
        if (Character.isDigit(qual.charAt(qual.length() - 1))) return null;
        return qual;
    }

    /**
     * Sert les membres d'un enum : ses constantes (badge « # »), ses
     * méthodes déclarées dans le fichier, puis les deux méthodes de
     * protocole. Retourne vrai si le récepteur nomme un enum du buffer
     * — directement ({@code Couleur.}) ou par une variable typée
     * ({@code c.}) — même sans candidat, le contexte est CONSOMMÉ et
     * coupe les familles suivantes.
     */
    private static boolean compléterEnum(String text, int caret, Vue vue,
            List<Candidate> out) {
        String qual = qualificatifAvantPoint(text, caret);
        if (qual == null) return false;
        String nom = dernierSegment(qual);
        // Récepteur = le nom de l'enum lui-même…
        EnumBuffer e = vue.enums.get(nom);
        if (e == null) {
            // … ou une VARIABLE de ce type (Couleur c = …; c.).
            String type = vue.variables.get(nom);
            if (type != null) e = vue.enums.get(dernierSegment(type));
        }
        if (e == null) return false;
        for (String constante : e.constantes) {
            out.add(new Candidate(constante, KIND_ENUM_MEMBER,
                    "constante de " + e.nom,
                    "La constante " + constante + " de l'enum " + e.nom + ".",
                    constante, null));
        }
        for (String méthode : e.méthodes) {
            out.add(new Candidate(méthodeNom(méthode), KIND_METHOD,
                    "méthode de " + e.nom,
                    "Méthode déclarée dans l'enum " + e.nom + ".",
                    méthode + (méthode.indexOf('(') >= 0 ? "" : "()"), null));
        }
        out.add(new Candidate("values", KIND_METHOD, e.nom + "[]",
                "Les constantes de l'enum " + e.nom
                        + ", dans l'ordre de déclaration.",
                "values()", null));
        out.add(new Candidate("valueOf", KIND_METHOD,
                e.nom + " valueOf(String nom)",
                "La constante de " + e.nom + " portant ce nom "
                        + "(exception si absente).",
                "valueOf()", null));
        return true;
    }

    /** Le dernier segment d'un nom qualifié (« a.b.C » → « C »). */
    private static String dernierSegment(String qualifié) {
        int p = qualifié.lastIndexOf('.');
        return p >= 0 ? qualifié.substring(p + 1) : qualifié;
    }

    /** Le nom avant la '(' d'un libellé de méthode. */
    private static String méthodeNom(String label) {
        int open = label.indexOf('(');
        return open > 0 ? label.substring(0, open) : label;
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Attributs d'annotation
    // ════════════════════════════════════════════════════════════════

    /** Attributs des annotations du JDK à attribut unique. */
    private static final Map<String, List<Attribut>> JDK_ANNOTATIONS = new HashMap<>();

    static {
        JDK_ANNOTATIONS.put("SuppressWarnings",
                Arrays.asList(new Attribut("value", "String[]", null)));
        JDK_ANNOTATIONS.put("SafeVarargs",
                Arrays.asList(new Attribut("value", "String...", null)));
    }

    /** Un attribut d'annotation : nom, type, valeur par défaut. */
    static final class Attribut {
        final String nom;
        final String type;
        final String défaut;

        Attribut(String nom, String type, String défaut) {
            this.nom = nom;
            this.type = type;
            this.défaut = défaut;
        }
    }

    /**
     * Sert les attributs d'une annotation : le caret doit vivre dans
     * les parenthèses d'une {@code @Anno(…)} à la profondeur de SES
     * arguments (pas dans un appel ou un tableau imbriqué), à une
     * position de début d'argument (après « ( » ou une virgule, sans
     * « = » déjà frappé — après le « = » c'est une position de
     * VALEUR). Les attributs déjà posés avant le caret sont exclus.
     * Retourne vrai si l'annotation est CONNUE (buffer ou JDK), même
     * quand tout est déjà posé — le contexte est consommé.
     */
    private static boolean compléterAnnotation(String text, int caret, Vue vue,
            List<Candidate> out) {
        int open = MiniJavaSignatures.trouverOuvrante(text, caret);
        if (open < 0) return false;
        // Le caret vit directement dans la liste d'arguments (pas dans
        // un appel imbriqué).
        if (profondeurRelative(text, open, caret) != 0) return false;
        // Le token avant l'ouvrante : un identifiant précédé de « @ ».
        String nom = annotationAvant(text, open);
        if (nom == null) return false;
        List<Attribut> attributs = vue.annotations.get(nom);
        if (attributs == null) attributs = JDK_ANNOTATIONS.get(nom);
        if (attributs == null) return false;
        // Position de début d'argument (pas après un « = » ni dans une
        // expression).
        if (!positionDébutArgument(text, open, caret)) return false;
        Set<String> déjàPosés = nomsDéjàPosés(text, open, caret);
        for (Attribut a : attributs) {
            if (déjàPosés.contains(a.nom)) continue;
            out.add(new Candidate(a.nom, KIND_PROPERTY, a.type
                    + (a.défaut != null ? " (défaut : " + a.défaut + ")" : ""),
                    "L'attribut " + a.nom + " de @" + nom
                            + (a.défaut != null ? " — défaut " + a.défaut : "")
                            + ".",
                    a.nom + "=", null));
        }
        return true;
    }

    /**
     * L'identifiant immédiatement avant l'offset donné est-il précédé
     * d'un « @ » (espaces permises) ? Retourne son nom, ou null.
     */
    private static String annotationAvant(String text, int open) {
        int i = open;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        int end = i;
        while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) i--;
        if (i == end) return null;
        int j = i;
        while (j > 0 && Character.isWhitespace(text.charAt(j - 1))) j--;
        if (j == 0 || text.charAt(j - 1) != '@') return null;
        return text.substring(i, end);
    }

    // ════════════════════════════════════════════════════════════════
    // 3. Arguments nommés
    // ════════════════════════════════════════════════════════════════

    /** Têtes de contrôle : leurs parenthèses ne sont pas des appels. */
    private static final Set<String> TÊTES = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "do", "try",
            "return", "new", "throw", "else", "synchronized"));

    /**
     * Mots qui, devant le nom d'une méthode, signent une DÉCLARATION
     * ({@code void f(}, {@code int f(}) — les types primitifs et
     * {@code var}.
     */
    private static final Set<String> TYPES_NUS = new HashSet<>(Arrays.asList(
            "void", "int", "long", "short", "byte", "char", "float",
            "double", "boolean", "var"));

    /**
     * Sert les arguments nommés d'un appel : la position du caret doit
     * être un début d'argument dans les parenthèses d'un VRAI appel
     * (pas une déclaration, pas une tête de contrôle, pas une
     * annotation) et la méthode appelée doit être résolue par
     * {@link MiniJavaSignatures}. Chaque surcharge dont le paramètre
     * actif existe donne SON nom de paramètre (dédupliqué par nom) —
     * le paramètre actif est calculé par le même moteur que l'aide de
     * signature, les deux popups avancent ensemble.
     */
    private static void compléterArgumentsNommés(String text, int caret,
            List<Candidate> out) {
        int open = MiniJavaSignatures.trouverOuvrante(text, caret);
        if (open < 0) return;
        if (profondeurRelative(text, open, caret) != 0) return;
        if (!positionDébutArgument(text, open, caret)) return;
        if (estDéclarationOuContrôle(text, open)) return;
        MiniJavaSignatures.Result r = MiniJavaSignatures.resolve(text, caret);
        if (r == null || r.activeParameter < 0) return;
        Set<String> déjàPosés = nomsDéjàPosés(text, open, caret);
        Set<String> servis = new HashSet<>();
        for (MiniJavaSignatures.Entry e : r.signatures) {
            if (r.activeParameter >= e.params.size()) continue;
            String nom = nomDeParamètre(e.params.get(r.activeParameter));
            if (nom == null || déjàPosés.contains(nom) || !servis.add(nom)) {
                continue;
            }
            String doc = r.activeParameter < e.paramDocs.size()
                    ? e.paramDocs.get(r.activeParameter) : "";
            out.add(new Candidate(nom, KIND_VARIABLE,
                    "argument nommé — " + méthodeNom(e.label),
                    doc.isEmpty()
                            ? "Le paramètre " + nom + " de "
                                    + méthodeNom(e.label) + "."
                            : doc,
                    nom + "=", "parameter"));
        }
    }

    /**
     * Les parenthèses ouvertes à {@code open} sont-elles celles d'une
     * DÉCLARATION de méthode ou d'une construction qui n'est pas un
     * appel ?
     *
     * <p>Trois indices, dans l'ordre :</p>
     * <ol>
     *   <li>le nom avant la parenthèse est une tête de contrôle
     *       ({@code if/for/while/…}) ;</li>
     *   <li>le mot AVANT le nom est un type nu ({@code void f(},
     *       {@code int f(}) ou un identifiant Capitalisé
     *       ({@code String valueOf(}) — deux identifiants d'affilée,
     *       c'est une déclaration en cours de frappe ; un point entre
     *       les deux ({@code Math.max(}) dément l'indice : c'est un
     *       appel statique ;</li>
     *   <li>la fermante APPARIÉE est suivie de « { » (corps) ou
     *       « throws » (clause) — la déclaration est complète sur sa
     *       ligne.</li>
     * </ol>
     */
    private static boolean estDéclarationOuContrôle(String text, int open) {
        int i = open;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        int nomEnd = i;
        while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) i--;
        if (i == nomEnd) return true; // « ( » orpheline : pas un appel
        String nom = text.substring(i, nomEnd);
        if (TÊTES.contains(nom)) return true;
        // Le mot d'avant (espaces sautées) : un identifiant ?
        int j = i;
        while (j > 0 && Character.isWhitespace(text.charAt(j - 1))) j--;
        if (j > 0 && Character.isJavaIdentifierPart(text.charAt(j - 1))) {
            int avantEnd = j;
            while (j > 0 && Character.isJavaIdentifierPart(text.charAt(j - 1))) j--;
            String avant = text.substring(j, avantEnd);
            if (TYPES_NUS.contains(avant)
                    || Character.isUpperCase(avant.charAt(0))) {
                return true; // « void f( » / « String valueOf( »
            }
        }
        // Fermante appariée suivie d'un corps ou d'une clause.
        int depth = 0;
        int close = -1;
        for (int k = open; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = k;
                    break;
                }
            }
        }
        if (close < 0) return false; // jamais fermée : frappe en cours
        int k = close;
        while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
        return text.startsWith("{", k) || text.startsWith("throws", k);
    }

    /**
     * Le nom d'un paramètre dans son libellé (« double base » →
     * « base », « int... nombres » → « nombres ») : le dernier mot
     * identifiant du libellé, sans les « … » du varargs ni les
     * génériques du type. Null si le libellé ne nomme pas son
     * paramètre.
     */
    private static String nomDeParamètre(String libellé) {
        if (libellé == null) return null;
        String s = libellé.replace("...", " ");
        int end = s.length();
        while (end > 0 && !Character.isJavaIdentifierPart(s.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(s.charAt(start - 1))) {
            start--;
        }
        if (start == end) return null;
        if (Character.isDigit(s.charAt(start))) return null; // « 1 » n'est pas un nom
        return s.substring(start, end);
    }

    // ════════════════════════════════════════════════════════════════
    // Utilitaires de scan (masqué)
    // ════════════════════════════════════════════════════════════════

    /**
     * La profondeur d'imbrication du caret RELATIVE à l'ouvrante :
     * 0 = le caret vit directement dans la liste d'arguments, &gt; 0 =
     * dans un appel ou un tableau imbriqué (le contexte appartient
     * alors à la construction interne).
     */
    private static int profondeurRelative(String text, int open, int caret) {
        boolean[] masque = masquerJusquÀ(text, caret);
        int depth = 0;
        for (int i = open + 1; i < caret; i++) {
            if (masque[i]) continue;
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
        }
        return depth;
    }

    /**
     * Le caret est-il à un DÉBUT d'argument (juste après « ( » ou une
     * virgule de profondeur 0, espaces et identifiants frappés
     * seulement) ? Un opérateur entre la frontière et le caret signe
     * une position de VALEUR ou d'expression — la complétion d'attribut
     * ou d'argument nommé ne s'applique pas là.
     */
    private static boolean positionDébutArgument(String text, int open, int caret) {
        boolean[] masque = masquerJusquÀ(text, caret);
        int depth = 0;
        int frontière = open;
        for (int i = open + 1; i < caret; i++) {
            if (masque[i]) continue;
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) frontière = i;
        }
        // Entre la frontière et le caret : espaces et identifiants
        // seulement (l'utilisateur tape le nom, pas une expression).
        for (int i = frontière + 1; i < caret; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isJavaIdentifierPart(c)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Les noms déjà posés comme arguments NOMMÉS avant le caret
     * ({@code foo(a = 1, b = 2, |} → « a », « b »), à la profondeur 0
     * de la construction — pour ne pas re-proposer un attribut ou un
     * argument déjà écrit.
     */
    private static Set<String> nomsDéjàPosés(String text, int open, int caret) {
        Set<String> posés = new HashSet<>();
        boolean[] masque = masquerJusquÀ(text, caret);
        int depth = 0;
        int i = open + 1;
        while (i < caret) {
            if (masque[i]) {
                i++;
                continue;
            }
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < caret && !masque[i]
                        && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                String mot = text.substring(start, i);
                // L'identifiant est-il suivi (espaces mises à part)
                // d'un « = » d'affectation (pas « == » ni « <= ») ?
                int j = i;
                while (j < caret && Character.isWhitespace(text.charAt(j))) j++;
                if (j < caret && text.charAt(j) == '='
                        && !(j + 1 < caret && (text.charAt(j + 1) == '='
                                || text.charAt(j + 1) == '>'))) {
                    posés.add(mot);
                }
                continue;
            }
            i++;
        }
        return posés;
    }

    /**
     * Masque chaînes et commentaires jusqu'à un offset — même
     * algorithme que le masque de {@link MiniJavaSignatures},
     * réimplémenté ici pour l'autonomie du moteur (le sien est
     * privé, et la Vue masque le document ENTIER).
     */
    private static boolean[] masquerJusquÀ(String text, int limite) {
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

    // ════════════════════════════════════════════════════════════════
    // Vue légère du buffer : enums, annotations, variables
    // ════════════════════════════════════════════════════════════════

    /** « enum Couleur » (Java) / « enum class Couleur » (Kotlin). */
    private static final Pattern ENUM_DÉCL = Pattern.compile(
            "\\benum\\s+(?:class\\s+)?(\\w+)");
    /** « @interface Trace » — la déclaration d'un type d'annotation. */
    private static final Pattern ANNOTATION_DÉCL = Pattern.compile(
            "@interface\\s+(\\w+)");
    /** Une méthode dans un corps de type : nom + paramètres aplatis. */
    private static final Pattern MÉTHODE_CORPS = Pattern.compile(
            "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(([^()]*)\\)\\s*(?:throws[^{;]+)?\\{");
    /** Un attribut d'annotation : type + nom + défaut optionnel. */
    private static final Pattern ATTRIBUT = Pattern.compile(
            "([\\w.$]+(?:\\s*<[^<>]*>)?(?:\\s*\\[\\s*\\])?)\\s+"
            + "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(\\s*\\)\\s*"
            + "(?:default\\s+([^;]+))?;");
    private static final Pattern DÉCL_VAR = Pattern.compile(
            "(?:(?:public|private|protected|static|final)\\s+)?"
            + "([A-Z][\\w.$]*)(?:\\s*<[^<>]*>)?\\s*(?:\\[\\])?\\s+(\\w+)\\s*(?:=[^=]|;|,|\\))");
    private static final Pattern DÉCL_NEW = Pattern.compile(
            "\\b(\\w+)\\s*=\\s*new\\s+([A-Z][\\w.$]*)");

    /** Un enum déclaré dans le buffer : nom, constantes, méthodes. */
    static final class EnumBuffer {
        final String nom;
        final List<String> constantes = new ArrayList<>();
        final List<String> méthodes = new ArrayList<>();

        EnumBuffer(String nom) {
            this.nom = nom;
        }
    }

    /**
     * Vue légère du buffer construite en RÉGIONS du texte masqué
     * (chaînes et commentaires remplacés par des espaces, offsets
     * préservés) : le corps de chaque {@code enum} donne ses
     * constantes puis ses méthodes, le corps de chaque
     * {@code @interface} donne ses attributs, et les déclarations de
     * variables donnent leurs types — les mêmes approximations
     * déterministes que la vue de {@link MiniJavaSignatures}, sans
     * prétendre à un parseur complet.
     */
    private static final class Vue {
        final Map<String, EnumBuffer> enums = new LinkedHashMap<>();
        final Map<String, List<Attribut>> annotations = new LinkedHashMap<>();
        final Map<String, String> variables = new HashMap<>();

        Vue(String text) {
            String grille = grilleMasquée(text);
            // 1. Enums : déclaration → corps → constantes + méthodes.
            Matcher me = ENUM_DÉCL.matcher(grille);
            while (me.find()) {
                EnumBuffer e = new EnumBuffer(me.group(1));
                int corps = grille.indexOf('{', me.end());
                if (corps >= 0) {
                    lireEnum(e, grille, corps);
                }
                enums.put(e.nom, e);
            }
            // 2. Annotations : déclaration → corps → attributs.
            Matcher ma = ANNOTATION_DÉCL.matcher(grille);
            while (ma.find()) {
                List<Attribut> attrs = new ArrayList<>();
                int corps = grille.indexOf('{', ma.end());
                if (corps >= 0) {
                    int fin = fermante(grille, corps);
                    Matcher at = ATTRIBUT.matcher(grille);
                    at.region(corps + 1, fin);
                    while (at.find()) {
                        attrs.add(new Attribut(at.group(2),
                                at.group(1).trim(),
                                at.group(3) != null ? at.group(3).trim() : null));
                    }
                }
                annotations.put(ma.group(1), attrs);
            }
            // 3. Variables typées — le récepteur « c. » d'un enum.
            Matcher dv = DÉCL_VAR.matcher(grille);
            while (dv.find()) variables.put(dv.group(2), dv.group(1));
            Matcher dn = DÉCL_NEW.matcher(grille);
            while (dn.find()) variables.putIfAbsent(dn.group(1), dn.group(2));
        }

        /** Le texte intégral masqué : littéraux et commentaires en espaces. */
        private static String grilleMasquée(String text) {
            boolean[] masque = masquerJusquÀ(text, text.length());
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                sb.append(masque[i] ? ' ' : text.charAt(i));
            }
            return sb.toString();
        }

        /**
         * Lit le corps d'un enum : les CONSTANTES jusqu'au premier
         * « ; » de profondeur 0 (ou à la fermante), puis les MÉTHODES
         * — un identifiant suivi de parenthèses et d'une accolade
         * ouvrante, têtes de contrôle et constructeur exclus.
         */
        private static void lireEnum(EnumBuffer e, String grille, int corps) {
            int fin = fermante(grille, corps);
            // La frontière des constantes : premier « ; » de profondeur
            // 0 (les arguments d'une constante « ROUGE(1, 2) »
            // imbriquent leurs parenthèses).
            int depth = 0;
            int frontière = fin;
            for (int i = corps + 1; i < fin; i++) {
                char c = grille.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ';' && depth == 0) {
                    frontière = i;
                    break;
                }
            }
            // Les constantes : identifiants séparés par des virgules de
            // profondeur 0, arguments parenthésés retirés.
            depth = 0;
            int start = corps + 1;
            for (int i = corps + 1; i <= frontière; i++) {
                char c = i < frontière ? grille.charAt(i) : ',';
                if (c == '(') depth++;
                else if (c == ')') depth--;
                if ((c == ',' && depth == 0) || i == frontière) {
                    String morceau = grille.substring(start, i).trim();
                    int paren = morceau.indexOf('(');
                    if (paren >= 0) morceau = morceau.substring(0, paren).trim();
                    if (estIdentifiant(morceau) && !morceau.isEmpty()) {
                        e.constantes.add(morceau);
                    }
                    start = i + 1;
                }
            }
            // Les méthodes : après la frontière des constantes.
            Matcher m = MÉTHODE_CORPS.matcher(grille);
            m.region(frontière == fin ? frontière : frontière + 1, fin);
            while (m.find()) {
                String nom = m.group(1);
                if (nom.equals(e.nom) || TÊTES.contains(nom)) continue;
                e.méthodes.add(nom + "(" + m.group(2).trim() + ")");
            }
        }

        /** La fermante appariée de l'accolade ouvrante (ou la fin). */
        private static int fermante(String grille, int ouvrante) {
            int depth = 0;
            for (int i = ouvrante; i < grille.length(); i++) {
                char c = grille.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return grille.length();
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
    }
}

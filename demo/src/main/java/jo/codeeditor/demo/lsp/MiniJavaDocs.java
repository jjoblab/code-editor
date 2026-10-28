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
 * La documentation au SURVOL du mini Java de la démo — le texte fantôme
 * du symbole sous le doigt, nourri par les QUATRE sources du moteur.
 *
 * <p>Le symbole sous le caret est résolu par famille, dans l'ordre du
 * PLUS local au PLUS lointain :</p>
 * <ol>
 *   <li><b>Le buffer</b> — constante d'enum, attribut d'annotation,
 *       méthode déclarée, variable typée, type déclaré : la javadoc
 *       {@code @param} du fichier comprise ;</li>
 *   <li><b>La bibliothèque embarquée</b> — les signatures documentées
 *       de {@link MiniJavaSignatures} ({@code Math.max},
 *       {@code sb.append}…) ;</li>
 *   <li><b>Le classpath</b> — {@link MiniJavaClasspath} : la fiche
 *       réelle de la classe ({@code Integer}, {@code Arrays}…), les
 *       méthodes publiques par réflexion — le filet qui attrape tout
 *       ce que la table ne connaît pas ;</li>
 *   <li><b>Rien</b> — un mot inconnu ne montre RIEN (jamais de doc
 *       inventée) : le survol retourné est null et le client garde
 *       son repli local.</li>
 * </ol>
 *
 * <p>Le format de sortie est du MARKDOWN façon javadoc : un fence
 * {@code ```java} porte la (les) signature(s) — le panneau de
 * documentation en fait son EN-TÊTE — puis la description, puis les
 * sections {@code @param} parsées par le QuickDoc de l'hôte. Pur JVM
 * (aucune dépendance Android), partagé par le mini serveur
 * ({@code textDocument/hover}) : testable directement en JUnit.</p>
 */
public final class MiniJavaDocs {

    private MiniJavaDocs() {}

    // ════════════════════════════════════════════════════════════════
    // Point d'entrée
    // ════════════════════════════════════════════════════════════════

    /**
     * Le survol du symbole sous le caret — markdown façon javadoc
     * (fence signature, description, sections @param), ou null si le
     * caret n'est pas sur un symbole documentable.
     *
     * @param text  texte complet du document
     * @param caret offset du caret
     */
    public static String survol(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) return null;
        boolean[] masque = Vue.masquer(text, text.length());
        int[] mot = motÀ(text, masque, caret);
        if (mot == null) return null;
        String nom = text.substring(mot[0], mot[1]);
        if (MOTS_CLÉS.contains(nom)) return null;
        Vue vue = new Vue(text);
        // Le qualificatif AVANT le mot (« sb.append » → récepteur
        // « sb », « Couleur.ROUGE » → récepteur « Couleur »).
        String récepteur = qualificatif(text, masque, mot[0]);

        // 1. Constante d'enum — par récepteur ou par région du corps.
        String constante = survolConstante(vue, récepteur, nom, mot[0]);
        if (constante != null) return constante;
        // 2. Attribut d'annotation — le mot suivi de « = » dans
        //    « @Anno(… ) ».
        String attribut = survolAttribut(text, vue, nom, mot[0], mot[1]);
        if (attribut != null) return attribut;
        // 3. Méthode — un appel (nom suivi de « ( ») ou un nom qualifié.
        String méthode = survolMéthode(text, récepteur, nom, mot[1]);
        if (méthode != null) return méthode;
        // 4. Variable déclarée — le type d'abord, sa fiche ensuite.
        String variable = survolVariable(vue, nom, récepteur);
        if (variable != null) return variable;
        // 5. Type — déclaré dans le fichier, puis classpath.
        if (Character.isUpperCase(nom.charAt(0))) {
            String type = survolType(vue, nom);
            if (type != null) return type;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // 1. Constante d'enum
    // ════════════════════════════════════════════════════════════════

    /**
     * Une constante d'enum : le mot nomme une constante — soit derrière
     * un récepteur enum ({@code Couleur.ROUGE}, {@code c.ROUGE}), soit
     * dans la RÉGION DES CONSTANTES du corps d'un enum déclaré. Null
     * sinon.
     */
    private static String survolConstante(Vue vue, String récepteur,
            String nom, int motStart) {
        for (Vue.EnumSpan e : vue.enums.values()) {
            boolean parRécepteur = false;
            if (récepteur != null) {
                if (récepteur.equals(e.nom)) {
                    parRécepteur = true;
                } else {
                    String type = vue.variables.get(récepteur);
                    parRécepteur = type != null
                            && dernierSegment(type).equals(e.nom);
                }
            }
            boolean dansRégion = motStart > e.corps
                    && motStart <= e.frontière;
            if (!parRécepteur && !dansRégion) continue;
            int ordinal = e.constantes.indexOf(nom);
            if (ordinal < 0) continue;
            StringBuilder md = new StringBuilder();
            md.append("```java\n").append(e.nom).append('.')
                    .append(nom).append("\n```\n");
            md.append("Constante de l'enum ").append(e.nom)
                    .append(" (ordinal ").append(ordinal)
                    .append("), déclarée dans ce fichier.");
            if (!e.constantes.isEmpty()) {
                md.append("\n\nConstantes : ")
                        .append(String.join(", ", e.constantes)).append('.');
            }
            return md.toString();
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Attribut d'annotation
    // ════════════════════════════════════════════════════════════════

    /**
     * Un attribut d'annotation : le mot est suivi d'un « = » (simple,
     * pas « == ») à l'intérieur des parenthèses d'une annotation
     * CONNUE (déclarée dans le buffer ou table JDK). Null sinon.
     */
    private static String survolAttribut(String text, Vue vue, String nom,
            int motStart, int motEnd) {
        // Le « = » qui suit le mot (espaces sautées) — un attribut
        // POSÉ ; sans « = », le mot peut être en cours de frappe :
        // servi seulement s'il vit dans les parenthèses de l'annotation.
        int j = motEnd;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        boolean posé = j < text.length() && text.charAt(j) == '='
                && !(j + 1 < text.length() && (text.charAt(j + 1) == '='
                        || text.charAt(j + 1) == '>'));
        int open = MiniJavaSignatures.trouverOuvrante(text,
                Math.min(motStart + 1, text.length()));
        if (open < 0) return null;
        String anno = annotationAvant(text, open);
        if (anno == null) return null;
        List<String[]> attributs = vue.annotations.get(anno);
        if (attributs == null) attributs = ANNOTATIONS_JDK.get(anno);
        if (attributs == null) return null;
        for (String[] a : attributs) {
            if (!nom.equals(a[0])) continue;
            if (!posé) {
                // En cours de frappe : le caret vit DANS les
                // parenthèses — sinon le mot serait ailleurs.
                return null;
            }
            StringBuilder md = new StringBuilder();
            md.append("```java\n").append(a[1]).append(' ')
                    .append(a[0]).append("\n```\n");
            md.append("Attribut de l'annotation @").append(anno);
            if (a[2] != null) {
                md.append(" — valeur par défaut : ").append(a[2])
                        .append('.');
            } else {
                md.append(" — sans valeur par défaut (obligatoire).");
            }
            return md.toString();
        }
        return null;
    }

    /** L'identifiant avant l'ouvrante est-il précédé d'un « @ » ? */
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
    // 3. Méthode
    // ════════════════════════════════════════════════════════════════

    /**
     * Une méthode : le mot suit un point ({@code sb.append}) ou ouvre
     * un appel ({@code add(}). Les candidats viennent du MÊME moteur
     * que l'aide de signature — bibliothèque embarquée, méthodes
     * déclarées du buffer, repli classpath compris — le survol et le
     * popup racontent la même histoire.
     */
    private static String survolMéthode(String text, String récepteur,
            String nom, int motFin) {
        // Le mot survolé ouvre-t-il un appel (cette occurrence, pas
        // une autre) ? Sans point ni parenthèse, ce n'est pas une
        // méthode — la suite des familles tranche.
        boolean ouvreAppel = false;
        int j = motFin;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        ouvreAppel = j < text.length() && text.charAt(j) == '(';
        if (récepteur == null && !ouvreAppel) return null;
        List<MiniJavaSignatures.Entry> entrées =
                MiniJavaSignatures.candidats(text, récepteur, nom);
        if (entrées.isEmpty()) return null;
        StringBuilder md = new StringBuilder("```java\n");
        for (int i = 0; i < entrées.size() && i < 6; i++) {
            md.append(entrées.get(i).label).append('\n');
        }
        if (entrées.size() > 6) {
            md.append("… (+").append(entrées.size() - 6)
                    .append(" autres)\n");
        }
        md.append("```\n");
        MiniJavaSignatures.Entry première = entrées.get(0);
        if (!première.documentation.isEmpty()) {
            md.append(première.documentation);
        } else {
            md.append("Méthode ").append(nom).append(" — ")
                    .append(entrées.size() > 1
                            ? entrées.size() + " surcharges."
                            : "signature résolue.");
        }
        // Les sections @param de la première surcharge documentée.
        for (MiniJavaSignatures.Entry e : entrées) {
            boolean auMoinsUne = false;
            for (int i = 0; i < e.params.size()
                    && i < e.paramDocs.size(); i++) {
                if (e.paramDocs.get(i) != null
                        && !e.paramDocs.get(i).isEmpty()) {
                    auMoinsUne = true;
                    break;
                }
            }
            if (!auMoinsUne) continue;
            for (int i = 0; i < e.params.size()
                    && i < e.paramDocs.size(); i++) {
                String p = nomDeParamètre(e.params.get(i));
                String doc = e.paramDocs.get(i);
                if (p != null && doc != null && !doc.isEmpty()) {
                    md.append("\n\n@param ").append(p).append(' ')
                            .append(doc);
                }
            }
            break;
        }
        return md.toString();
    }


    // ════════════════════════════════════════════════════════════════
    // 4. Variable
    // ════════════════════════════════════════════════════════════════

    /**
     * Une variable du buffer : sa déclaration en fence, sa ligne de
     * déclaration en description, et la fiche de son TYPE — constantes
     * d'enum déclarées, ou fiche classpath par réflexion.
     */
    private static String survolVariable(Vue vue, String nom, String récepteur) {
        if (récepteur != null) return null; // « a.b » : b n'est pas la variable
        String déclaration = vue.déclarations.get(nom);
        if (déclaration == null) return null;
        String typeComplet = vue.variables.get(nom);
        String base = dernierSegment(typeComplet);
        StringBuilder md = new StringBuilder("```java\n")
                .append(typeComplet).append(' ').append(nom)
                .append("\n```\n");
        md.append("Variable déclarée dans ce fichier — ")
                .append(déclaration.trim());
        // La fiche du type : enum du buffer d'abord…
        Vue.EnumSpan e = vue.enums.get(base);
        if (e != null) {
            md.append("\n\nType enum ").append(base).append(" — constantes : ")
                    .append(String.join(", ", e.constantes)).append('.');
            return md.toString();
        }
        // … méthodes du type déclaré du fichier…
        Integer nb = vue.méthodesParType.get(base);
        if (nb != null) {
            md.append("\n\nType ").append(base)
                    .append(" déclaré dans ce fichier — ").append(nb)
                    .append(" méthode(s).");
            return md.toString();
        }
        // … fiche classpath enfin (jamais pour un type du fichier).
        Class<?> c = MiniJavaClasspath.classe(base);
        if (c != null) {
            List<String> fiche = MiniJavaClasspath.fiche(c);
            for (int i = 1; i < fiche.size(); i++) {
                md.append("\n\n").append(fiche.get(i));
            }
        }
        return md.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // 5. Type
    // ════════════════════════════════════════════════════════════════

    /**
     * Un TYPE : déclaré dans le fichier (classe, interface, enum,
     * record, @interface — la déclaration en fence, ses membres en
     * description) ou porté par le classpath (fiche de réflexion).
     */
    private static String survolType(Vue vue, String nom) {
        Vue.TypeSpan t = vue.types.get(nom);
        if (t != null) {
            StringBuilder md = new StringBuilder("```java\n")
                    .append(t.déclaration).append("\n```\n");
            md.append("Type ").append(t.genre).append(" déclaré dans ce fichier.");
            if (t.membres != null && !t.membres.isEmpty()) {
                md.append("\n\n").append(t.membres);
            }
            return md.toString();
        }
        Class<?> c = MiniJavaClasspath.classe(nom);
        if (c == null) return null;
        List<String> fiche = MiniJavaClasspath.fiche(c);
        if (fiche.isEmpty()) return null;
        StringBuilder md = new StringBuilder("```java\n")
                .append(fiche.get(0)).append("\n```\n");
        md.append("Type du classpath, résolu par réflexion sur le runtime.");
        for (int i = 1; i < fiche.size(); i++) {
            md.append("\n\n").append(fiche.get(i));
        }
        return md.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // Utilitaires
    // ════════════════════════════════════════════════════════════════

    /**
     * Le mot (identifiant Java Unicode) CONTENANT l'offset — le caret
     * posé juste après le mot le prend aussi. Un mot DANS une chaîne
     * ou un commentaire n'est pas un symbole : null. Null si l'offset
     * n'est dans aucun identifiant.
     */
    private static int[] motÀ(String text, boolean[] masque, int caret) {
        int borne = Math.min(caret, text.length());
        int fin = borne;
        while (fin < text.length()
                && Character.isJavaIdentifierPart(text.charAt(fin))) {
            fin++;
        }
        int début = borne;
        while (début > 0
                && Character.isJavaIdentifierPart(text.charAt(début - 1))) {
            début--;
        }
        if (début == fin) return null;
        if (!Character.isJavaIdentifierStart(text.charAt(début))) return null;
        // Le mot vit dans une chaîne ou un commentaire : pas un
        // symbole du code — le survol n'a rien à dire dessus.
        for (int i = début; i < fin; i++) {
            if (i < masque.length && masque[i]) return null;
        }
        return new int[]{début, fin};
    }

    /**
     * Le qualificatif AVANT un mot : « sb. » avant {@code append} dans
     * « sb.append » — la chaîne identifiants/points lue en arrière,
     * HORS chaînes et commentaires. Null si le mot n'est pas
     * précédé d'un point.
     */
    private static String qualificatif(String text, boolean[] masque,
            int motStart) {
        int i = motStart;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        if (i == 0 || text.charAt(i - 1) != '.') return null;
        if (i - 1 < masque.length && masque[i - 1]) return null;
        int end = i - 1;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == '.' && !(start - 1 < masque.length && masque[start - 1])) {
                start--;
            } else if (Character.isJavaIdentifierPart(c)
                    && !(start - 1 < masque.length && masque[start - 1])) {
                start--;
            } else {
                break;
            }
        }
        if (start == end) return null;
        String qual = text.substring(start, end);
        if (qual.endsWith(".") || qual.startsWith(".")) return null;
        if (Character.isDigit(qual.charAt(qual.length() - 1))) return null;
        return qual;
    }

    /** Le dernier segment d'un nom qualifié. */
    private static String dernierSegment(String qualifié) {
        if (qualifié == null) return "";
        int p = qualifié.lastIndexOf('.');
        return p >= 0 ? qualifié.substring(p + 1) : qualifié;
    }

    /**
     * Le nom d'un paramètre dans son libellé (« double base » →
     * « base ») — le dernier mot identifiant, sans « … » ni génériques.
     */
    private static String nomDeParamètre(String libellé) {
        if (libellé == null) return null;
        String s = libellé.replace("...", " ");
        int fin = s.length();
        while (fin > 0 && !Character.isJavaIdentifierPart(s.charAt(fin - 1))) {
            fin--;
        }
        int début = fin;
        while (début > 0
                && Character.isJavaIdentifierPart(s.charAt(début - 1))) {
            début--;
        }
        if (début == fin) return null;
        if (Character.isDigit(s.charAt(début))) return null;
        return s.substring(début, fin);
    }

    // ════════════════════════════════════════════════════════════════
    // Vue du buffer — enums avec plages, annotations, types, variables
    // ════════════════════════════════════════════════════════════════

    /** Mots-clés jamais survolés comme symboles. */
    private static final Set<String> MOTS_CLÉS = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "return", "new", "do",
            "try", "else", "throw", "assert", "synchronized", "case", "super",
            "this", "import", "package", "class", "interface", "enum",
            "record", "extends", "implements", "static", "final", "public",
            "private", "protected", "void", "int", "long", "short", "byte",
            "char", "float", "double", "boolean", "var", "null", "true",
            "false", "instanceof", "throws", "default"));

    /** Annotations du JDK à attributs — nom → [nom, type, défaut]. */
    private static final Map<String, List<String[]>> ANNOTATIONS_JDK =
            new HashMap<>();

    static {
        ANNOTATIONS_JDK.put("SuppressWarnings",
                java.util.Collections.singletonList(
                        new String[]{"value", "String[]", null}));
        ANNOTATIONS_JDK.put("SafeVarargs",
                java.util.Collections.singletonList(
                        new String[]{"value", "String...", null}));
    }

    private static final Pattern ENUM_DÉCL = Pattern.compile(
            "\\benum\\s+(?:class\\s+)?(\\w+)");
    private static final Pattern TYPE_DÉCL = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+(\\w+)"
                    + "([^\\{\\;]*)");
    private static final Pattern ANNOTATION_DÉCL = Pattern.compile(
            "@interface\\s+(\\w+)");
    private static final Pattern ATTRIBUT = Pattern.compile(
            "([\\w.$]+(?:\\s*<[^<>]*>)?(?:\\s*\\[\\s*\\])?)\\s+"
                    + "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(\\s*\\)\\s*"
                    + "(?:default\\s+([^;]+))?;");
    private static final Pattern DÉCL_VAR = Pattern.compile(
            "(?:(?:public|private|protected|static|final)\\s+)?"
                    + "([A-Z][\\w.$]*)(\\s*<[^<>]*>)?\\s*(?:\\[\\])?\\s+"
                    + "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*(?:=[^=]|;|,|\\))");
    private static final Pattern MÉTHODE_TÊTE = Pattern.compile(
            "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(");

    /**
     * Vue légère du buffer construite sur la GRILLE MASQUÉE (chaînes
     * et commentaires en espaces, offsets préservés) : enums avec la
     * plage de leurs constantes, annotations déclarées et attributs,
     * types déclarés avec leur ligne de déclaration, variables typées
     * avec génériques conservés, méthodes comptées par type — les
     * mêmes approximations déterministes que les moteurs frères, sans
     * prétendre à un parseur complet.
     */
    private static final class Vue {
        final Map<String, EnumSpan> enums = new LinkedHashMap<>();
        final Map<String, List<String[]>> annotations = new LinkedHashMap<>();
        final Map<String, TypeSpan> types = new LinkedHashMap<>();
        final Map<String, String> variables = new HashMap<>();
        final Map<String, String> déclarations = new HashMap<>();
        final Map<String, Integer> méthodesParType = new HashMap<>();

        /** Un enum déclaré : sa plage de constantes et leur liste. */
        static final class EnumSpan {
            final String nom;
            final int corps;
            final int frontière;
            final List<String> constantes;
            EnumSpan(String nom, int corps, int frontière,
                    List<String> constantes) {
                this.nom = nom;
                this.corps = corps;
                this.frontière = frontière;
                this.constantes = constantes;
            }
        }

        /** Un type déclaré : genre, ligne de déclaration, membres. */
        static final class TypeSpan {
            final String genre;
            final String déclaration;
            final String membres;
            TypeSpan(String genre, String déclaration, String membres) {
                this.genre = genre;
                this.déclaration = déclaration;
                this.membres = membres;
            }
        }

        Vue(String text) {
            String grille = grille(text);
            lireEnums(grille);
            lireTypes(grille);
            lireAnnotations(grille);
            lireVariables(grille, text);
            compterMéthodes(grille);
        }

        // ── Grille masquée (offsets préservés) ──────────────────

        private static String grille(String text) {
            boolean[] masque = masquer(text, text.length());
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                sb.append(masque[i] ? ' ' : text.charAt(i));
            }
            return sb.toString();
        }

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

        // ── Familles de déclarations ────────────────────────────

        private void lireEnums(String grille) {
            Matcher me = ENUM_DÉCL.matcher(grille);
            while (me.find()) {
                String nom = me.group(1);
                int corps = grille.indexOf('{', me.end());
                if (corps < 0) continue;
                int fin = fermante(grille, corps);
                // La frontière des constantes : premier « ; » de
                // profondeur 0 (les arguments d'une constante imbriquent).
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
                List<String> constantes = new ArrayList<>();
                depth = 0;
                int start = corps + 1;
                for (int i = corps + 1; i <= frontière; i++) {
                    char c = i < frontière ? grille.charAt(i) : ',';
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                    if ((c == ',' && depth == 0) || i == frontière) {
                        String morceau = grille.substring(start, i).trim();
                        int paren = morceau.indexOf('(');
                        if (paren >= 0) {
                            morceau = morceau.substring(0, paren).trim();
                        }
                        if (!morceau.isEmpty()
                                && estIdentifiant(morceau)) {
                            constantes.add(morceau);
                        }
                        start = i + 1;
                    }
                }
                enums.put(nom, new EnumSpan(nom, corps, frontière, constantes));
            }
        }

        private void lireTypes(String grille) {
            Matcher mt = TYPE_DÉCL.matcher(grille);
            while (mt.find()) {
                String genre = mt.group(1);
                String nom = mt.group(2);
                String queue = mt.group(3) != null ? mt.group(3).trim() : "";
                String déclaration = (genre + " " + nom + (" " + queue).trim()
                        + " {").replaceAll("\\s+", " ").trim();
                // Les membres : méthodes du corps (nom + parenthèses),
                // constantes d'enum ou attributs d'annotation.
                int corps = grille.indexOf('{', mt.end() - 1);
                StringBuilder membres = null;
                if (corps >= 0) {
                    int fin = fermante(grille, corps);
                    EnumSpan e = enums.get(nom);
                    if (e != null) {
                        membres = new StringBuilder("Constantes : "
                                + String.join(", ", e.constantes) + ".");
                    } else if ("interface".equals(genre)) {
                        membres = new StringBuilder(
                                compterTêtes(grille, corps, fin) + " membre(s) abstrait(s).");
                    } else {
                        int nb = compterTêtes(grille, corps, fin);
                        membres = new StringBuilder(
                                nb + " méthode(s) déclarée(s).");
                    }
                }
                types.put(nom, new TypeSpan(genre, déclaration,
                        membres != null ? membres.toString() : null));
            }
            Matcher ma = ANNOTATION_DÉCL.matcher(grille);
            while (ma.find()) {
                String nom = ma.group(1);
                int corps = grille.indexOf('{', ma.end());
                StringBuilder membres = new StringBuilder();
                if (corps >= 0) {
                    int fin = fermante(grille, corps);
                    Matcher at = ATTRIBUT.matcher(grille);
                    at.region(corps + 1, fin);
                    List<String> noms = new ArrayList<>();
                    while (at.find()) noms.add(at.group(2));
                    if (!noms.isEmpty()) {
                        membres.append("Attributs : ")
                                .append(String.join(", ", noms)).append('.');
                    }
                }
                types.put(nom, new TypeSpan("@interface",
                        "@interface " + nom + " {",
                        membres.length() > 0 ? membres.toString() : null));
            }
        }

        /** Les têtes « nom( » d'un corps — pas celles des contrôles. */
        private static int compterTêtes(String grille, int début, int fin) {
            Matcher m = MÉTHODE_TÊTE.matcher(grille);
            m.region(début, fin);
            int nb = 0;
            while (m.find()) {
                String tête = m.group(1);
                if (MOTS_CLÉS.contains(tête)) continue;
                nb++;
            }
            return nb;
        }

        private void lireAnnotations(String grille) {
            Matcher ma = ANNOTATION_DÉCL.matcher(grille);
            while (ma.find()) {
                List<String[]> attrs = new ArrayList<>();
                int corps = grille.indexOf('{', ma.end());
                if (corps >= 0) {
                    int fin = fermante(grille, corps);
                    Matcher at = ATTRIBUT.matcher(grille);
                    at.region(corps + 1, fin);
                    while (at.find()) {
                        attrs.add(new String[]{at.group(2),
                                at.group(1).trim(),
                                at.group(3) != null
                                        ? at.group(3).trim() : null});
                    }
                }
                annotations.put(ma.group(1), attrs);
            }
        }

        private void lireVariables(String grille, String texte) {
            Matcher dv = DÉCL_VAR.matcher(grille);
            while (dv.find()) {
                String nom = dv.group(3);
                String base = dv.group(1);
                String génériques = dv.group(2) != null
                        ? dv.group(2).trim() : "";
                variables.putIfAbsent(nom, base + génériques);
                // La ligne de déclaration brute (bornée pour la doc).
                int ls = ligneDébut(grille, dv.start());
                int le = ligneFin(grille, dv.end());
                String ligne = texte.substring(ls, Math.min(le, ls + 120));
                déclarations.putIfAbsent(nom, ligne.trim());
            }
        }

        private void compterMéthodes(String grille) {
            // Toutes les têtes « nom( » suivies d'un bloc, par type
            // englobant — approximation déterministe assumée.
            Matcher mt = TYPE_DÉCL.matcher(grille);
            while (mt.find()) {
                int corps = grille.indexOf('{', mt.end() - 1);
                if (corps < 0) continue;
                int fin = fermante(grille, corps);
                int nb = compterTêtes(grille, corps, fin);
                méthodesParType.merge(mt.group(2), nb, Integer::sum);
            }
        }

        // ── Bornes de ligne ──────────────────────────────────────

        private static int ligneDébut(String s, int offset) {
            int i = Math.min(offset, s.length());
            while (i > 0 && s.charAt(i - 1) != '\n') i--;
            return i;
        }

        private static int ligneFin(String s, int offset) {
            int i = Math.max(0, Math.min(offset, s.length()));
            while (i < s.length() && s.charAt(i) != '\n') i++;
            return i;
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

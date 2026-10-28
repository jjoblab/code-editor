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
 * Moteur des MEMBRES MANQUANTS du mini Java de la démo — la détection
 * et le correctif rapide « implémenter les membres manquants » :
 *
 * <ol>
 *   <li><b>Détection</b> — chaque classe du buffer qui étend ou
 *       implémente un type DÉCLARÉ DANS LE FICHIER est comparée aux
 *       membres ABSTRAITS de ses parents (toutes les méthodes d'une
 *       {@code interface}, les méthodes sans corps d'une classe) : un
 *       membre est rempli quand la classe déclare une méthode de MÊME
 *   NOM ET MÊME ARITÉ avec un corps. Les parents transitifs
 *       (interface qui étend interface) comptent ; une classe
 *       {@code abstract} est EXCUSÉE (Java le permet) ; une classe
 *       concrète incomplète est signalée SUR SON NOM.</li>
 *   <li><b>Gabarit</b> — les membres manquants deviennent des stubs
 *       {@code @Override public … (…) \{ // TODO … \}} insérés avant
 *       la fermante de la classe, à l'indentation de ses membres :
 *       valeur de retour par défaut par type ({@code return 0;},
 *       {@code return false;}, {@code return null;}, rien pour
 *       {@code void}) — le code généré compile et le diagnostic
 *       disparaît au didChange suivant.</li>
 * </ol>
 *
 * <p><b>Méthode :</b> même discipline que {@link MiniJavaParser} et
 * {@link MiniJavaSignatures} — un balayage du texte MASQUÉ (chaînes et
 * commentaires ne comptent pas), un mini-scanner de corps qui reconnaît
 * les déclarations de membres (mots-clés modificateurs filtrés, type de
 * retour = dernier identifiant du préfixe, paramètres appariés, corps
 * sauté) et saute les types IMBRIQUÉS en les enregistrant pour leur
 * propre compte. Pur JVM, aucune dépendance Android : testable en JUnit
 * et partagé par le mini serveur LSP (diagnostics + code action) et le
 * moteur maison de la démo.</p>
 */
public final class MiniJavaMembers {

    private MiniJavaMembers() {}

    // ════════════════════════════════════════════════════════════════
    // Types de sortie
    // ════════════════════════════════════════════════════════════════

    /** Un membre abstrait à implémenter : type de retour, nom, paramètres. */
    public static class Membre {
        /** Le type de retour tel que déclaré (« List<String> », « void »). */
        public final String retour;
        /** Le nom de la méthode. */
        public final String nom;
        /** Les libellés des paramètres, tels que déclarés (« String clé »). */
        public final List<String> params;
        /** Le nombre de paramètres (l'arité des surcharges). */
        public final int arité;

        Membre(String retour, String nom, List<String> params) {
            this.retour = retour;
            this.nom = nom;
            this.params = params;
            this.arité = params.size();
        }

        /** Le libellé affiché : « void démarrer(String clé) ». */
        public String libellé() {
            return retour + " " + nom + "(" + String.join(", ", params) + ")";
        }

        @Override
        public String toString() {
            return libellé();
        }
    }

    /** Une classe concrète avec des membres manquants. */
    public static final class Manquant {
        /** Le nom de la classe incomplète. */
        public final String classe;
        /** Les parents déclarés DANS LE FICHIER qui portent les membres. */
        public final List<String> parents;
        /** Les membres abstraits non implémentés. */
        public final List<Membre> manquants;
        /** L'offset du NOM de la classe (le point d'ancrage du diagnostic). */
        public final int nomStart;
        /** La fin du nom de la classe. */
        public final int nomEnd;
        /** L'offset où insérer les stubs (juste avant la fermante). */
        public final int insertion;
        /** L'indentation d'un membre de la classe («     »). */
        public final String indentation;

        Manquant(String classe, List<String> parents, List<Membre> manquants,
                int nomStart, int nomEnd, int insertion, String indentation) {
            this.classe = classe;
            this.parents = parents;
            this.manquants = manquants;
            this.nomStart = nomStart;
            this.nomEnd = nomEnd;
            this.insertion = insertion;
            this.indentation = indentation;
        }

        /** Le nom de règle du diagnostic. */
        public String code() {
            return "unimplemented-members";
        }

        /** Le message du diagnostic, prêt à l'affichage. */
        public String message() {
            StringBuilder sb = new StringBuilder("La classe ").append(classe)
                    .append(" n'implémente pas ")
                    .append(manquants.size() == 1
                            ? "le membre hérité de "
                            : "les " + manquants.size() + " membres hérités de ")
                    .append(String.join(", ", parents))
                    .append(" : ");
            for (int i = 0; i < manquants.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(manquants.get(i).libellé());
            }
            return sb.toString();
        }
    }

    /**
     * Le texte des stubs à insérer à {@link Manquant#insertion} — un
     * bloc {@code @Override} par membre manquant, séparés par une
     * ligne vide, le tout précédé d'un saut de ligne (le bloc se pose
     * APRÈS le dernier membre existant, AVANT la fermante de la
     * classe). Valeur de retour par défaut par type — le code généré
     * compile.
     *
     * @param m la classe incomplète
     * @return le texte à insérer tel quel
     */
    public static String gabarit(Manquant m) {
        String ind = m.indentation;
        StringBuilder sb = new StringBuilder("\n");
        for (int i = 0; i < m.manquants.size(); i++) {
            Membre mb = m.manquants.get(i);
            if (i > 0) sb.append('\n');
            sb.append(ind).append("@Override\n");
            sb.append(ind).append("public ").append(mb.retour).append(' ')
                    .append(mb.nom).append('(')
                    .append(String.join(", ", mb.params)).append(") {\n");
            sb.append(ind).append("    // TODO : à implémenter\n");
            String retour = valeurDeRetour(mb.retour);
            if (retour != null) {
                sb.append(ind).append("    ").append(retour).append('\n');
            }
            sb.append(ind).append("}\n");
        }
        return sb.toString();
    }

    /**
     * La valeur de retour par défaut d'un type — null pour {@code void}
     * (aucune ligne de retour), {@code return 0;} pour les entiers,
     * {@code return 0.0;} pour les décimaux, {@code return false;} pour
     * les booléens, {@code return null;} pour le reste (objets,
     * tableaux, types déclarés).
     */
    private static String valeurDeRetour(String type) {
        String t = type.trim();
        if (t.endsWith("[]")) return "return null;";
        switch (t) {
            case "void": return null;
            case "int": case "long": case "short": case "byte":
                return "return 0;";
            case "double": case "float":
                return "return 0.0;";
            case "boolean":
                return "return false;";
            case "char":
                return "return '\\u0000';";
            default: return "return null;";
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Détection
    // ════════════════════════════════════════════════════════════════

    /**
     * Les classes concrètes du buffer qui n'implémentent pas leurs
     * membres abstraits hérités de types déclarés dans le fichier —
     * liste vide quand tout est rempli.
     *
     * @param text le texte complet du document
     * @return les classes incomplètes, dans l'ordre du document
     */
    public static List<Manquant> manquants(String text) {
        List<Manquant> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Grille grille = new Grille(text);
        for (TypeBuffer t : grille.types.values()) {
            // Seules les CLASSES concrètes sont redevables : une
            // interface n'implémente pas, une classe abstract est
            // excusée, un enum/record n'hérite pas de membres.
            if (!"class".equals(t.kind) || t.abstrait) continue;
            if (t.parents.isEmpty()) continue;
            // Les membres abstraits de la fermeture transitive des
            // parents déclarés dans le buffer.
            Map<String, Membre> dus = new LinkedHashMap<>();
            Set<String> vus = new HashSet<>();
            collecterDus(grille, t.parents, dus, vus);
            if (dus.isEmpty()) continue;
            List<Membre> manquants = new ArrayList<>();
            for (Membre dû : dus.values()) {
                if (!t.estImplémenté(dû.nom, dû.arité)) {
                    manquants.add(dû);
                }
            }
            if (manquants.isEmpty()) continue;
            List<String> parents = new ArrayList<>();
            for (String p : t.parents) {
                if (grille.types.containsKey(p) && !parents.contains(p)) {
                    parents.add(p);
                }
            }
            if (parents.isEmpty()) continue;
            out.add(new Manquant(t.nom, parents, manquants,
                    t.nomStart, t.nomEnd, t.corpsFin, t.indentation));
        }
        return out;
    }

    /**
     * Les membres abstraits dus par une liste de parents, récursivement
     * (une interface qui étend une interface transmet son contrat) —
     * dédupliqués par nom+arité, le premier déclaré gagne. Les méthodes
     * AVEC CORPS d'une interface ({@code default}, {@code static}) ne
     * sont pas dues : seules celles SANS CORPS sont abstraites.
     */
    private static void collecterDus(Grille grille, List<String> parents,
            Map<String, Membre> dus, Set<String> vus) {
        for (String p : parents) {
            if (p == null || !vus.add(p)) continue;
            TypeBuffer parent = grille.types.get(p);
            if (parent == null) continue; // parent externe : inconnu
            if (parent.estInterface() || parent.abstrait) {
                for (MembreBuffer m : parent.membres) {
                    if (m.estAbstrait()) {
                        dus.putIfAbsent(clé(m), m);
                    }
                }
            }
            collecterDus(grille, parent.parents, dus, vus);
        }
    }

    /** La clé de déduplication : nom + arité. */
    private static String clé(Membre m) {
        return m.nom + "/" + m.arité;
    }

    // ════════════════════════════════════════════════════════════════
    // La grille : types, membres, parents — sur le texte masqué
    // ════════════════════════════════════════════════════════════════

    /** « class|interface|enum|record Nom » — la déclaration d'un type
     *  (un « @interface » n'est PAS un type ici : il a sa propre
     *  entrée, sans héritage de membres). */
    private static final Pattern TYPE_DÉCL = Pattern.compile(
            "(?<!@)\\b(class|interface|enum|record)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)");
    /** « @interface Nom » — la déclaration d'un type d'annotation. */
    private static final Pattern ANNOTATION_TYPE = Pattern.compile(
            "@interface\\s+([\\p{L}_][\\p{L}\\p{N}_]*)");
    /** « @Anno(…) » dans le préfixe d'un membre — retiré du décodage. */
    private static final Pattern ANNOTATION_USAGE = Pattern.compile(
            "@[\\p{L}_][\\p{L}\\p{N}_]*(\\([^)]*\\))?");
    /** Les modificateurs Java — filtrés du préfixe d'un membre. */
    private static final Set<String> MODIFICATEURS = new HashSet<>(Arrays.asList(
            "public", "private", "protected", "static", "final", "abstract",
            "synchronized", "native", "transient", "volatile", "strictfp",
            "default", "sealed", "non-sealed"));

    /** Un membre déclaré dans un type : abstrait s'il n'a pas de corps. */
    static final class MembreBuffer extends Membre {
        final boolean aCorps;

        MembreBuffer(String retour, String nom, List<String> params, boolean aCorps) {
            super(retour, nom, params);
            this.aCorps = aCorps;
        }

        boolean estAbstrait() {
            return !aCorps;
        }
    }

    /** Un type déclaré : classe, interface, enum, record, annotation. */
    static final class TypeBuffer {
        final String kind;
        final String nom;
        final boolean abstrait;
        final List<String> parents = new ArrayList<>();
        final List<MembreBuffer> membres = new ArrayList<>();
        final int nomStart;
        final int nomEnd;
        final int corpsFin;
        final String indentation;

        TypeBuffer(String kind, String nom, boolean abstrait, int nomStart,
                int nomEnd, int corpsFin, String indentation) {
            this.kind = kind;
            this.nom = nom;
            this.abstrait = abstrait;
            this.nomStart = nomStart;
            this.nomEnd = nomEnd;
            this.corpsFin = corpsFin;
            this.indentation = indentation;
        }

        boolean estInterface() {
            return "interface".equals(kind);
        }

        /** Le nom (et l'arité) d'un membre avec CORPS sont-ils déclarés ? */
        boolean estImplémenté(String nom, int arité) {
            for (MembreBuffer m : membres) {
                if (m.aCorps && m.nom.equals(nom) && m.arité == arité) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Le buffer vu en types : chaque déclaration (y compris IMBRIQUÉE)
     * donne un {@link TypeBuffer} avec ses membres et ses parents —
     * construit sur le texte masqué, offsets exacts.
     */
    private static final class Grille {
        final Map<String, TypeBuffer> types = new LinkedHashMap<>();

        Grille(String text) {
            String grille = masquer(text);
            // 1. Toutes les déclarations de types, dans l'ordre.
            List<int[]> décls = new ArrayList<>(); // {start, kind(1=type,2=anno), nomStart, nomEnd}
            Matcher m = TYPE_DÉCL.matcher(grille);
            while (m.find()) {
                décls.add(new int[]{m.start(), 1, m.start(2), m.end(2)});
            }
            Matcher a = ANNOTATION_TYPE.matcher(grille);
            while (a.find()) {
                décls.add(new int[]{a.start(), 2, a.start(1), a.end(1)});
            }
            décls.sort((x, y) -> Integer.compare(x[0], y[0]));
            // 2. Chaque type : modificateurs, clause, corps, membres.
            for (int[] d : décls) {
                enregistrer(grille, d);
            }
        }

        private void enregistrer(String grille, int[] d) {
            Matcher m = TYPE_DÉCL.matcher(grille);
            m.region(d[0], grille.length());
            boolean estType = d[1] == 1 && m.find() && m.start() == d[0];
            String kind;
            String nom;
            int aprèsNom;
            if (estType) {
                kind = m.group(1);
                nom = m.group(2);
                aprèsNom = m.end();
            } else {
                Matcher am = ANNOTATION_TYPE.matcher(grille);
                am.region(d[0], grille.length());
                if (!(am.find() && am.start() == d[0])) return;
                kind = "@interface";
                nom = am.group(1);
                aprèsNom = am.end();
            }
            // Le modificateur abstract vit avant la déclaration (même
            // membre, ligne ouverte au dernier « ; »/« } »/« { »).
            boolean abstrait = contientAbstract(grille, d[0]);
            // Le corps : première « { » après la clause.
            int corps = grille.indexOf('{', aprèsNom);
            if (corps < 0) return;
            int fin = fermante(grille, corps);
            TypeBuffer t = new TypeBuffer(kind, nom, abstrait,
                    d[2], d[3], fin, indentationDe(grille, d[2]));
            lireClause(grille, aprèsNom, corps, t);
            types.put(nom, t);
            // Les membres du corps, types imbriqués exclus (ils sont
            // enregistrés pour leur propre compte par le balayage global).
            scannerMembres(grille, corps + 1, fin, t, this);
        }

        /** « abstract » entre la frontière du membre précédent et la déclaration ? */
        private static boolean contientAbstract(String grille, int décl) {
            int start = Math.max(0, grille.lastIndexOf(';', décl) + 1);
            int acc = grille.lastIndexOf('{', décl);
            if (acc >= start) start = acc + 1;
            return grille.substring(start, décl).contains("abstract");
        }

        /**
         * La clause d'héritage : « extends A » et « implements B, C »
         * entre le nom et le corps — les identifiants des deux listes.
         */
        private static void lireClause(String grille, int de, int corps, TypeBuffer t) {
            String clause = grille.substring(de, corps);
            Matcher ext = Pattern.compile("\\bextends\\s+([\\p{L}_.][\\p{L}\\p{N}_.]*)")
                    .matcher(clause);
            while (ext.find()) {
                ajouterParent(t, ext.group(1));
            }
            Matcher imp = Pattern.compile("\\bimplements\\s+([\\p{L}_,.\\s]+)")
                    .matcher(clause);
            while (imp.find()) {
                for (String p : imp.group(1).split(",")) {
                    ajouterParent(t, p.trim());
                }
            }
        }

        private static void ajouterParent(TypeBuffer t, String parent) {
            if (parent != null && !parent.isEmpty()
                    && Character.isJavaIdentifierStart(parent.charAt(0))
                    && !t.parents.contains(parent)) {
                t.parents.add(parent);
            }
        }

        /**
         * Le mini-scanner de corps : les déclarations de membres — un
         * identifiant suivi de parenthèses APPARIÉES puis d'un corps
         * « { » ou d'un « ; » — avec type de retour = dernier
         * identifiant du préfixe (modificateurs et annotations
         * filtrés), paramètres aplatis. Les types IMBRIQUÉS sont
         * sautés (enregistrement global) ; les corps de méthode sont
         * sautés (leurs instructions ne sont pas des membres).
         */
        private static void scannerMembres(String grille, int start, int end,
                TypeBuffer t, Grille g) {
            StringBuilder préfixe = new StringBuilder();
            int i = start;
            while (i < end) {
                char c = grille.charAt(i);
                if (c == '@') {
                    // Annotation : usage (préfixe du membre, filtrée au
                    // décodage) ou déclaration de type imbriqué.
                    int wStart = i + 1;
                    int wEnd = wStart;
                    while (wEnd < end && Character.isJavaIdentifierPart(grille.charAt(wEnd))) {
                        wEnd++;
                    }
                    String mot = grille.substring(wStart, wEnd);
                    if ("interface".equals(mot)) {
                        i = sauterTypeImbriqué(grille, wEnd, end);
                        préfixe.setLength(0);
                        continue;
                    }
                    préfixe.setLength(0); // l'annotation ouvre un préfixe
                    i = wEnd;
                    continue;
                }
                if (Character.isJavaIdentifierStart(c)) {
                    int wStart = i;
                    while (i < end && Character.isJavaIdentifierPart(grille.charAt(i))) {
                        i++;
                    }
                    String mot = grille.substring(wStart, i);
                    // Une déclaration de type imbriqué : son ENTIER
                    // (nom, clause, corps) est sauté — il vivra sa vie.
                    if (mot.equals("class") || mot.equals("interface")
                            || mot.equals("enum") || mot.equals("record")) {
                        i = sauterTypeImbriqué(grille, i, end);
                        préfixe.setLength(0);
                        continue;
                    }
                    int j = i;
                    while (j < end && Character.isWhitespace(grille.charAt(j))) j++;
                    if (j < end && grille.charAt(j) == '(') {
                        int close = fermanteParen(grille, j, end);
                        int après = close >= 0 ? close + 1 : j;
                        if (close >= 0) {
                            int k = après;
                            while (k < end && Character.isWhitespace(grille.charAt(k))) k++;
                            if (k < end && grille.startsWith("throws", k)) {
                                while (k < end && grille.charAt(k) != '{'
                                        && grille.charAt(k) != ';') {
                                    k++;
                                }
                            }
                            while (k < end && Character.isWhitespace(grille.charAt(k))) k++;
                            if (k < end && (grille.charAt(k) == '{'
                                    || grille.charAt(k) == ';')) {
                                déclarer(t, préfixe.toString(), mot,
                                        grille.substring(j + 1, close),
                                        grille.charAt(k) == '{');
                                if (grille.charAt(k) == '{') {
                                    int corpsFin = fermante(grille, k);
                                    i = Math.min(corpsFin + 1, end);
                                } else {
                                    i = k + 1;
                                }
                                préfixe.setLength(0);
                                continue;
                            }
                        }
                        // Parenthèses sans corps : un appel, un
                        // constructeur d'enum… pas un membre.
                    }
                    préfixe.append(mot).append(' ');
                    continue;
                }
                if (c == ';' || c == '{' || c == '}') {
                    préfixe.setLength(0); // frontière de membre
                } else if (!Character.isWhitespace(c)) {
                    préfixe.append(c);
                }
                i++;
            }
        }

        /**
         * Décode un préfixe de membre : les annotations sont retirées,
         * les modificateurs filtrés — le DERNIER identifiant restant
         * est le type de retour (« List<String> » d'un générique
         * survit aux chevrons collés du préfixe). Sans type de retour
         * (constructeur, constante d'enum) : pas un membre abstrait
         * possible — ignoré.
         */
        private static void déclarer(TypeBuffer t, String préfixe, String nom,
                String paramsBruts, boolean aCorps) {
            // Un constructeur ne compte pas (ni l'arité d'une constante
            // d'enum avec arguments).
            if (nom.equals(t.nom)) return;
            String pré = ANNOTATION_USAGE.matcher(préfixe).replaceAll(" ");
            List<String> params = découperParams(paramsBruts);
            String retour = null;
            for (String mot : pré.trim().split("\\s+")) {
                if (mot.isEmpty() || MODIFICATEURS.contains(mot)) continue;
                if (!Character.isJavaIdentifierStart(mot.charAt(0))) continue;
                retour = mot;
            }
            if (retour == null) return; // pas de type de retour : ignoré
            t.membres.add(new MembreBuffer(retour, nom, params, aCorps));
        }

        /** Les paramètres aplatis, séparés par les virgules de tête. */
        private static List<String> découperParams(String bruts) {
            List<String> out = new ArrayList<>();
            String s = bruts.trim();
            if (s.isEmpty()) return out;
            int depth = 0;
            int start = 0;
            for (int i = 0; i <= s.length(); i++) {
                char c = i < s.length() ? s.charAt(i) : ',';
                if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
                else if (c == ')' || c == ']' || c == '}' || c == '>') depth--;
                else if ((c == ',' && depth == 0) || i == s.length()) {
                    String p = s.substring(start, i).trim()
                            .replaceAll("\\s+", " ");
                    if (!p.isEmpty()) out.add(p);
                    start = i + 1;
                }
            }
            return out;
        }

        /**
         * Saute la déclaration d'un type imbriqué depuis après son
         * mot-clé : nom, clause d'héritage, corps ENTIER — retourne
         * l'offset APRÈS la fermante du corps.
         */
        private static int sauterTypeImbriqué(String grille, int aprèsMotClé, int end) {
            int corps = grille.indexOf('{', aprèsMotClé);
            if (corps < 0 || corps >= end) return Math.min(aprèsMotClé, end);
            return Math.min(fermante(grille, corps) + 1, end);
        }

        /** La fermante appariée d'une accolade ouvrante (ou la fin). */
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

        /** La fermante appariée d'une parenthèse, bornée à end. */
        private static int fermanteParen(String grille, int ouvrante, int end) {
            int depth = 0;
            for (int i = ouvrante; i < end; i++) {
                char c = grille.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        /** L'indentation de la ligne qui porte l'offset donné. */
        private static String indentationDe(String grille, int offset) {
            int début = grille.lastIndexOf('\n', offset) + 1;
            StringBuilder sb = new StringBuilder();
            for (int i = début; i < grille.length()
                    && (grille.charAt(i) == ' ' || grille.charAt(i) == '\t'); i++) {
                sb.append(grille.charAt(i));
            }
            String ind = sb.toString();
            return ind + "    "; // un membre vit un cran plus profond
        }
    }

    /**
     * Masque chaînes et commentaires du texte entier — littéraux et
     * commentaires remplacés par des espaces, offsets et retours à la
     * ligne préservés (même algorithme que le masque de
     * {@link MiniJavaCompletion}, partagé en private static ici pour
     * l'autonomie du moteur).
     */
    private static String masquer(String text) {
        boolean[] out = new boolean[text.length()];
        boolean inString = false, inLine = false, inBlock = false;
        char quote = '\0';
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inLine) {
                out[i] = true;
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                out[i] = true;
                if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    out[i + 1] = true;
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out[i] = true;
                if (c == '\\') {
                    if (i + 1 < text.length()) out[i + 1] = true;
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                out[i] = true;
                inLine = true;
                out[i + 1] = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                out[i] = true;
                inBlock = true;
                out[i + 1] = true;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out[i] = true;
            }
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            sb.append(out[i] ? ' ' : text.charAt(i));
        }
        return sb.toString();
    }
}

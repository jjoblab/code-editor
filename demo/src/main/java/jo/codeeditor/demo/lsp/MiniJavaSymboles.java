package jo.codeeditor.demo.lsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les SYMBOLES du mini Java de la démo — références et renommage au
 * niveau du fichier, avec des PORTÉES honnêtes.
 *
 * <p>Le symbole sous le caret est résolu par nature, dans l'ordre :</p>
 * <ol>
 *   <li><b>Méthode</b> — le mot ouvre un appel ({@code label(}) et une
 *       méthode de ce nom est déclarée dans le fichier : les usages
 *       sont les APPELS (le mot suivi de « ( », jamais qualifié par un
 *       point). Le renommage est REFUSÉ en cas de surcharges (deux
 *       déclarations du même nom : un renommage aveugle toucherait
 *       les deux — la prudence l'emporte) ;</li>
 *   <li><b>Local / paramètre</b> — le mot est déclaré dans la MÉTHODE
 *       englobante du caret : les occurrences comptent DANS CETTE
 *       MÉTHODE seulement (le même nom dans une autre méthode est un
 *       AUTRE symbole) ;</li>
 *   <li><b>Champ</b> — le mot est déclaré au niveau du TYPE englobant :
 *       les occurrences comptent dans le corps du type, HORS des
 *       méthodes qui déclarent leur PROPRE local de ce nom (l'ombre
 *       du local masque le champ, comme en Java) ;</li>
 *   <li><b>Type</b> — classe, interface, enum, record, @interface
 *       déclarés dans le fichier : toutes les occurrences, la
 *       déclaration comprise au renommage ;</li>
 *   <li><b>Constante d'enum</b> — les usages qualifiés par SON enum
 *       ({@code Couleur.ROUGE}, une variable du type, les étiquettes
 *       nues de {@code switch}) ; si un AUTRE enum du fichier déclare
 *       le même nom de constante, TOUT est refusé (l'ambiguïté
 *       n'admet pas de demi-mesure).</li>
 * </ol>
 *
 * <p>Chaînes et commentaires sont MASQUÉS (une occurrence dans un
 * littéral n'est pas une référence), les mots des mots-clés ne
 * comptent jamais, et un symbole introuvable ne sert RIEN — le client
 * affiche « aucune référence » et le popup de renommage refuse
 * proprement. Pur JVM, testable directement en JUnit.</p>
 */
public final class MiniJavaSymboles {

    private MiniJavaSymboles() {}

    // ════════════════════════════════════════════════════════════════
    // API publique
    // ════════════════════════════════════════════════════════════════

    /**
     * Les RÉFÉRENCES du symbole sous le caret — les USAGES, la
     * déclaration EXCLUE (le contrat du client,
     * {@code includeDeclaration = false}). Liste vide si le symbole
     * n'est pas résolu.
     */
    public static List<int[]> références(String text, int caret) {
        return résoudre(text, caret, false);
    }

    /**
     * La plage du mot si le symbole est RENAMMABLE — le placeholder
     * du popup de renommage (prepareRename). Null sinon : le popup
     * refuse proprement à l'ouverture.
     */
    public static int[] préparation(String text, int caret) {
        Vue vue = new Vue(text);
        int[] mot = vue.motÀ(caret);
        if (mot == null) return null;
        return vue.renommable(mot[0], mot[1]) ? mot : null;
    }

    /**
     * Toutes les occurrences du renommage — la déclaration ET les
     * usages, dans l'ordre du document. Null si le symbole n'est pas
     * renommable (introuvable, surchargé, ambigu).
     */
    public static List<int[]> occurrencesDuRenommage(String text, int caret) {
        return résoudre(text, caret, true);
    }

    /** Le cœur partagé : les usages (renommage = faux) ou tout (vrai). */
    private static List<int[]> résoudre(String text, int caret,
            boolean pourRenommage) {
        if (text == null || caret < 0 || caret > text.length()) {
            return pourRenommage ? null : new ArrayList<int[]>();
        }
        Vue vue = new Vue(text);
        int[] mot = vue.motÀ(caret);
        if (mot == null) return pourRenommage ? null : new ArrayList<int[]>();
        List<int[]> out = vue.occurrences(mot[0], mot[1], pourRenommage);
        if (pourRenommage) {
            // Le renommage refuse NET : introuvable, surchargé, ambigu.
            return out == null || out.isEmpty() ? null : out;
        }
        // Les références répondent VIDE (le client attend une liste,
        // jamais null — le popup « aucune référence »).
        return out != null ? out : new ArrayList<int[]>();
    }

    // ════════════════════════════════════════════════════════════════
    // Les scans
    // ════════════════════════════════════════════════════════════════

    private static final Set<String> MOTS_CLÉS = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "return", "new", "do",
            "try", "else", "throw", "assert", "synchronized", "case", "super",
            "this", "import", "package", "class", "interface", "enum",
            "record", "extends", "implements", "static", "final", "public",
            "private", "protected", "void", "int", "long", "short", "byte",
            "char", "float", "double", "boolean", "var", "null", "true",
            "false", "instanceof", "throws", "default", "break", "continue",
            "finally", "abstract", "native", "transient", "volatile",
            "strictfp", "permits", "sealed", "yield"));

    private static final Pattern TYPE_DÉCL = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern ANNOTATION_DÉCL = Pattern.compile(
            "@interface\\s+(\\w+)");
    private static final Pattern TÊTE = Pattern.compile(
            "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(");
    private static final Pattern DÉCL_VAR = Pattern.compile(
            "(?:(?:public|private|protected|static|final)\\s+)?"
                    + "((?:int|long|short|byte|char|float|double|boolean|var"
                    + "|[A-Z][\\w.$]*)"
                    + "(?:\\s*<[^<>]*>)?(?:\\s*\\[\\])?)\\s+"
                    + "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*(?:=[^=]|;|,|\\)|:)");

    /**
     * La vue du buffer : grille masquée, types avec leurs corps,
     * méthodes avec leurs têtes et leurs corps, déclarations de
     * variables (locales, paramètres, champs) avec leurs PORTÉES,
     * constantes d'enum avec leurs régions — les mêmes approximations
     * déterministes que les moteurs frères.
     */
    private static final class Vue {

        final String grille;
        final List<TypeSpan> types = new ArrayList<>();
        final List<MéthodeSpan> méthodes = new ArrayList<>();
        final List<Déclarée> locaux = new ArrayList<>();
        final List<Déclarée> champs = new ArrayList<>();
        final List<Constante> constantes = new ArrayList<>();
        final Map<String, String> variables = new HashMap<>();

        /** Un type déclaré : nom, plage du nom, corps. */
        static final class TypeSpan {
            final String nom;
            final int nomDébut, nomFin;
            final int corpsDébut, corpsFin;
            TypeSpan(String nom, int nomDébut, int nomFin,
                    int corpsDébut, int corpsFin) {
                this.nom = nom;
                this.nomDébut = nomDébut;
                this.nomFin = nomFin;
                this.corpsDébut = corpsDébut;
                this.corpsFin = corpsFin;
            }
        }

        /** Une méthode déclarée : nom, plage du nom, corps, params. */
        static final class MéthodeSpan {
            final String nom;
            final int nomDébut, nomFin;
            final int têteDébut, corpsFin;
            MéthodeSpan(String nom, int nomDébut, int nomFin,
                    int têteDébut, int corpsFin) {
                this.nom = nom;
                this.nomDébut = nomDébut;
                this.nomFin = nomFin;
                this.têteDébut = têteDébut;
                this.corpsFin = corpsFin;
            }
        }

        /** Une déclaration de variable : nom, plage, PORTÉE. */
        static final class Déclarée {
            final String nom;
            final int nomDébut, nomFin;
            final int portéeDébut, portéeFin;
            Déclarée(String nom, int nomDébut, int nomFin,
                    int portéeDébut, int portéeFin) {
                this.nom = nom;
                this.nomDébut = nomDébut;
                this.nomFin = nomFin;
                this.portéeDébut = portéeDébut;
                this.portéeFin = portéeFin;
            }
        }

        /** Une constante d'enum : nom, plage, région, enum porteur. */
        static final class Constante {
            final String nom;
            final int nomDébut, nomFin;
            final int régionDébut, régionFin;
            final String enumNom;
            Constante(String nom, int nomDébut, int nomFin,
                    int régionDébut, int régionFin, String enumNom) {
                this.nom = nom;
                this.nomDébut = nomDébut;
                this.nomFin = nomFin;
                this.régionDébut = régionDébut;
                this.régionFin = régionFin;
                this.enumNom = enumNom;
            }
        }

        Vue(String text) {
            boolean[] masque = masquer(text, text.length());
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                sb.append(masque[i] ? ' ' : text.charAt(i));
            }
            grille = sb.toString();
            lireTypes();
            lireMéthodes();
            lireVariables();
            lireConstantes();
        }

        // ── Les déclarations ────────────────────────────────────

        private void lireTypes() {
            Matcher m = TYPE_DÉCL.matcher(grille);
            while (m.find()) {
                int corps = grille.indexOf('{', m.end());
                if (corps < 0) continue;
                types.add(new TypeSpan(m.group(2), m.start(2), m.end(2),
                        corps, fermante(corps)));
            }
            Matcher a = ANNOTATION_DÉCL.matcher(grille);
            while (a.find()) {
                int corps = grille.indexOf('{', a.end());
                if (corps < 0) continue;
                types.add(new TypeSpan(a.group(1), a.start(1), a.end(1),
                        corps, fermante(corps)));
            }
        }

        /**
         * Les têtes de méthode : un identifiant, des parenthèses
         * APPARIÉES, puis un bloc — têtes de contrôle et
         * CONSTRUCTEURS exclus (le renommage d'un constructeur est
         * celui de sa classe, la nature « type » s'en charge).
         */
        private void lireMéthodes() {
            Matcher m = TÊTE.matcher(grille);
            while (m.find()) {
                String nom = m.group(1);
                if (MOTS_CLÉS.contains(nom)) continue;
                // Une déclaration n'est JAMAIS qualifiée : « sb.append( »
                // est l'appel d'une méthode d'AILLEURS. Le type de retour
                // devant le nom (« int autre( ») est, lui, LÉGITIME —
                // seule la queue après les parenthèses (un bloc, ou une
                // clause throws puis un bloc) signe la déclaration.
                int avant = m.start(1);
                while (avant > 0
                        && Character.isWhitespace(grille.charAt(avant - 1))) {
                    avant--;
                }
                if (avant > 0 && grille.charAt(avant - 1) == '.') {
                    continue;
                }
                // Le MOT immédiatement avant : « new » (classe
                // anonyme) ou « record » (tête d'enregistrement) signe
                // une CONSTRUCTION, pas une méthode — mais le type de
                // retour peut être un PRIMITIF, mot-clé légitime
                // (« int calculer( », « void t( ») : seuls ces deux-là
                // sont refusés.
                int finAvant = avant;
                int débutAvant = finAvant;
                while (débutAvant > 0
                        && Character.isJavaIdentifierPart(
                                grille.charAt(débutAvant - 1))) {
                    débutAvant--;
                }
                if (débutAvant < finAvant) {
                    String avantNom = grille.substring(débutAvant, finAvant);
                    if ("new".equals(avantNom) || "record".equals(avantNom)) {
                        continue;
                    }
                }
                int close = fermée(m.end() - 1);
                if (close < 0) continue;
                int k = close + 1;
                while (k < grille.length()
                        && Character.isWhitespace(grille.charAt(k))) k++;
                if (grille.startsWith("throws", k)) {
                    while (k < grille.length() && grille.charAt(k) != '{'
                            && grille.charAt(k) != ';') k++;
                }
                if (k >= grille.length() || grille.charAt(k) != '{') continue;
                TypeSpan porteur = typeContenant(m.start(1));
                if (porteur != null && nom.equals(porteur.nom)) continue;
                MéthodeSpan mé = new MéthodeSpan(nom, m.start(1), m.end(1),
                        m.start(1), fermante(k));
                méthodes.add(mé);
                // Les paramètres : le DERNIER identifiant de chaque
                // morceau de profondeur 0, AVEC sa plage — la portée
                // couvre la méthode entière (tête et corps).
                int depth = 0;
                int start = m.end();
                for (int i = m.end(); i <= close; i++) {
                    char c = i < close ? grille.charAt(i) : ',';
                    if (i < close && (c == '(' || c == '[' || c == '<')) {
                        depth++;
                    } else if (i < close && (c == ')' || c == ']'
                            || c == '>')) {
                        depth--;
                    } else if (c == ',' && depth == 0) {
                        int[] nomParam = dernierMotEntre(start, i);
                        if (nomParam != null) {
                            locaux.add(new Déclarée(
                                    grille.substring(nomParam[0], nomParam[1]),
                                    nomParam[0], nomParam[1],
                                    mé.têteDébut, mé.corpsFin));
                        }
                        start = i + 1;
                    }
                }
            }
        }

        /**
         * Les déclarations de variables : dans un corps de méthode →
         * LOCALE (portée = la méthode) ; au niveau d'un type → CHAMP
         * (portée = le corps du type).
         */
        private void lireVariables() {
            Matcher m = DÉCL_VAR.matcher(grille);
            while (m.find()) {
                String nom = m.group(2);
                if (MOTS_CLÉS.contains(nom)) continue;
                int débutNom = m.start(2);
                int finNom = m.end(2);
                variables.put(nom, m.group(1));
                MéthodeSpan mé = méthodeContenante(débutNom);
                if (mé != null) {
                    locaux.add(new Déclarée(nom, débutNom, finNom,
                            mé.têteDébut, mé.corpsFin));
                    continue;
                }
                TypeSpan type = typeContenant(débutNom);
                if (type != null) {
                    champs.add(new Déclarée(nom, débutNom, finNom,
                            type.corpsDébut, type.corpsFin));
                }
            }
        }

        /**
         * Les constantes d'enum : la région des constantes du corps
         * (jusqu'au premier « ; » de profondeur 0), identifiant par
         * morceau, arguments parenthésés exclus.
         */
        private void lireConstantes() {
            for (TypeSpan t : types) {
                if (!estEnum(t)) continue;
                int régionFin = t.corpsFin;
                int depth = 0;
                for (int i = t.corpsDébut + 1; i < t.corpsFin; i++) {
                    char c = grille.charAt(i);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                    else if (c == ';' && depth == 0) {
                        régionFin = i;
                        break;
                    }
                }
                int depthC = 0;
                int start = t.corpsDébut + 1;
                for (int i = t.corpsDébut + 1; i <= régionFin; i++) {
                    char c = i < régionFin ? grille.charAt(i) : ',';
                    if (i < régionFin && c == '(') depthC++;
                    else if (i < régionFin && c == ')') depthC--;
                    else if (c == ',' && depthC == 0) {
                        String morceau = grille.substring(start, i).trim();
                        int paren = morceau.indexOf('(');
                        if (paren >= 0) {
                            morceau = morceau.substring(0, paren).trim();
                        }
                        if (!morceau.isEmpty()
                                && estIdentifiant(morceau)) {
                            constantes.add(new Constante(morceau,
                                    start + grille.substring(start, i)
                                            .indexOf(morceau),
                                    start + grille.substring(start, i)
                                            .indexOf(morceau)
                                            + morceau.length(),
                                    t.corpsDébut, régionFin, t.nom));
                        }
                        start = i + 1;
                    }
                }
            }
        }

        /** Un corps d'enum se reconnaît à son mot-clé de déclaration —
         *  le mot « enum » FINIT juste avant le nom (espaces sautés). */
        private boolean estEnum(TypeSpan t) {
            int fin = t.nomDébut;
            while (fin > 0
                    && Character.isWhitespace(grille.charAt(fin - 1))) {
                fin--;
            }
            return fin >= 4
                    && grille.substring(fin - 4, fin).equals("enum")
                    && (fin - 5 < 0 || !Character.isJavaIdentifierPart(
                            grille.charAt(fin - 5)));
        }

        // ── Les contenants ───────────────────────────────────────

        /** Le type le plus INTÉRIEUR dont le corps contient l'offset. */
        private TypeSpan typeContenant(int offset) {
            TypeSpan out = null;
            for (TypeSpan t : types) {
                if (offset > t.corpsDébut && offset < t.corpsFin) {
                    if (out == null || (t.corpsFin - t.corpsDébut)
                            < (out.corpsFin - out.corpsDébut)) {
                        out = t;
                    }
                }
            }
            return out;
        }

        /** La méthode dont le corps contient l'offset. */
        private MéthodeSpan méthodeContenante(int offset) {
            for (MéthodeSpan m : méthodes) {
                if (offset > m.têteDébut && offset < m.corpsFin) return m;
            }
            return null;
        }

        // ── La résolution ────────────────────────────────────────

        /** Le mot (identifiant non masqué) CONTENANT l'offset. */
        int[] motÀ(int caret) {
            int fin = Math.min(caret, grille.length());
            int début = fin;
            while (début > 0
                    && Character.isJavaIdentifierPart(
                            grille.charAt(début - 1))) {
                début--;
            }
            while (fin < grille.length()
                    && Character.isJavaIdentifierPart(grille.charAt(fin))) {
                fin++;
            }
            if (début == fin) return null;
            if (!Character.isJavaIdentifierStart(grille.charAt(début))) {
                return null;
            }
            String mot = grille.substring(début, fin);
            if (MOTS_CLÉS.contains(mot)) return null;
            return new int[]{début, fin};
        }

        /**
         * Les occurrences du mot selon sa nature — les USAGES
         * ({@code pourRenommage = faux}) ou TOUT ({@code vrai}).
         * Null : introuvable, surchargé, ambigu.
         */
        List<int[]> occurrences(int motDébut, int motFin,
                boolean pourRenommage) {
            String mot = grille.substring(motDébut, motFin);
            List<int[]> brutes = occurrencesBrutes(mot);
            if (brutes.isEmpty()) return null;

            // 1. Méthode : le mot ouvre un appel ET une méthode de ce
            //    nom est déclarée.
            if (ouvreAppel(motFin)) {
                List<MéthodeSpan> déclarées = new ArrayList<>();
                for (MéthodeSpan m : méthodes) {
                    if (m.nom.equals(mot)) déclarées.add(m);
                }
                if (!déclarées.isEmpty()) {
                    // Surchargé : les références se servent (tous les
                    // appels du nom), le renommage se REFUSE.
                    if (pourRenommage && déclarées.size() > 1) return null;
                    List<int[]> out = new ArrayList<>();
                    for (int[] o : brutes) {
                        boolean déclaration = false;
                        for (MéthodeSpan m : déclarées) {
                            if (o[0] == m.nomDébut) déclaration = true;
                        }
                        if (déclaration) {
                            if (pourRenommage) out.add(o);
                            continue;
                        }
                        if (!ouvreAppel(o[1])) continue;
                        // Un appel qualifié compte pour le fichier si le
                        // qualificatif est « this » ou un TYPE du fichier
                        // (appel statique) — une variable qualifie une
                        // méthode d'AILLEURS (sb.append(…)).
                        String qual = qualificatif(o[0]);
                        if (qual != null && !"this".equals(qual)
                                && typeNommé(qual) == null) {
                            continue;
                        }
                        out.add(o);
                    }
                    return trierDédupe(out);
                }
            }

            // 2. Local / paramètre : déclaré dans la méthode du caret.
            Déclarée local = null;
            for (Déclarée d : locaux) {
                if (d.nom.equals(mot) && motDébut >= d.portéeDébut
                        && motFin <= d.portéeFin) {
                    local = d; // le dernier gagne (le plus profond)
                }
            }
            if (local != null) {
                List<int[]> out = new ArrayList<>();
                for (int[] o : brutes) {
                    if (o[0] < local.portéeDébut
                            || o[1] > local.portéeFin) continue;
                    boolean déclaration = o[0] == local.nomDébut;
                    if (déclaration) {
                        if (pourRenommage) out.add(o);
                        continue;
                    }
                    out.add(o);
                }
                return trierDédupe(out);
            }

            // 3. Champ : déclaré au niveau du type englobant — les
            //    méthodes qui déclarent leur PROPRE local masquent.
            Déclarée champ = null;
            for (Déclarée d : champs) {
                if (d.nom.equals(mot) && motDébut >= d.portéeDébut
                        && motFin <= d.portéeFin) {
                    champ = d;
                }
            }
            if (champ != null) {
                List<int[]> out = new ArrayList<>();
                for (int[] o : brutes) {
                    if (o[0] < champ.portéeDébut
                            || o[1] > champ.portéeFin) continue;
                    MéthodeSpan mé = méthodeContenante(o[0]);
                    if (mé != null) {
                        boolean masqué = false;
                        for (Déclarée d : locaux) {
                            if (d.nom.equals(mot)
                                    && o[0] >= d.portéeDébut
                                    && o[1] <= d.portéeFin) {
                                masqué = true;
                            }
                        }
                        if (masqué) continue;
                    }
                    boolean déclaration = o[0] == champ.nomDébut;
                    if (déclaration && !pourRenommage) continue;
                    out.add(o);
                }
                return trierDédupe(out);
            }

            // 4. Type déclaré.
            for (TypeSpan t : types) {
                if (!t.nom.equals(mot)) continue;
                List<int[]> out = new ArrayList<>();
                for (int[] o : brutes) {
                    boolean déclaration = o[0] == t.nomDébut;
                    if (déclaration && !pourRenommage) continue;
                    out.add(o);
                }
                return trierDédupe(out);
            }

            // 5. Constante d'enum — refusée si un autre enum déclare
            //    le même nom.
            Constante laConstante = null;
            for (Constante c : constantes) {
                if (!c.nom.equals(mot)) continue;
                if (laConstante != null && !c.enumNom.equals(
                        laConstante.enumNom)) {
                    return null; // ambiguïté entre enums
                }
                if (motDébut >= c.régionDébut && motFin <= c.régionFin) {
                    laConstante = c;
                } else if (laConstante == null) {
                    laConstante = c;
                }
            }
            if (laConstante != null) {
                List<int[]> out = new ArrayList<>();
                for (int[] o : brutes) {
                    boolean dansRégion = o[0] >= laConstante.régionDébut
                            && o[1] <= laConstante.régionFin;
                    if (dansRégion) {
                        if (pourRenommage) out.add(o);
                        continue;
                    }
                    // Qualifié : le qualificatif décide.
                    String qual = qualificatif(o[0]);
                    if (qual != null) {
                        boolean leSien = qual.equals(laConstante.enumNom);
                        if (!leSien) {
                            String typeVar = variables.get(qual);
                            leSien = typeVar != null && dernierSegment(
                                    typeVar).equals(laConstante.enumNom);
                        }
                        if (!leSien) continue;
                    }
                    out.add(o);
                }
                return trierDédupe(out);
            }
            return null;
        }

        /** Le symbole du mot est-il RENAMMABLE ? */
        boolean renommable(int motDébut, int motFin) {
            List<int[]> out = occurrences(motDébut, motFin, true);
            return out != null && !out.isEmpty();
        }

        // ── Les occurrences brutes ───────────────────────────────

        /** Toutes les occurrences du mot, bords d'identifiants, hors
         *  chaînes et commentaires (la grille est masquée). */
        private List<int[]> occurrencesBrutes(String mot) {
            List<int[]> out = new ArrayList<>();
            int idx = 0;
            while ((idx = grille.indexOf(mot, idx)) >= 0) {
                int avant = idx - 1;
                int après = idx + mot.length();
                boolean bordAvant = avant < 0
                        || !Character.isJavaIdentifierPart(grille.charAt(avant));
                boolean bordAprès = après >= grille.length()
                        || !Character.isJavaIdentifierPart(
                                grille.charAt(après));
                if (bordAvant && bordAprès) {
                    out.add(new int[]{idx, après});
                }
                idx = après;
            }
            return out;
        }

        /** Le caractère (espaces sautées) après la fin du mot ouvre-t-il
         *  une liste d'arguments ? */
        private boolean ouvreAppel(int finMot) {
            int i = finMot;
            while (i < grille.length()
                    && Character.isWhitespace(grille.charAt(i))) i++;
            return i < grille.length() && grille.charAt(i) == '(';
        }

        /** Le caractère (espaces sautées) avant le début du mot est-il
         *  un point ? */
        private boolean précédéDunPoint(int débutMot) {
            int i = débutMot;
            while (i > 0
                    && Character.isWhitespace(grille.charAt(i - 1))) i--;
            return i > 0 && grille.charAt(i - 1) == '.';
        }

        /** Le qualificatif AVANT une occurrence (« Couleur.ROUGE » →
         *  « Couleur »), ou null. */
        private String qualificatif(int débutMot) {
            int i = débutMot;
            while (i > 0
                    && Character.isWhitespace(grille.charAt(i - 1))) i--;
            if (i == 0 || grille.charAt(i - 1) != '.') return null;
            int fin = i - 1;
            int début = fin;
            while (début > 0
                    && Character.isJavaIdentifierPart(
                            grille.charAt(début - 1))) {
                début--;
            }
            if (début == fin) return null;
            return grille.substring(début, fin);
        }

        /** Tri par offset + déduplication (une occurrence, une fois). */
        private static List<int[]> trierDédupe(List<int[]> out) {
            out.sort(java.util.Comparator.comparingInt(o -> o[0]));
            List<int[]> uniques = new ArrayList<>(out.size());
            int dernier = -1;
            for (int[] o : out) {
                if (o[0] != dernier) {
                    uniques.add(o);
                    dernier = o[0];
                }
            }
            return uniques;
        }

        // ── Utilitaires ──────────────────────────────────────────

        /** Le type du fichier portant ce nom (null sinon). */
        private TypeSpan typeNommé(String nom) {
            for (TypeSpan t : types) {
                if (t.nom.equals(nom)) return t;
            }
            return null;
        }

        /** Le DERNIER identifiant d'une plage de la grille, AVEC sa
         *  plage — le nom d'un paramètre dans son morceau. Null si le
         *  morceau ne nomme rien (ou nomme un mot-clé). */
        private int[] dernierMotEntre(int début, int fin) {
            int finMot = fin;
            while (finMot > début && !Character.isJavaIdentifierPart(
                    grille.charAt(finMot - 1))) finMot--;
            int débutMot = finMot;
            while (débutMot > début && Character.isJavaIdentifierPart(
                    grille.charAt(débutMot - 1))) débutMot--;
            if (débutMot == finMot) return null;
            if (Character.isDigit(grille.charAt(débutMot))) return null;
            if (MOTS_CLÉS.contains(grille.substring(débutMot, finMot))) {
                return null;
            }
            return new int[]{débutMot, finMot};
        }

        /**
 La fermante appariée d'une '(' (ou -1). */
        private int fermée(int ouvrante) {
            int depth = 0;
            for (int i = ouvrante; i < grille.length(); i++) {
                char c = grille.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        /** La fermante appariée d'une '{' (ou la fin). */
        private int fermante(int ouvrante) {
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

        /** Le dernier segment d'un nom qualifié. */
        private static String dernierSegment(String qualifié) {
            if (qualifié == null) return "";
            int p = qualifié.lastIndexOf('.');
            return p >= 0 ? qualifié.substring(p + 1) : qualifié;
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
    }
}

package jo.codeeditor.demo.lsp;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La documentation du CLASSPATH du mini Java de la démo — la réflexion
 * sur le runtime comme dernière source de vérité.
 *
 * <p>Quatrième source des moteurs de la démo (après la base fixe, la
 * bibliothèque embarquée de {@link MiniJavaSignatures} et le buffer
 * lui-même) : quand un récepteur n'est NI dans la table embarquée, NI
 * une variable de type déclaré dans le fichier, la classe est chargée
 * PAR RÉFLEXION ({@code Class.forName} SANS initialisation — jamais
 * d'exécuteur statique) et ses méthodes publiques deviennent de vraies
 * signatures. Les paramètres sont nommés par la réflexion
 * ({@code arg0}, {@code arg1}… le JDK ne livre pas les noms réels),
 * les exceptions levées sont réelles, et l'ordre de présentation est
 * rendu déterministe (arité puis libellé — {@code getMethods()} ne
 * promet rien).</p>
 *
 * <p><b>Prudence runtime :</b> le moteur tourne sur la JVM des tests
 * comme sur l'ART de l'appareil — chaque accès est borné par
 * {@code try/catch(Throwable)} (une classe absente d'un niveau d'API
 * est un échec de chargement ORDINAIRE, jamais un crash du serveur),
 * et les résolutions — positives COMME négatives — sont mémoïsées :
 * un nom inconnu ne coûte qu'une fois son balayage de préfixes.</p>
 *
 * <p>Pur JVM, aucune dépendance Android : testable directement en
 * JUnit. Le code NE mentionne aucun projet tiers : les choix de
 * conception (réflexion différée, noms de substitution, mémoïsation
 * des échecs) sont documentés ici.</p>
 */
final class MiniJavaClasspath {

    private MiniJavaClasspath() {}

    // ════════════════════════════════════════════════════════════════
    // Résolution de classe — nom simple contre préfixes de paquets
    // ════════════════════════════════════════════════════════════════

    /**
     * Les paquets balayés pour un nom simple — l'ordre compte (les
     * usages courants d'abord : {@code List} veut dire
     * {@code java.util.List}, jamais un hypothétique List d'ailleurs).
     */
    private static final String[] PRÉFIXES = {
            "java.lang", "java.util", "java.util.function",
            "java.util.stream", "java.io", "java.math", "java.time",
            "java.text", "java.util.concurrent",
    };

    /**
     * Mémoïsation des résolutions — positives ET négatives : un nom
     * inconnu ne re-balaye les préfixes qu'une seule fois par
     * processus (le mini serveur vit longtemps, les requêtes
     * répétées).
     */
    private static final Map<String, Class<?>> CACHE = new ConcurrentHashMap<>();

    /** La valeur sentinelle des échecs (null n'entre pas dans une
     *  ConcurrentHashMap). */
    private static final Class<?> ABSENT = Object.class;

    /**
     * Charge la classe d'un nom simple ({@code Integer}, {@code Arrays})
     * ou qualifié ({@code java.util.Optional}) — SANS initialisation.
     * Null si aucun paquet connu ne la porte (ou si la réflexion
     * refuse : sécurité, API absente — un échec ordinaire).
     */
    static Class<?> classe(String nom) {
        if (nom == null || nom.isEmpty()) return null;
        // Le nom tel quel ne peut être complet que s'il contient un
        // point — un nom simple sans point serait du java.lang nu.
        if (nom.indexOf('.') >= 0) {
            return charger(nom);
        }
        Class<?> dansCache = CACHE.get(nom);
        if (dansCache != null) {
            return dansCache == ABSENT ? null : dansCache;
        }
        for (String préfixe : PRÉFIXES) {
            Class<?> c = charger(préfixe + "." + nom);
            if (c != null) {
                CACHE.put(nom, c);
                return c;
            }
        }
        CACHE.put(nom, ABSENT);
        return null;
    }

    /** Un chargement isolé : tout échec vaut null, jamais d'exception. */
    private static Class<?> charger(String fqn) {
        try {
            ClassLoader l = MiniJavaClasspath.class.getClassLoader();
            return Class.forName(fqn, false, l != null ? l
                    : ClassLoader.getSystemClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Méthodes publiques → entrées de signature
    // ════════════════════════════════════════════════════════════════

    /**
     * Les méthodes publiques d'un nom de récepteur, prêtes pour l'aide
     * de signature et le survol — liste VIDE si la classe est
     * inconnue du classpath.
     *
     * @param récepteur nom simple ou qualifié ({@code Integer},
     *                  {@code java.util.Arrays})
     * @param nom       le nom de la méthode appelée
     */
    static List<MiniJavaSignatures.Entry> méthodesRécepteur(
            String récepteur, String nom) {
        Class<?> c = classe(récepteur);
        return c == null ? Collections.emptyList() : méthodes(c, nom);
    }

    /**
     * Les méthodes publiques portant ce nom — dédupliquées par
     * libellé (les ponts du compilateur doublonnent), ordonnées par
     * arité puis libellé (présentation déterministe).
     */
    static List<MiniJavaSignatures.Entry> méthodes(Class<?> c, String nom) {
        if (c == null || nom == null || nom.isEmpty()) {
            return Collections.emptyList();
        }
        Method[] brutes;
        try {
            brutes = c.getMethods();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        Map<String, MiniJavaSignatures.Entry> uniques = new LinkedHashMap<>();
        for (Method m : brutes) {
            if (m == null || !nom.equals(m.getName())) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            if (!Modifier.isPublic(m.getModifiers())) continue;
            MiniJavaSignatures.Entry e = entrée(c, m);
            if (e != null) uniques.putIfAbsent(e.label, e);
        }
        List<MiniJavaSignatures.Entry> out = new ArrayList<>(uniques.values());
        out.sort(java.util.Comparator
                .comparingInt((MiniJavaSignatures.Entry e) -> e.params.size())
                .thenComparing(e -> e.label));
        return out;
    }

    /**
     * Une méthode réfléchie en entrée de signature : le libellé porte
     * les types RÉELS et leurs GÉNÉRIQUES ({@code forEach(Consumer<? super E> arg0)}
     * — les noms de paquets raccourcis), les paramètres sont nommés par
     * la réflexion, et la documentation dit ce qui est VRAIMENT su —
     * la classe porteuse, les exceptions levées, le caractère statique.
     * Les génériques nourrissent l'inférence des indices lambda.
     */
    private static MiniJavaSignatures.Entry entrée(Class<?> c, Method m) {
        java.lang.reflect.Type[] types;
        try {
            types = m.getGenericParameterTypes();
        } catch (Throwable t) {
            return null;
        }
        List<String> params = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            boolean vararg = m.isVarArgs() && i == types.length - 1;
            String t = nomAffiché(types[i]);
            // Varargs : le tableau du dernier paramètre devient « ... ».
            if (vararg && t.endsWith("[]")) {
                t = t.substring(0, t.length() - 2);
            }
            params.add(t + (vararg ? "..." : "") + " arg" + i);
        }
        StringBuilder libellé = new StringBuilder(m.getName()).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) libellé.append(", ");
            libellé.append(params.get(i));
        }
        libellé.append(')');
        StringBuilder doc = new StringBuilder("Méthode ")
                .append(m.getName()).append(" de ").append(c.getName())
                .append(" — signature réelle du classpath (réflexion), "
                        + "paramètres nommés par la réflexion.");
        try {
            Class<?>[] levées = m.getExceptionTypes();
            List<String> noms = new ArrayList<>();
            for (Class<?> l : levées) {
                if (l != Exception.class && l != RuntimeException.class) {
                    noms.add(nomSimple(l));
                }
            }
            if (!noms.isEmpty()) {
                doc.append(" Lève ").append(String.join(", ", noms)).append('.');
            }
        } catch (Throwable ignoré) {
            // Les exceptions ne sont qu'un supplément du libellé.
        }
        try {
            if (Modifier.isStatic(m.getModifiers())) {
                doc.append(" Méthode statique.");
            }
        } catch (Throwable ignoré) {
            // Le caractère statique ne change pas la signature servie.
        }
        List<String> docsParams = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) docsParams.add("");
        return new MiniJavaSignatures.Entry(libellé.toString(), doc.toString(),
                params, docsParams);
    }

    // ════════════════════════════════════════════════════════════════
    // Fiche de synthèse d'une classe — le corps du survol
    // ════════════════════════════════════════════════════════════════

    /**
     * La synthèse d'une classe du classpath : genre et qualifiants
     * ({@code public final class java.lang.Integer extends Number}),
     * interfaces portées, constantes d'enum (les vingt premières),
     * champs publics notables. Liste VIDE si la réflexion refuse.
     */
    static List<String> fiche(Class<?> c) {
        if (c == null) return Collections.emptyList();
        try {
            List<String> lignes = new ArrayList<>();
            int mod = c.getModifiers();
            StringBuilder tête = new StringBuilder();
            tête.append(java.lang.reflect.Modifier.toString(mod)).append(' ');
            tête.append(estEnum(c) ? "enum" : c.isInterface() ? "interface"
                    : estRecord(c) ? "record" : "class");
            tête.append(' ').append(c.getName());
            Class<?> sup = c.getSuperclass();
            if (sup != null && sup != Object.class) {
                tête.append(" extends ").append(sup.getName());
            }
            lignes.add(tête.toString().trim());
            Class<?>[] ifaces = c.getInterfaces();
            if (ifaces.length > 0) {
                List<String> noms = new ArrayList<>();
                for (Class<?> i : ifaces) noms.add(i.getName());
                lignes.add("Interfaces : " + String.join(", ", noms) + '.');
            }
            if (c.isEnum()) {
                Object[] constantes = c.getEnumConstants();
                if (constantes != null && constantes.length > 0) {
                    List<String> noms = new ArrayList<>();
                    for (int i = 0; i < Math.min(20, constantes.length); i++) {
                        noms.add(String.valueOf(constantes[i]));
                    }
                    lignes.add("Constantes : " + String.join(", ", noms)
                            + (constantes.length > 20 ? "…" : "") + '.');
                }
            } else {
                List<String> champs = new ArrayList<>();
                for (java.lang.reflect.Field f : c.getFields()) {
                    if (f == null || f.isSynthetic()) continue;
                    if (!Modifier.isPublic(f.getModifiers())) continue;
                    champs.add(nomSimple(f.getType()) + " " + f.getName());
                    if (champs.size() >= 8) {
                        champs.add("…");
                        break;
                    }
                }
                if (!champs.isEmpty()) {
                    lignes.add("Champs publics : "
                            + String.join(", ", champs) + '.');
                }
            }
            return lignes;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** Le genre réel d'une classe (le mot-clé de sa déclaration). */
    private static boolean estEnum(Class<?> c) {
        try {
            return c.isEnum();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Record ? — API récente, indisponible sur les vieux runtimes :
     *  l'échec vaut « non », jamais une exception. Class.isRecord exige
     *  l'API 34 : avant, le repli structurel (superclasse
     *  java.lang.Record) décide — même réponse, sans NewApi. */
    private static boolean estRecord(Class<?> c) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                return c.isRecord();
            }
            Class<?> superc = c.getSuperclass();
            return superc != null
                    && "java.lang.Record".equals(superc.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Le nom simple d'un type réfléchi (tableaux en {@code []}). */
    private static String nomSimple(Class<?> t) {
        if (t == null) return "?";
        try {
            if (t.isArray()) {
                return nomSimple(t.getComponentType()) + "[]";
            }
            return t.getSimpleName();
        } catch (Throwable x) {
            return t.getName();
        }
    }

    /**
     * Le nom AFFICHABLE d'un type générique réfléchi : les paquets
     * raccourcis ({@code java.util.function.Consumer<? super E>} →
     * {@code Consumer<? super E>}) — la lisibilité d'une déclaration,
     * la vérité du runtime.
     */
    private static String nomAffiché(java.lang.reflect.Type t) {
        if (t == null) return "?";
        String nom;
        try {
            // Type.getTypeName exige l'API 28 : avant, toString() porte
            // la même information (nom canonique du type réfléchi).
            nom = android.os.Build.VERSION.SDK_INT >= 28
                    ? t.getTypeName() : t.toString();
        } catch (Throwable x) {
            return "?";
        }
        // Tout segment minuscule pointillé devant une majuscule est
        // un paquet : raccourci. « ? super E » et « [] » passent tels
        // quels (aucun point dedans).
        return nom.replaceAll("(?:\\b[a-z][\\w]*\\.)+([A-Z])", "$1");
    }

    /** Exclusion des tests : vider la mémoïsation. */
    static void oublierCache() {
        CACHE.clear();
    }
}

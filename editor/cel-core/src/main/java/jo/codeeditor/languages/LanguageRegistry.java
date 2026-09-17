package jo.codeeditor.languages;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registre central des langages (portage du
 * {@code EditorLanguageRegistry} de CodeAssist).
 *
 * <p>Centralise la distribution par chaînes qui vivait dans
 * {@code SyntaxHighlighter} (routage des tokenizers + recherche de
 * mots-clés) et {@code CommentSyntax.forLanguage} (syntaxe de
 * commentaire) : les deux résolvent désormais l'id de langage via ce
 * registre et lisent le {@link LanguageProfile} résultant.</p>
 *
 * <p><b>Contribuable.</b> Les hôtes enregistrent leurs propres langages —
 * y compris des langages inconnus de l'éditeur — et le highlighter
 * intégré + les bascules de commentaire les prennent en compte
 * immédiatement :</p>
 *
 * <pre>{@code
 * LanguageRegistry.register(LanguageProfile.builder("mylang")
 *     .family(SyntaxFamily.C_LIKE)
 *     .keywords("if", "else", "repeat", "until")
 *     .commentSyntax(new CommentSyntax("#", null, null))
 *     .build());
 * }</pre>
 *
 * <p><b>Observable.</b> Les listeners sont notifiés après chaque
 * {@link #register}/{@link #unregister} — un hôte peut invalider ses
 * sélecteurs de langage ou caches quand l'ensemble des langages
 * change.</p>
 *
 * <p><b>Sémantique de surcharge.</b> Enregistrer un profil dont le nom ou
 * un alias entre en collision avec un id existant remplace la
 * correspondance pour cet id. Un hôte peut ainsi affiner un langage
 * intégré (ex. donner à {@code "sql"} un ensemble de mots-clés plus
 * riche) sans toucher à la lib.</p>
 *
 * <p>Les ids et extensions sont normalisés (trim, minuscules via
 * {@code Locale.ROOT}) avant recherche. Une recherche ne renvoie jamais
 * de profil pour un id inconnu — les appelants gardent leur propre
 * secours (le highlighter retombe sur le tokenizer C-like avec
 * l'ensemble de mots-clés Java, {@code CommentSyntax} retombe sur la
 * paire C-style).</p>
 */
public final class LanguageRegistry {

    /** Notifié après l'enregistrement ou le retrait d'un profil. */
    public interface Listener {
        /**
         * @param profile le profil qui a été ajouté ou retiré
         * @param removed true pour un retrait, false pour un enregistrement
         */
        void onLanguagesChanged(LanguageProfile profile, boolean removed);
    }

    private static final Map<String, LanguageProfile> BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, LanguageProfile> BY_EXTENSION = new ConcurrentHashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean builtinsInstalled = false;

    private LanguageRegistry() {}

    private static void ensureBuiltins() {
        if (!builtinsInstalled) {
            synchronized (LanguageRegistry.class) {
                if (!builtinsInstalled) {
                    BuiltinLanguages.install();
                    builtinsInstalled = true;
                }
            }
        }
    }

    /** Insertion dans la table (visibilité package) utilisée par {@link BuiltinLanguages}. */
    static void put(LanguageProfile p) {
        BY_ID.put(p.name, p);
        for (String a : p.aliases) BY_ID.put(a, p);
        for (String e : p.extensions) BY_EXTENSION.put(e, p);
    }

    /**
     * Résout un id de langage (nom canonique ou alias, insensible à la
     * casse), ou null si inconnu.
     */
    public static LanguageProfile forName(String id) {
        ensureBuiltins();
        if (id == null) return null;
        return BY_ID.get(normalize(id));
    }

    /**
     * Résout une extension de fichier (avec ou sans le point initial,
     * insensible à la casse), ou null si inconnue.
     */
    public static LanguageProfile forExtension(String ext) {
        ensureBuiltins();
        if (ext == null) return null;
        String e = normalize(ext);
        if (e.isEmpty()) return null;
        if (e.charAt(0) == '.') e = e.substring(1);
        if (e.isEmpty()) return null;
        return BY_EXTENSION.get(e);
    }

    /**
     * Enregistre (ou remplace) un profil. Les alias et extensions du
     * profil deviennent immédiatement résolvables. Les listeners se
     * déclenchent après la mise à jour de la table.
     */
    public static LanguageProfile register(LanguageProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile is null");
        ensureBuiltins();
        put(profile);
        for (Listener l : LISTENERS) {
            try {
                l.onLanguagesChanged(profile, false);
            } catch (RuntimeException ignored) {
                // Un listener qui lève ne doit pas casser l'enregistrement
                // (même politique défensive que EditorPainterHost).
            }
        }
        return profile;
    }

    /**
     * Retire le profil enregistré sous le nom canonique {@code name}
     * (les alias et extensions de ce profil sont aussi retirés).
     * Renvoie false quand aucun profil n'est enregistré sous ce nom.
     */
    public static boolean unregister(String name) {
        ensureBuiltins();
        if (name == null) return false;
        LanguageProfile p = BY_ID.get(normalize(name));
        if (p == null) return false;
        BY_ID.values().removeIf(v -> v == p);
        BY_EXTENSION.values().removeIf(v -> v == p);
        for (Listener l : LISTENERS) {
            try {
                l.onLanguagesChanged(p, true);
            } catch (RuntimeException ignored) {
                // voir register()
            }
        }
        return true;
    }

    /** Les noms canoniques de tous les profils enregistrés (triés). */
    public static Set<String> registeredNames() {
        ensureBuiltins();
        Set<String> names = new TreeSet<>();
        for (LanguageProfile p : BY_ID.values()) names.add(p.name);
        return names;
    }

    /** Les objets profils canoniques enregistrés (un par langage). */
    public static Set<LanguageProfile> registeredProfiles() {
        ensureBuiltins();
        Set<LanguageProfile> out = new TreeSet<>((a, b) -> a.name.compareTo(b.name));
        for (LanguageProfile p : BY_ID.values()) out.add(p);
        return out;
    }

    public static void addListener(Listener l) {
        if (l != null) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    /**
     * Réservé aux tests : supprime tout enregistrement (y compris profils
     * personnalisés et surcharges) et réinstalle la table intégrée.
     */
    static void resetToBuiltins() {
        synchronized (LanguageRegistry.class) {
            BY_ID.clear();
            BY_EXTENSION.clear();
            builtinsInstalled = false;
            ensureBuiltins();
        }
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}

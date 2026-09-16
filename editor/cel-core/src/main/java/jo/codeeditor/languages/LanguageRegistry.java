package jo.codeeditor.languages;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v3.36.0 — Central registry of languages (roadmap item 7, port of
 * CodeAssist v3.20's {@code EditorLanguageRegistry}).
 *
 * <p>Replaces the per-class string dispatch that lived in
 * {@code SyntaxHighlighter} (tokenizer routing + keyword lookup) and
 * {@code CommentSyntax.forLanguage} (comment syntax): both now resolve
 * the language id through this registry and read the resulting
 * {@link LanguageProfile}.</p>
 *
 * <p><b>Contributable.</b> Hosts register their own languages — including
 * ones the editor has never heard of — and the built-in highlighter +
 * comment toggles pick them up immediately:</p>
 *
 * <pre>{@code
 * LanguageRegistry.register(LanguageProfile.builder("mylang")
 *     .family(SyntaxFamily.C_LIKE)
 *     .keywords("if", "else", "repeat", "until")
 *     .commentSyntax(new CommentSyntax("#", null, null))
 *     .build());
 * }</pre>
 *
 * <p><b>Observable.</b> Listeners are notified after every
 * {@link #register}/{@link #unregister} — a host can invalidate its
 * language pickers or caches when the set of languages changes.</p>
 *
 * <p><b>Override semantics.</b> Registering a profile whose name or alias
 * collides with an existing id replaces the mapping for that id. This lets
 * a host refine a built-in (e.g. give {@code "sql"} a richer keyword set)
 * without touching the library.</p>
 *
 * <p>Ids and extensions are normalized (trimmed, lowercased with
 * {@code Locale.ROOT}) before lookup. Lookups never return a profile for
 * an unknown id — callers keep their own fallback (the highlighter falls
 * back to the C-like tokenizer with the Java keyword set,
 * {@code CommentSyntax} falls back to the C-style pair).</p>
 *
 * @since v3.36.0
 */
public final class LanguageRegistry {

    /** Notified after a profile is registered or unregistered. */
    public interface Listener {
        /**
         * @param profile the profile that was added or removed
         * @param removed true for an unregister, false for a register
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

    /** Package-private table insert used by {@link BuiltinLanguages}. */
    static void put(LanguageProfile p) {
        BY_ID.put(p.name, p);
        for (String a : p.aliases) BY_ID.put(a, p);
        for (String e : p.extensions) BY_EXTENSION.put(e, p);
    }

    /**
     * Resolves a language id (canonical name or alias, case-insensitive),
     * or null when unknown.
     */
    public static LanguageProfile forName(String id) {
        ensureBuiltins();
        if (id == null) return null;
        return BY_ID.get(normalize(id));
    }

    /**
     * Resolves a file extension (with or without the leading dot,
     * case-insensitive), or null when unknown.
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
     * Registers (or replaces) a profile. Aliases and extensions of the
     * profile become resolvable immediately. Listeners fire after the
     * table update.
     */
    public static LanguageProfile register(LanguageProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile is null");
        ensureBuiltins();
        put(profile);
        for (Listener l : LISTENERS) {
            try {
                l.onLanguagesChanged(profile, false);
            } catch (RuntimeException ignored) {
                // A throwing listener must not break registration
                // (same defensive policy as EditorPainterHost).
            }
        }
        return profile;
    }

    /**
     * Unregisters the profile registered under the canonical name
     * {@code name} (aliases and extensions of that profile are removed
     * too). Returns false when no profile is registered under that name.
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
                // see register()
            }
        }
        return true;
    }

    /** The canonical names of every registered profile (sorted). */
    public static Set<String> registeredNames() {
        ensureBuiltins();
        Set<String> names = new TreeSet<>();
        for (LanguageProfile p : BY_ID.values()) names.add(p.name);
        return names;
    }

    /** The canonical profile objects registered (one per language). */
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
     * Test-only: drops every registration (including custom profiles and
     * overrides) and reinstalls the built-in table.
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

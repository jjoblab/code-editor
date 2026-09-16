package jo.codeeditor.languages;

import jo.codeeditor.edit.CommentSyntax;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * v3.36.0 — Immutable description of a language (roadmap item 7, port of
 * CodeAssist v3.20's {@code EditorLanguageProfile}).
 *
 * <p>A profile bundles everything the editor needs to know about a language
 * beyond the intelligence SPI ({@code jo.codeeditor.lang.Language}): the
 * ids it answers to (canonical name + aliases), the file extensions it
 * covers, its keyword set, its lexical {@link SyntaxFamily} (which selects
 * the built-in tokenizer) and its {@link CommentSyntax} (which drives
 * {@code toggleLineComment}/{@code toggleBlockComment}).</p>
 *
 * <p>Profiles are registered in the {@link LanguageRegistry}. Hosts can
 * register their own languages — including languages the editor has never
 * heard of — and the highlighter + comment toggles pick them up
 * automatically:</p>
 *
 * <pre>{@code
 * LanguageProfile custom = LanguageProfile.builder("mylang")
 *     .family(SyntaxFamily.C_LIKE)
 *     .alias("ml")
 *     .extension("ml")
 *     .keywords("if", "else", "repeat", "until")
 *     .commentSyntax(new CommentSyntax("#", null, null))
 *     .build();
 * LanguageRegistry.register(custom);
 * editorSession.setLanguage("mylang");
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @since v3.36.0
 */
public final class LanguageProfile {

    /** Canonical, lowercase id (e.g. {@code "python"}). */
    public final String name;
    /** Which built-in tokenizer handles this language. */
    public final SyntaxFamily family;
    /** Additional lowercase ids this profile answers to (e.g. {@code "py"}). */
    public final Set<String> aliases;
    /** Lowercase file extensions without the dot (e.g. {@code "py"}). */
    public final Set<String> extensions;
    /** Keyword set consumed by the C-like tokenizer (may be empty). */
    public final Set<String> keywords;
    /** Comment syntax used by the comment toggles (never null). */
    public final CommentSyntax commentSyntax;

    private LanguageProfile(String name, SyntaxFamily family,
            Set<String> aliases, Set<String> extensions,
            Set<String> keywords, CommentSyntax commentSyntax) {
        this.name = name;
        this.family = family;
        this.aliases = aliases;
        this.extensions = extensions;
        this.keywords = keywords;
        this.commentSyntax = commentSyntax;
    }

    /** Fluent builder — see the class javadoc for a usage example. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** True when {@code id} is the canonical name or one of the aliases. */
    public boolean answersTo(String id) {
        if (id == null) return false;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        return name.equals(norm) || aliases.contains(norm);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LanguageProfile)) return false;
        return name.equals(((LanguageProfile) o).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "LanguageProfile(" + name + ", family=" + family
                + ", aliases=" + aliases + ")";
    }

    /** Builder for {@link LanguageProfile}. */
    public static final class Builder {
        private final String name;
        private final Set<String> aliases = new HashSet<>();
        private final Set<String> extensions = new HashSet<>();
        private final Set<String> keywords = new HashSet<>();
        private SyntaxFamily family = SyntaxFamily.C_LIKE;
        private CommentSyntax commentSyntax = CommentSyntax.C_STYLE;

        private Builder(String name) {
            if (name == null) throw new IllegalArgumentException("name is null");
            String n = name.trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty()) throw new IllegalArgumentException("name is empty");
            this.name = n;
        }

        public Builder family(SyntaxFamily family) {
            this.family = family != null ? family : SyntaxFamily.C_LIKE;
            return this;
        }

        public Builder alias(String... ids) {
            aliases.addAll(normalize(ids));
            return this;
        }

        public Builder extension(String... exts) {
            for (String v : exts) {
                if (v == null) continue;
                String n = v.trim().toLowerCase(Locale.ROOT);
                if (n.startsWith(".")) n = n.substring(1); // tolerate dotted input
                if (!n.isEmpty()) extensions.add(n);
            }
            return this;
        }

        public Builder keywords(String... words) {
            keywords.addAll(Arrays.asList(words));
            return this;
        }

        public Builder keywordSet(Set<String> set) {
            if (set != null) keywords.addAll(set);
            return this;
        }

        public Builder commentSyntax(CommentSyntax syntax) {
            this.commentSyntax = syntax != null ? syntax : CommentSyntax.C_STYLE;
            return this;
        }

        public LanguageProfile build() {
            return new LanguageProfile(name, family,
                    Collections.unmodifiableSet(new HashSet<>(aliases)),
                    Collections.unmodifiableSet(new HashSet<>(extensions)),
                    Collections.unmodifiableSet(new HashSet<>(keywords)),
                    commentSyntax);
        }

        private static Set<String> normalize(String... values) {
            Set<String> out = new HashSet<>();
            if (values != null) {
                for (String v : values) {
                    if (v == null) continue;
                    String n = v.trim().toLowerCase(Locale.ROOT);
                    if (!n.isEmpty()) out.add(n);
                }
            }
            return out;
        }
    }
}

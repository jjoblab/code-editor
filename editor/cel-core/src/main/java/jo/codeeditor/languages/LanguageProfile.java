package jo.codeeditor.languages;

import jo.codeeditor.edit.CommentSyntax;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Description immuable d'un langage (portage du
 * {@code EditorLanguageProfile} de CodeAssist).
 *
 * <p>Un profil regroupe tout ce que l'éditeur doit savoir d'un langage
 * au-delà du SPI d'intelligence ({@code jo.codeeditor.lang.Language}) :
 * les ids auxquels il répond (nom canonique + alias), les extensions de
 * fichiers couvertes, son ensemble de mots-clés, sa {@link SyntaxFamily}
 * lexicale (qui sélectionne le tokenizer intégré) et sa
 * {@link CommentSyntax} (qui pilote {@code toggleLineComment}/
 * {@code toggleBlockComment}).</p>
 *
 * <p>Les profils sont enregistrés dans le {@link LanguageRegistry}. Les
 * hôtes peuvent enregistrer leurs propres langages — y compris des
 * langages inconnus de l'éditeur — et le highlighter + les bascules de
 * commentaire les prennent en compte automatiquement :</p>
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
 * <p>Les instances sont immuables et thread-safe.</p>
 */
public final class LanguageProfile {

    /** Id canonique en minuscules (ex. {@code "python"}). */
    public final String name;
    /** Tokenizer intégré qui gère ce langage. */
    public final SyntaxFamily family;
    /** Ids minuscules supplémentaires auxquels ce profil répond (ex. {@code "py"}). */
    public final Set<String> aliases;
    /** Extensions de fichiers en minuscules sans le point (ex. {@code "py"}). */
    public final Set<String> extensions;
    /** Ensemble de mots-clés consommé par le tokenizer C-like (peut être vide). */
    public final Set<String> keywords;
    /** Syntaxe de commentaire utilisée par les bascules de commentaire (jamais null). */
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

    /** Builder fluent — voir la javadoc de la classe pour un exemple d'utilisation. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** true quand {@code id} est le nom canonique ou un des alias. */
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

    /** Builder pour {@link LanguageProfile}. */
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
                if (n.startsWith(".")) n = n.substring(1); // tolère une entrée avec point
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

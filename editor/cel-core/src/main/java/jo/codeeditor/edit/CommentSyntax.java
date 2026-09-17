package jo.codeeditor.edit;

import java.util.Locale;

/**
 * Syntaxe de commentaire pilotée par le langage.
 *
 * <p>Profil immuable minimal, résolu depuis l'identifiant de langage de la
 * session. {@code EditorSession.toggleLineComment()} et
 * {@code EditorSession.toggleBlockComment()} s'appuient sur ce profil :
 * coder en dur {@code "//"} et la paire style C produirait des préfixes
 * erronés pour XML, Python, Markdown, Lua, SQL, shell… (p. ex.
 * {@code // def foo():} dans un fichier Python).</p>
 *
 * <p>Règles de résolution (alignées sur la table de langages de
 * {@code SyntaxHighlighter}, alias courts inclus comme {@code "py"},
 * {@code "js"}, {@code "ts"}, {@code "rs"}, {@code "rb"}, {@code "sh"}) :</p>
 * <ul>
 *   <li>langages de la famille C → {@code //} + {@code /* *&#47;} (défaut inchangé) ;</li>
 *   <li>langages à dièse (Python, Ruby, shell, TOML, properties, smali, YAML)
 *       → {@code #} et (généralement) pas de commentaire de bloc ;</li>
 *   <li>XML/HTML/Markdown → pas de commentaire de ligne, bloc
 *       {@code <!-- -->} — {@code toggleLineComment} retombe alors sur
 *       l'encadrement de chaque ligne par la paire de bloc (comportement
 *       VS Code pour XML) ;</li>
 *   <li>Lua → {@code --} + {@code --[[ ]]}; SQL → {@code --} +
 *       {@code /* *&#47;} ;</li>
 *   <li>JSON → aucune syntaxe de commentaire : les deux bascules sont des
 *       no-ops sûrs.</li>
 * </ul>
 *
 * <p>Les identifiants de langage inconnus ou {@code null} se résolvent vers
 * le défaut famille C, si bien que les appelants existants (Java d'abord)
 * conservent exactement le même comportement octet par octet.</p>
 *
 * <p>Les hôtes disposant de langages exotiques peuvent court-circuiter la
 * résolution par langage via
 * {@code EditorSession.setCommentSyntax(CommentSyntax)}.</p>
 */
public final class CommentSyntax {

    /** Défaut famille C : {@code //} + {@code /* *&#47;}. */
    public static final CommentSyntax C_STYLE =
            new CommentSyntax("//", "/*", "*/");

    /** JSON (et tout format sans syntaxe de commentaire) : toutes les bascules sont des no-ops. */
    public static final CommentSyntax NONE =
            new CommentSyntax(null, null, null);

    /** Préfixe de commentaire de ligne, ou null quand le langage n'en a pas. */
    public final String lineComment;
    /** Ouvreur de commentaire de bloc, ou null quand le langage n'en a pas. */
    public final String blockStart;
    /** Fermeur de commentaire de bloc, ou null quand le langage n'en a pas. */
    public final String blockEnd;

    public CommentSyntax(String lineComment, String blockStart, String blockEnd) {
        this.lineComment = lineComment;
        this.blockStart = blockStart;
        this.blockEnd = blockEnd;
    }

    /** Vrai quand {@link #toggleLineComment} peut insérer quelque chose. */
    public boolean hasLine() {
        return lineComment != null;
    }

    /** Vrai quand une paire de commentaire de bloc existe. */
    public boolean hasBlock() {
        return blockStart != null && blockEnd != null;
    }

    /**
     * Résout la syntaxe de commentaire pour l'identifiant de langage donné.
     *
     * <p>La résolution passe par le registre : l'identifiant est normalisé
     * (trimmé, passé en minuscules via {@code Locale.ROOT}) puis cherché
     * dans {@link jo.codeeditor.languages.LanguageRegistry}. Un langage
     * personnalisé enregistré par l'hôte avec un
     * {@code commentSyntax(...)} est honoré ici automatiquement. Les
     * identifiants inconnus retombent sur {@link #C_STYLE} afin de préserver
     * le comportement Java-first de cet éditeur.</p>
     */
    public static CommentSyntax forLanguage(String language) {
        if (language == null) return C_STYLE;
        String lang = language.trim().toLowerCase(Locale.ROOT);
        if (lang.isEmpty()) return C_STYLE;
        jo.codeeditor.languages.LanguageProfile profile =
                jo.codeeditor.languages.LanguageRegistry.forName(lang);
        return profile != null ? profile.commentSyntax : C_STYLE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommentSyntax)) return false;
        CommentSyntax that = (CommentSyntax) o;
        return eq(lineComment, that.lineComment)
                && eq(blockStart, that.blockStart)
                && eq(blockEnd, that.blockEnd);
    }

    @Override
    public int hashCode() {
        int h = 7;
        h = 31 * h + (lineComment == null ? 0 : lineComment.hashCode());
        h = 31 * h + (blockStart == null ? 0 : blockStart.hashCode());
        h = 31 * h + (blockEnd == null ? 0 : blockEnd.hashCode());
        return h;
    }

    @Override
    public String toString() {
        return "CommentSyntax(line=" + lineComment
                + ", block=" + blockStart + "…" + blockEnd + ")";
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}

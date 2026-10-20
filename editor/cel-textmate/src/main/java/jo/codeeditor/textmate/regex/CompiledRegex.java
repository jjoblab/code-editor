package jo.codeeditor.textmate.regex;

import org.joni.Option;
import org.joni.Regex;
import org.joni.WarnCallback;
import org.jcodings.specific.UTF8Encoding;

import java.nio.charset.StandardCharsets;

/**
 * Motif compilé du dialecte (plan 10 §3.2) — type cel-textmate exclusivement.
 *
 * <p>La compilation passe par {@link DialectNormalizer}, puis enveloppe le
 * motif d'une ancre de position : {@code \G(?: … )}. Le groupe
 * d'enveloppe est NON capturant — la numérotation des groupes du motif
 * d'origine est inchangée ; l'ancre lie à la position d'essai, ce qui
 * rend {@link LineMatcher#matchAt(int)} (une recherche depuis cette
 * position) exactement équivalent à un essai ancré, tout en héritant du
 * budget par essai du moteur ({@link MatchBudget}) — la recherche est la
 * seule voie qui arme réellement le chronomètre du budget.</p>
 *
 * <p>Échec de compilation → {@link RegexSyntaxException} : l'appelant
 * décide (règle muette au parse, fermante NEVER au tokéniseur) — jamais
 * d'échec en coloration.</p>
 */
public final class CompiledRegex {

    /** Source normalisée (clé de cache et d'égalité des cadres). */
    private final String source;
    private final Regex joni;

    private CompiledRegex(String source, Regex joni) {
        this.source = source;
        this.joni = joni;
    }

    /**
     * Compile un motif du dialecte. Le motif est normalisé puis
     * enveloppé ; la compilation est faite en UTF-8 avec groupes de
     * capture. Lève {@link RegexSyntaxException} sur motif invalide.
     *
     * <p>L'enveloppe ajoute une seconde tentative avec un saut de ligne
     * avant la parenthèse fermante : un motif en mode étendu {@code (?x)}
     * peut se terminer par une LIGNE DE COMMENTAIRE — la fermante
     * appliquée telle quelle serait avalée par le commentaire (« parenthèse
     * non fermée ») ; le saut de ligne clôt la ligne de commentaire et la
     * fermante redevient visible. Un motif réellement déséquilibré échoue
     * aux DEUX tentatives — l'erreur de la première est reportée.</p>
     */
    public static CompiledRegex compile(String rawSource) {
        String source = DialectNormalizer.normalize(rawSource);
        RuntimeException first = null;
        for (String wrapped : new String[] {"\\G(?:" + source + ")", "\\G(?:" + source + "\n)"}) {
            byte[] bytes = wrapped.getBytes(StandardCharsets.UTF_8);
            try {
                Regex joni = new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                        UTF8Encoding.INSTANCE, WarnCallback.NONE);
                return new CompiledRegex(source, joni);
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        // JOniException et consœurs : le message du moteur est porté
        // tel quel (diagnostic fidèle, jamais une reformulation).
        throw new RegexSyntaxException(rawSource, first.getMessage(), first);
    }

    /** La source APRÈS normalisation — clé de cache, égalité des cadres. */
    public String source() {
        return source;
    }

    /** Matcher lié à une ligne encodée (un par motif × ligne). */
    public LineMatcher matcher(EncodedLine line) {
        return new LineMatcher(this, line,
                joni.matcher(line.bytes(), 0, line.bytes().length,
                        MatchBudget.MATCH_TIMEOUT_NS));
    }

    @Override
    public String toString() {
        return "CompiledRegex(" + source + ")";
    }

    /** Motif invalide au sens du dialecte (port du message du moteur). */
    public static final class RegexSyntaxException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RegexSyntaxException(String pattern, String message, Throwable cause) {
            super("motif invalide « " + pattern + " » : " + message, cause);
        }
    }
}

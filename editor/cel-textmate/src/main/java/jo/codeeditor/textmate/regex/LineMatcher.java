package jo.codeeditor.textmate.regex;

import org.joni.Matcher;
import org.joni.Option;
import org.joni.Region;
import org.joni.exception.JOniException;

import java.nio.charset.StandardCharsets;

/**
 * Correspondance ancrée à une position, sur une ligne encodée
 * (plan 10 §3.2) — équivalent sémantique du « matcher frais lancé à
 * pos sur la ligne + "\n" virtuel » de l'ancien moteur.
 *
 * <p>Chaque {@link #matchAt(int)} est un essai BUDGÉTÉ
 * ({@link MatchBudget}) : l'interruption du budget et les exceptions du
 * moteur se replient en NON-correspondance (jamais d'échec en
 * coloration). Après un essai réussi, les accèsurs rendent les
 * positions en CARACTÈRES de la ligne ; les groupes non participants
 * valent {@code null}.</p>
 */
public final class LineMatcher {

    private final CompiledRegex regex;
    private final EncodedLine line;
    private final org.joni.Matcher joni;
    /** Région du dernier essai réussi — nulle avant. */
    private Region lastRegion;
    /** Le DERNIER essai a-t-il été interrompu par le budget ? */
    private boolean interrupted;

    LineMatcher(CompiledRegex regex, EncodedLine line, org.joni.Matcher joni) {
        this.regex = regex;
        this.line = line;
        this.joni = joni;
    }

    /**
     * Essai ancré à la position char {@code charPos} (0-based, sur la
     * ligne + « \n » virtuel). Vrai si le motif correspond à cette
     * position exacte ; les accèsurs décrivent alors la correspondance.
     */
    public boolean matchAt(int charPos) {
        lastRegion = null;
        interrupted = false;
        int at = line.byteOffset(charPos);
        int res;
        try {
            res = joni.search(at, line.bytes().length, Option.NONE);
        } catch (JOniException | ArrayIndexOutOfBoundsException e) {
            MatchBudget.logMatchFailure(regex.source(), e);
            return false;
        }
        if (res == Matcher.FAILED) return false;
        if (res == Matcher.INTERRUPTED) {
            interrupted = true;
            MatchBudget.logInterrupted(regex.source(), line.line());
            return false;
        }
        lastRegion = joni.getEagerRegion();
        return true;
    }

    /**
     * Le dernier essai a-t-il été interrompu par le budget ? (Consulté
     * par le tokéniseur pour sa garde anti-interruptions — plan 10 D4 :
     * un motif adversarial ne consomme pas « positions × budget ».)
     */
    public boolean wasInterrupted() {
        return interrupted;
    }

    /** Début du dernier essai réussi (caractères). */
    public int start() {
        requireRegion();
        return line.charIndex(lastRegion.getBeg(0));
    }

    /** Fin du dernier essai réussi (caractères). */
    public int end() {
        requireRegion();
        return line.charIndex(lastRegion.getEnd(0));
    }

    /** Nombre de groupes de capture du motif. */
    public int groupCount() {
        // La région compte le groupe 0 (match entier) — groupCount rend
        // la convention « groupes capturants » comme l'ancien moteur.
        return joni.getEagerRegion().getNumRegs() - 1;
    }

    /** Début du groupe g (caractères) ; -1 si non participant. */
    public int start(int group) {
        requireRegion();
        checkGroup(group);
        return line.charIndex(lastRegion.getBeg(group));
    }

    /** Fin du groupe g (caractères) ; -1 si non participant. */
    public int end(int group) {
        requireRegion();
        checkGroup(group);
        return line.charIndex(lastRegion.getEnd(group));
    }

    /**
     * Texte du groupe g, ou {@code null} si non participant — utilisé
     * par la substitution des backrefs des fermantes résolues.
     */
    public String group(int group) {
        requireRegion();
        checkGroup(group);
        int beg = lastRegion.getBeg(group);
        int end = lastRegion.getEnd(group);
        if (beg < 0 || end < 0 || end < beg) return null;
        return new String(line.bytes(), beg, end - beg, StandardCharsets.UTF_8);
    }

    private void requireRegion() {
        if (lastRegion == null) {
            throw new IllegalStateException("aucun essai réussi — matchAt d'abord");
        }
    }

    private void checkGroup(int group) {
        if (group < 0 || group >= lastRegion.getNumRegs()) {
            throw new IndexOutOfBoundsException("groupe " + group
                    + " hors [0, " + lastRegion.getNumRegs() + ")");
        }
    }
}

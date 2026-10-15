package jo.codeeditor.lang.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import jo.codeeditor.lang.provider.DiagnosticsProvider;

/**
 * Un diagnostic (erreur, avertissement ou info). Retourné par
 * {@link DiagnosticsProvider}.
 */
public final class Diagnostic {

    /** Offset de début (inclus). */
    public final int start;
    /** Offset de fin (exclu). */
    public final int end;
    /** Sévérité : 1=info, 2=avertissement, 3=erreur. */
    public final int severity;
    /** Le message du diagnostic. */
    @NonNull public final String message;
    /** Le code du diagnostic (ex. « E001 »). Peut être vide. */
    @Nullable public final String code;
    /** D4 (lot 2, J5) — la source du diagnostic (ex. « java », « ecj »
     * — champ {@code source} de LSP). Peut être vide.
     * @since 3.56.0 */
    @Nullable public final String source;
    /** Tag LSP {@code Unnecessary} (code inutilisé — ex. import mort) :
     * la plage est peinte estompée (grisé à la Android Studio) au lieu
     * du soulignement de sévérité. @since 3.51.0 */
    public final boolean unnecessary;

    public Diagnostic(int start, int end, int severity, @NonNull String message, @Nullable String code) {
        this(start, end, severity, message, code, "", false);
    }

    public Diagnostic(int start, int end, int severity, @NonNull String message, @Nullable String code,
            boolean unnecessary) {
        this(start, end, severity, message, code, "", unnecessary);
    }

    /** Variante complète (D4) : la source est transportée jusqu'au
     *  client (en-tête enrichi des popups de diagnostic). */
    public Diagnostic(int start, int end, int severity, @NonNull String message, @Nullable String code,
            @Nullable String source, boolean unnecessary) {
        this.start = start;
        this.end = end;
        this.severity = severity;
        this.message = message != null ? message : "";
        this.code = code != null ? code : "";
        this.source = source != null ? source : "";
        this.unnecessary = unnecessary;
    }
}

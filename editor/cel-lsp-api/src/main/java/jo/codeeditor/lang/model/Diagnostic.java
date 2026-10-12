package jo.codeeditor.lang.model;

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
    public final String message;
    /** Le code du diagnostic (ex. « E001 »). Peut être vide. */
    public final String code;
    /** Tag LSP {@code Unnecessary} (code inutilisé — ex. import mort) :
     * la plage est peinte estompée (grisé à la Android Studio) au lieu
     * du soulignement de sévérité. @since 3.51.0 */
    public final boolean unnecessary;

    public Diagnostic(int start, int end, int severity, String message, String code) {
        this(start, end, severity, message, code, false);
    }

    public Diagnostic(int start, int end, int severity, String message, String code,
            boolean unnecessary) {
        this.start = start;
        this.end = end;
        this.severity = severity;
        this.message = message != null ? message : "";
        this.code = code != null ? code : "";
        this.unnecessary = unnecessary;
    }
}

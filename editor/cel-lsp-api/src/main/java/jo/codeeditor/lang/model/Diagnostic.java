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

    public Diagnostic(int start, int end, int severity, String message, String code) {
        this.start = start;
        this.end = end;
        this.severity = severity;
        this.message = message != null ? message : "";
        this.code = code != null ? code : "";
    }
}

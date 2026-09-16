package jo.codeeditor.lang;

/**
 * A diagnostic (error, warning, or info). Returned by {@link DiagnosticsProvider}.
 *
 * @since v2.0.0
 */
public final class Diagnostic {

    /** Start offset (inclusive). */
    public final int start;
    /** End offset (exclusive). */
    public final int end;
    /** Severity: 1=info, 2=warning, 3=error. */
    public final int severity;
    /** The diagnostic message. */
    public final String message;
    /** The diagnostic code (e.g. "E001"). May be empty. */
    public final String code;

    public Diagnostic(int start, int end, int severity, String message, String code) {
        this.start = start;
        this.end = end;
        this.severity = severity;
        this.message = message != null ? message : "";
        this.code = code != null ? code : "";
    }
}

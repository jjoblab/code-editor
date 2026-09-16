package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides diagnostics (errors, warnings, infos) for the document.
 *
 * @since v2.0.0
 */
public interface DiagnosticsProvider {

    /**
     * Computes diagnostics for the given document text. Called on a worker
     * thread after each edit (debounced).
     *
     * @param text the full document text
     * @return the list of diagnostics (may be empty)
     */
    List<Diagnostic> computeDiagnostics(CharSequence text);
}

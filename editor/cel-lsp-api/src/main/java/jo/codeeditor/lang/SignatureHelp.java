package jo.codeeditor.lang;

import java.util.Collections;
import java.util.List;

/**
 * Signature help response. Returned by {@link SignatureHelpProvider}.
 *
 * @since v2.0.0
 */
public final class SignatureHelp {

    /** The available signatures for the call. */
    public final List<Signature> signatures;
    /** The index of the active signature in {@link #signatures}. */
    public final int activeSignature;
    /** The index of the active parameter (0-based). */
    public final int activeParameter;

    public SignatureHelp(List<Signature> signatures, int activeSignature, int activeParameter) {
        this.signatures = signatures != null ? Collections.unmodifiableList(signatures) : Collections.emptyList();
        this.activeSignature = activeSignature;
        this.activeParameter = activeParameter;
    }

    /** Returns the active signature, or {@code null} if the list is empty. */
    public Signature getActiveSignature() {
        if (signatures.isEmpty()) return null;
        int idx = Math.min(activeSignature, signatures.size() - 1);
        return signatures.get(idx);
    }
}

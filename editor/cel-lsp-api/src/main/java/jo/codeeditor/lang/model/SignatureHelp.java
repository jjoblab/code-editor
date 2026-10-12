package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.SignatureHelpProvider;

import java.util.Collections;
import java.util.List;

/**
 * Réponse d'aide de signature. Retournée par
 * {@link SignatureHelpProvider}.
 */
public final class SignatureHelp {

    /** Les signatures disponibles pour l'appel. */
    public final List<Signature> signatures;
    /** L'index de la signature active dans {@link #signatures}. */
    public final int activeSignature;
    /** L'index du paramètre actif (0-based). */
    public final int activeParameter;

    public SignatureHelp(List<Signature> signatures, int activeSignature, int activeParameter) {
        this.signatures = signatures != null ? Collections.unmodifiableList(signatures) : Collections.emptyList();
        this.activeSignature = activeSignature;
        this.activeParameter = activeParameter;
    }

    /** Retourne la signature active, ou {@code null} si la liste est vide. */
    public Signature getActiveSignature() {
        if (signatures.isEmpty()) return null;
        int idx = Math.min(activeSignature, signatures.size() - 1);
        return signatures.get(idx);
    }
}

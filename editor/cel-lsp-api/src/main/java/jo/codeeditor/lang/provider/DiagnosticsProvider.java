package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.Diagnostic;

import java.util.List;

/**
 * Fournit les diagnostics du document (erreurs, avertissements, infos).
 *
 * <p>Contrat : {@link #computeDiagnostics} est appelée avec un debounce
 * après chaque édition, soit sur le thread UI pour l'onglet focalisé, soit
 * sur un thread de balayage dédié pour les autres onglets ouverts.
 * L'implémentation doit donc être thread-safe, répondre rapidement (le pont
 * LSP se contente de renvoyer un cache alimenté par les
 * {@code publishDiagnostics} du serveur) et ne jamais retourner
 * {@code null} — une liste vide signifie l'absence de diagnostics.</p>
 */
public interface DiagnosticsProvider {

    /**
     * Calcule les diagnostics pour le texte du document.
     *
     * @param text le texte complet du document (instantané)
     * @return la liste des diagnostics (jamais {@code null}, peut être vide)
     */
    List<Diagnostic> computeDiagnostics(CharSequence text);
}

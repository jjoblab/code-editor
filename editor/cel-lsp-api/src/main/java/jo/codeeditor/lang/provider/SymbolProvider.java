package jo.codeeditor.lang.provider;

import jo.codeeditor.lang.model.Symbol;

import java.util.List;

/**
 * Fournit les symboles du document pour le go-to-symbol (vue plan).
 *
 * <p>Contrat : {@link #symbols} peut être appelée soit depuis un thread de
 * travail (popup go-to-symbol), soit depuis le thread UI (fil d'Ariane) —
 * l'implémentation doit donc rester rapide et thread-safe.</p>
 */
public interface SymbolProvider {

    /**
     * Retourne tous les symboles du document.
     *
     * @param text le texte complet du document (instantané)
     * @return la liste des symboles (peut être vide)
     */
    List<Symbol> symbols(CharSequence text);
}

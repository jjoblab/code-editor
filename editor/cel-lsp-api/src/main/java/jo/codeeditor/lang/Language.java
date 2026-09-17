package jo.codeeditor.lang;


import jo.codeeditor.lang.provider.CodeActionsProvider;
import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.lang.provider.DefinitionProvider;
import jo.codeeditor.lang.provider.DiagnosticsProvider;
import jo.codeeditor.lang.provider.DocumentHighlightProvider;
import jo.codeeditor.lang.provider.Formatter;
import jo.codeeditor.lang.provider.HoverProvider;
import jo.codeeditor.lang.provider.ImplementationsProvider;
import jo.codeeditor.lang.provider.InlayHintProvider;
import jo.codeeditor.lang.provider.ReferencesProvider;
import jo.codeeditor.lang.provider.RenameProvider;
import jo.codeeditor.lang.provider.SignatureHelpProvider;
import jo.codeeditor.lang.provider.SuperDefinitionProvider;
import jo.codeeditor.lang.provider.SymbolProvider;
import jo.codeeditor.lang.provider.TypeDefinitionProvider;
import jo.codeeditor.lang.provider.ViewZoneProvider;

/**
 * Point d'entrée du SPI de langage (Service Provider Interface). Une instance
 * de {@code Language} est branchée sur un {@link jo.codeeditor.view.EditorView}
 * via {@code setLanguage(Language)} et fournit à l'éditeur l'ensemble des
 * fonctionnalités d'intelligence de langage : coloration syntaxique,
 * complétion, hover, aide de signature, diagnostics, actions de code, etc.
 *
 * <p>Contrat :</p>
 * <ul>
 *   <li>les accesseurs de providers sont interrogés sur le <b>thread UI</b>
 *       (au branchement via {@code setLanguage}) — ils ne doivent donc rien
 *       y faire de bloquant ;</li>
 *   <li>chaque accesseur optionnel retourne {@code null} par défaut : un
 *       plugin de langage n'implémente que les fonctionnalités qu'il
 *       supporte, et l'éditeur vérifie la nullité avant de brancher l'UI
 *       correspondante (la fonctionnalité reste sinon désactivée) ;</li>
 *   <li>{@link #getAnalyzer()} est le seul provider obligatoire ;</li>
 *   <li>{@link #destroy()} est appelé sur le thread UI lorsque le langage
 *       est détaché de l'éditeur.</li>
 * </ul>
 */
public interface Language {

    /** Interruption forte : {@code Thread.interrupt()} + exception. */
    int INTERRUPTION_LEVEL_STRONG = 0;
    /** Interruption légère : exception seule. */
    int INTERRUPTION_LEVEL_SLIGHT = 1;
    /** Aucune interruption : le travail en vol n'est jamais annulé. */
    int INTERRUPTION_LEVEL_NONE = 2;

    /**
     * Retourne l'{@link Analyzer} qui fournit la coloration syntaxique
     * incrémentale, les blocs de code (folding) et l'appariement des
     * crochets. Ne doit jamais retourner {@code null} — un langage sans
     * analyzer est inutilisable.
     */
    Analyzer getAnalyzer();

    /**
     * Retourne le {@link CompletionProvider}, ou {@code null} si ce langage
     * ne supporte pas la complétion automatique.
     */
    default CompletionProvider getCompletionProvider() { return null; }

    /**
     * Retourne le {@link HoverProvider}, ou {@code null} si le
     * hover/quick-doc n'est pas supporté.
     */
    default HoverProvider getHoverProvider() { return null; }

    /**
     * Retourne le {@link SignatureHelpProvider}, ou {@code null} si l'aide
     * de signature n'est pas supportée.
     */
    default SignatureHelpProvider getSignatureHelpProvider() { return null; }

    /**
     * Retourne le {@link DefinitionProvider}, ou {@code null} si le
     * go-to-definition n'est pas supporté.
     */
    default DefinitionProvider getDefinitionProvider() { return null; }

    /**
     * Retourne le {@link TypeDefinitionProvider}, ou {@code null} si le
     * go-to-type-declaration (le type du symbole sous le caret) n'est pas
     * supporté.
     */
    default TypeDefinitionProvider getTypeDefinitionProvider() { return null; }

    /**
     * Retourne l'{@link ImplementationsProvider}, ou {@code null} si le
     * go-to-implementations (les héritiers directs du type sous le caret)
     * n'est pas supporté.
     */
    default ImplementationsProvider getImplementationsProvider() { return null; }

    /**
     * Retourne le {@link SuperDefinitionProvider}, ou {@code null} si le
     * go-to-super (le membre redéfini / les supertypes du type en contexte)
     * n'est pas supporté.
     */
    default SuperDefinitionProvider getSuperDefinitionProvider() { return null; }

    /**
     * Retourne le {@link ReferencesProvider}, ou {@code null} si la
     * recherche de références (find-references) n'est pas supportée.
     */
    default ReferencesProvider getReferencesProvider() { return null; }

    /**
     * Retourne le {@link DiagnosticsProvider}, ou {@code null} si les
     * diagnostics (erreurs/avertissements) ne sont pas supportés.
     */
    default DiagnosticsProvider getDiagnosticsProvider() { return null; }

    /**
     * Retourne le {@link CodeActionsProvider}, ou {@code null} si les
     * actions de code (quick-fixes, refactorings) ne sont pas supportées.
     */
    default CodeActionsProvider getCodeActionsProvider() { return null; }

    /**
     * Retourne le {@link DocumentHighlightProvider}, ou {@code null} si le
     * surlignage d'occurrences (symbole sous le caret) n'est pas supporté.
     */
    default DocumentHighlightProvider getDocumentHighlightProvider() { return null; }

    /**
     * Retourne l'{@link InlayHintProvider}, ou {@code null} si les inlay
     * hints (annotations de type fantômes) ne sont pas supportés.
     */
    default InlayHintProvider getInlayHintProvider() { return null; }

    /**
     * Retourne le {@link ViewZoneProvider}, ou {@code null} si les zones de
     * vue (espaces UI insérés entre les lignes) ne sont pas supportées.
     */
    default ViewZoneProvider getViewZoneProvider() { return null; }

    /**
     * Retourne le {@link Formatter}, ou {@code null} si le formatage de
     * code n'est pas supporté.
     */
    default Formatter getFormatter() { return null; }

    /**
     * Retourne le {@link SymbolProvider}, ou {@code null} si le
     * go-to-symbol (plan du document) n'est pas supporté.
     */
    default SymbolProvider getSymbolProvider() { return null; }

    /**
     * Retourne le {@link RenameProvider}, ou {@code null} si le renommage
     * n'est pas supporté.
     */
    default RenameProvider getRenameProvider() { return null; }

    /**
     * Indique avec quelle agressivité l'éditeur peut annuler les requêtes
     * de complétion en vol. Voir les constantes
     * {@link #INTERRUPTION_LEVEL_STRONG} et suivantes.
     */
    int getInterruptionLevel();

    /**
     * Appelé lorsque le langage est détaché de l'éditeur (par exemple quand
     * {@code setLanguage} est appelé avec un autre langage). Libère toutes
     * les ressources (parseurs, threads, connexions).
     */
    void destroy();
}

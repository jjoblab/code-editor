package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.CodeActionsProvider;

/**
 * Une action de code (quick-fix, refactoring). Retournée par
 * {@link CodeActionsProvider}.
 *
 * <p>Le runnable {@code apply} est exécuté sur le thread UI quand
 * l'utilisateur accepte l'action : il applique la correction au document.</p>
 */
public final class CodeAction {

    /** Le titre de l'action (ex. « Add missing import »). */
    public final String title;
    /** Le type d'action : « quickfix », « refactor », « source ». */
    public final String kind;
    /** Indique si c'est l'action préférée (affichée en premier). */
    public final boolean isPreferred;
    /** Le runnable qui applique l'action. Exécuté sur le thread UI. */
    public final Runnable apply;

    public CodeAction(String title, String kind, boolean isPreferred, Runnable apply) {
        this.title = title != null ? title : "";
        this.kind = kind != null ? kind : "quickfix";
        this.isPreferred = isPreferred;
        this.apply = apply;
    }

    /** Constructeur de commodité pour un quickfix non préféré. */
    public CodeAction(String title, Runnable apply) {
        this(title, "quickfix", false, apply);
    }
}

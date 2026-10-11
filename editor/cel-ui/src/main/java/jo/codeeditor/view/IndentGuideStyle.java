package jo.codeeditor.view;

/**
 * Lot B.11 (audit v3.41.0) — style d'affichage des guides
 * d'indentation. Piloté par
 * {@link EditorView#setIndentGuideStyle(IndentGuideStyle)} et
 * lu par {@link EditorView#getIndentGuideStyle()}.
 *
 * <p>Référence visuelle : les IDE Android et les éditeurs de code de bureau.</p>
 *
 * <ul>
 *   <li>{@link #OFF} — aucun guide. Les guides d'indentation
 *       sont complètement désactivés.</li>
 *   <li>{@link #INDENT} — guides d'indentation seuls. Tous les
 *       niveaux sont tracés avec la couleur de base
 *       ({@code theme.indentGuide}). Aucune mise en évidence de
 *       la portée active.</li>
 *   <li>{@link #INDENT_WITH_SCOPE} — guides d'indentation +
 *       mise en évidence de la portée active. Le guide du bloc
 *       qui contient le caret est dessiné avec la couleur
 *       d'accent ({@code theme.caret}) et une épaisseur
 *       légèrement supérieure. C'est le style par défaut
 *       (cohérent avec les IDE Android et les éditeurs de code de bureau).</li>
 * </ul>
 *
 * <p><b>Compatibilité</b> : la valeur par défaut
 * ({@link #INDENT_WITH_SCOPE}) préserve le comportement
 * historique de la v3.41.0 (Lot B). Les hôtes qui préfèrent
 * l'ancien comportement sans mise en évidence peuvent passer
 * à {@link #INDENT} ou {@link #OFF}.</p>
 *
 * @see EditorView#setIndentGuideStyle(IndentGuideStyle)
 * @see EditorView#getIndentGuideStyle()
 */
public enum IndentGuideStyle {
    /** Aucun guide d'indentation. */
    OFF,
    /** Guides d'indentation seuls, sans mise en évidence de la portée active. */
    INDENT,
    /** Guides d'indentation + portée active mise en évidence (défaut). */
    INDENT_WITH_SCOPE;
}

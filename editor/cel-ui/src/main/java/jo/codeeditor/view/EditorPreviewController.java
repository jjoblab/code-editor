package jo.codeeditor.view;

/**
 * Contrôleur du mode aperçu de l'éditeur : pilotage des modes NONE /
 * SPLIT / FULL / SHEET_* ({@link EditorView.PreviewMode}), hôte d'aperçu
 * ({@link EditorPreviewHost}), feuille popup d'overlay
 * ({@link EditorPreviewSheet}), nom de fichier courant et hit-test des
 * icônes d'aperçu.
 *
 * <p>Extrait d'EditorView : les champs {@code previewMode} et
 * {@code previewable} restent sur la vue (lus par les painters), tout le
 * reste de l'état d'aperçu, la géométrie du panneau
 * ({@link #previewLeft()}, {@link #previewWidth()},
 * {@link #effectiveTextWidth()}) et les corps des méthodes publiques
 * ({@code setPreviewMode}, {@code openPreview}, {@code closePreviewSheet},
 * …) vit ici ; la vue ne conserve que des relais.</p>
 */
class EditorPreviewController {

    private final EditorView view;

    // Icônes d'aperçu dessinées au Canvas (coin haut-droit).
    // Quand previewable = true, deux petites icônes sont dessinées :
    // - icône d'aperçu split (à gauche de la paire)
    // - icône d'aperçu full (à droite de la paire)
    // Taper une icône bascule le mode aperçu.
    static final float PREVIEW_ICON_SIZE_DP = 22f;
    static final float PREVIEW_ICON_MARGIN_DP = 8f;

    private String currentFileName = "";

    // Hôte d'aperçu découplé. L'app hôte fournit la fonctionnalité
    // d'aperçu (inflation XML, rendu Markdown, etc.) via cette interface.
    // La bibliothèque éditeur ne dépend plus des modules d'aperçu.
    private EditorPreviewHost previewHost;
    /** Feuille popup d'overlay active quand previewMode est un mode SHEET_*. */
    EditorPreviewSheet previewSheet;
    private EditorView.OnPreviewModeChangedListener previewModeListener;
    private final Runnable previewUpdateTask;

    EditorPreviewController(EditorView view) {
        this.view = view;
        this.previewUpdateTask = () -> {
            updateContent();
            view.invalidate();
        };
    }

    /**
     * Enregistre le hôte d'aperçu qui fournit le rendu d'aperçu.
     *
     * <p>Sans hôte, l'éditeur ne peut prévisualiser aucun fichier. Le hôte
     * est typiquement défini une fois dans le onCreate() de l'Activity
     * hôte :
     * <pre>{@code
     * editorView.setPreviewHost(new XmlPreviewHost(this));
     * }</pre>
     *
     * @param host le hôte d'aperçu, ou null pour désactiver l'aperçu
     */
    void setHost(EditorPreviewHost host) {
        this.previewHost = host;
    }

    EditorPreviewHost getHost() {
        return previewHost;
    }

    /**
     * Définit le nom de fichier courant pour que l'éditeur puisse détecter
     * si l'aperçu est disponible (.md, .markdown, .html, .htm, layout .xml).
     * Quand prévisualisable, l'éditeur dessine les icônes d'aperçu dans le
     * coin haut-droit.
     */
    void setFileName(String fileName) {
        this.currentFileName = fileName != null ? fileName : "";
        // Délègue la vérification de prévisualisabilité au hôte.
        view.previewable = (previewHost != null && previewHost.canPreview(this.currentFileName));
        view.invalidate();
    }

    /**
     * Retourne le dernier nom de fichier défini via
     * {@link EditorView#setFileName(String)}, ou la chaîne vide si aucun
     * n'a été défini. Utilisé par la feuille d'aperçu pour afficher le nom
     * de fichier dans son en-tête.
     */
    String getFileName() {
        return currentFileName;
    }

    private static boolean isPreviewableFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md")
            || lower.endsWith(".markdown")
            || lower.endsWith(".html")
            || lower.endsWith(".htm")
            || lower.endsWith(".xml");
    }

    private static boolean isXmlLayoutFile(String fileName) {
        if (fileName == null) return false;
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xml");
    }

    /**
     * Hit-teste les icônes d'aperçu. Retourne :
     * 0 = aucun hit
     * 1 = hit icône d'aperçu split
     * 2 = hit icône d'aperçu full
     *
     * <p>Les modes SHEET_* cachent les badges (le chrome de la feuille
     * possède l'interaction de fermeture). La feuille elle-même peut
     * basculer entre SHEET_SPLIT et SHEET_FULL via son toggle d'en-tête.
     */
    int hitTestIcons(float x, float y) {
        if (!view.previewable || view.previewMode != EditorView.PreviewMode.NONE) return 0;
        float density = view.getResources().getDisplayMetrics().density;
        float iconSize = PREVIEW_ICON_SIZE_DP * density;
        float margin = PREVIEW_ICON_MARGIN_DP * density;
        float iconY = margin;
        float iconW = iconSize;
        // Les icônes de toolbar ont disparu, donc les icônes d'aperçu
        // sont maintenant au bord le plus à droite.
        float fullX = view.getWidth() - margin - iconW;
        float splitX = fullX - iconW - margin * 0.5f;
        if (y >= iconY && y <= iconY + iconW) {
            if (x >= splitX && x <= splitX + iconW) return 1;
            if (x >= fullX && x <= fullX + iconW) return 2;
        }
        return 0;
    }

    // ── Géométrie du panneau d'aperçu (déplacée d'EditorView) ──────

    /**
     * Retourne la coordonnée X où commence le panneau d'aperçu.
     *
     * <p>Les modes SHEET_* retournent les bornes de la feuille — mais
     * l'éditeur ne rétrécit PAS sa zone de texte pour les modes feuille
     * (la feuille est un overlay). Ces valeurs sont passées au hôte pour
     * qu'il sache où vit le corps de la feuille.
     */
    int previewLeft() {
        if (view.previewMode == EditorView.PreviewMode.NONE) return view.getWidth();
        if (view.previewMode == EditorView.PreviewMode.FULL
                || view.previewMode == EditorView.PreviewMode.SHEET_FULL) return 0;
        // SPLIT ou SHEET_SPLIT : l'aperçu prend la moitié droite.
        return view.getWidth() / 2;
    }

    /**
     * Retourne la largeur du panneau d'aperçu.
     *
     * <p>Pour les modes SHEET_*, c'est la largeur du corps de la feuille —
     * passée au hôte pour qu'il connaisse les dimensions du canvas (quand
     * {@link EditorPreviewHost#drawPreview} sert de repli).
     */
    int previewWidth() {
        if (view.previewMode == EditorView.PreviewMode.NONE) return 0;
        if (view.previewMode == EditorView.PreviewMode.FULL
                || view.previewMode == EditorView.PreviewMode.SHEET_FULL) return view.getWidth();
        // SPLIT ou SHEET_SPLIT.
        return view.getWidth() - view.getWidth() / 2;
    }

    /**
     * Retourne la largeur effective de la zone de texte disponible pour
     * le rendu du texte. Quand l'aperçu est actif, elle est réduite pour
     * laisser place à l'aperçu.
     *
     * <p>Les modes SHEET_* ne réduisent PAS la largeur de la zone de
     * texte — la feuille flotte au-dessus de l'éditeur, donc le texte se
     * replit comme si aucun aperçu n'était actif (l'utilisateur peut
     * toujours voir et éditer le texte en fermant la feuille).
     */
    int effectiveTextWidth() {
        int fullWidth = (int) (view.getWidth() - view.metrics.getGutterWidth()
                - view.metrics.getPadLeft() - view.metrics.getPadRight());
        if (view.previewMode == EditorView.PreviewMode.NONE
                || view.previewMode.isSheet()) return fullWidth;
        if (view.previewMode == EditorView.PreviewMode.FULL) return 0;
        // SPLIT : le texte prend la moitié gauche.
        return (int) ((view.getWidth() / 2) - view.metrics.getGutterWidth()
                - view.metrics.getPadLeft() - view.metrics.getPadRight());
    }

    void setModeListener(EditorView.OnPreviewModeChangedListener listener) {
        this.previewModeListener = listener;
    }

    /**
     * Définit le mode d'aperçu. En SPLIT ou FULL, l'éditeur réserve de
     * l'espace pour le panneau d'aperçu et notifie le listener avec les
     * bornes de l'aperçu.
     *
     * <p>Quand le mode est {@link EditorView.PreviewMode#SHEET_SPLIT} ou
     * {@link EditorView.PreviewMode#SHEET_FULL}, l'éditeur ouvre une
     * feuille popup en overlay (voir {@link EditorPreviewSheet}) au lieu
     * de réserver de l'espace inline. La zone de texte reste pleine
     * largeur et la feuille flotte au-dessus.
     */
    void setPreviewMode(EditorView.PreviewMode mode) {
        if (view.previewMode == mode) return;
        // Ferme toute feuille existante avant de changer de mode.
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        view.previewMode = mode;
        // Reconstruit le modèle de wrap car la largeur de la zone de texte
        // a changé. (Les modes SHEET_* ne changent PAS la largeur du texte
        // — le modèle de wrap reste valide.)
        if (view.wordWrap && !mode.isSheet()) view.rebuildWrapModel();
        // Notifie le hôte d'aperçu du changement de mode.
        if (previewHost != null) {
            previewHost.onPreviewModeChanged(view.previewMode,
                    previewLeft(), previewWidth());
            // Pousse aussi le contenu courant immédiatement.
            if (view.previewMode != EditorView.PreviewMode.NONE && view.session != null) {
                previewHost.onPreviewContentChanged(view.session.getText());
            }
        }
        // Ouvre l'overlay de feuille pour les modes SHEET_*.
        if (mode.isSheet() && previewHost != null) {
            openPreviewSheet(mode);
        }
        view.invalidate();
        notifyModeChanged();
    }

    /**
     * Point d'entrée pratique invoqué quand l'utilisateur tape un des
     * badges d'aperçu dessinés par le renderer.
     *
     * <p>Pour les fichiers {@code .md}/{@code .html} (où le hôte fournit
     * typiquement un corps WebView via
     * {@link EditorPreviewHost#onCreatePreviewView}), ceci ouvre une
     * feuille popup en overlay ({@link EditorView.PreviewMode#SHEET_SPLIT}
     * ou {@link EditorView.PreviewMode#SHEET_FULL}) pour que la zone de
     * texte de l'éditeur reste pleine largeur et que la feuille flotte
     * au-dessus.
     *
     * <p>Pour les fichiers de layout {@code .xml} (où le hôte rend via
     * {@link EditorPreviewHost#drawPreview(Canvas, float, float)} dans le
     * canvas de l'éditeur), ceci ouvre le mode inline
     * {@link EditorView.PreviewMode#SPLIT} ou
     * {@link EditorView.PreviewMode#FULL}.
     *
     * @param full true pour le badge plein écran, false pour le badge split
     */
    void openPreview(boolean full) {
        if (!view.previewable || previewHost == null) return;
        String lower = currentFileName.toLowerCase(java.util.Locale.ROOT);
        boolean useSheet = lower.endsWith(".md")
            || lower.endsWith(".markdown")
            || lower.endsWith(".html")
            || lower.endsWith(".htm");
        EditorView.PreviewMode target = useSheet
            ? (full ? EditorView.PreviewMode.SHEET_FULL : EditorView.PreviewMode.SHEET_SPLIT)
            : (full ? EditorView.PreviewMode.FULL : EditorView.PreviewMode.SPLIT);
        setPreviewMode(target);
    }

    /**
     * Ouvre l'overlay de feuille popup pour le mode SHEET_* donné.
     * Package-private — les appelants passent par
     * {@link #setPreviewMode(EditorView.PreviewMode)} ou
     * {@link #openPreview(boolean)}.
     */
    void openPreviewSheet(EditorView.PreviewMode mode) {
        if (!mode.isSheet()) return;
        if (previewHost == null) return;
        if (view.getWidth() == 0 || view.getHeight() == 0) {
            // Diffère jusqu'au layout — planifie une tentative au prochain
            // predraw.
            view.post(() -> openPreviewSheet(mode));
            return;
        }
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        previewSheet = new EditorPreviewSheet(view, mode);
        previewSheet.show();
    }

    /**
     * Ferme la feuille popup active (s'il y en a une) et revient à
     * {@link EditorView.PreviewMode#NONE}. Appelé quand l'utilisateur tape
     * le bouton fermer de la feuille, tape hors de la feuille, ou presse
     * Retour.
     */
    void closePreviewSheet() {
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        if (view.previewMode.isSheet()) {
            view.previewMode = EditorView.PreviewMode.NONE;
            if (previewHost != null) {
                previewHost.onPreviewModeChanged(view.previewMode,
                        previewLeft(), previewWidth());
            }
            view.invalidate();
            notifyModeChanged();
        }
    }

    /**
     * Retourne la feuille popup active, ou null quand aucun mode SHEET_*
     * n'est actif. Exposé pour que le renderer et le harnais de test
     * puissent interroger l'état de la feuille sans passer par les champs
     * package-private.
     */
    EditorPreviewSheet getPreviewSheet() {
        return previewSheet;
    }

    /**
     * Pousse le texte courant de l'éditeur vers le hôte d'aperçu.
     * Appelé (débouncé) après les changements de texte quand l'aperçu est
     * actif.
     *
     * <p>Pousse aussi le contenu vers le corps de la feuille popup (s'il y
     * en a une) pour que le WebView du hôte puisse re-rendre le Markdown/
     * HTML en live pendant la frappe.
     */
    void updateContent() {
        if (previewHost == null || view.session == null) return;
        if (view.previewMode == EditorView.PreviewMode.NONE) return;
        previewHost.onPreviewContentChanged(view.session.getText());
        // La vue du corps de la feuille appartient au hôte — une fois le
        // nouveau contenu poussé via onPreviewContentChanged, demander à
        // la feuille d'invalider son corps pour que le WebView se redessine.
        if (previewSheet != null) {
            previewSheet.refreshBody();
        }
    }

    /**
     * Retourne vrai si le hôte d'aperçu a du contenu prêt à dessiner
     * <em>sur le canvas de l'éditeur</em> (aperçu inline SPLIT/FULL de
     * layout XML).
     *
     * <p>Les modes SHEET_* ne dessinent jamais sur le canvas de l'éditeur
     * (la feuille a sa propre surface), donc ceci retourne faux pour eux.
     */
    boolean isXmlPreviewActive() {
        if (view.previewMode.isSheet()) return false;
        return view.previewMode != EditorView.PreviewMode.NONE
            && previewHost != null
            && previewHost.hasPreviewContent();
    }

    /** Redimensionne la feuille d'aperçu aux nouvelles bornes de la vue. */
    void handleSizeChanged() {
        if (previewSheet != null && view.previewMode.isSheet()) {
            previewSheet.switchMode(view.previewMode);
        }
    }

    /**
     * Ferme toute feuille d'aperçu ouverte pour ne pas fuiter un popup
     * pointant vers un éditeur détaché (ce qui planterait au toucher).
     */
    void onDetachedFromWindow() {
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
    }

    /** Re-programme la mise à jour débouncée (200 ms) du contenu d'aperçu
     *  après une édition. */
    void scheduleContentUpdate() {
        if (view.previewMode != EditorView.PreviewMode.NONE && previewHost != null) {
            view.getHandler().removeCallbacks(previewUpdateTask);
            view.getHandler().postDelayed(previewUpdateTask, 200);
        }
    }

    private void notifyModeChanged() {
        if (previewModeListener != null) {
            previewModeListener.onPreviewModeChanged(view.previewMode,
                    previewLeft(), previewWidth());
        }
    }
}

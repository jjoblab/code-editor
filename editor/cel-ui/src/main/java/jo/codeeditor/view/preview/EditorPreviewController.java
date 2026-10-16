package jo.codeeditor.view.preview;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;
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
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorPreviewController {

    private final EditorView view;

    // Icônes d'aperçu dessinées au Canvas (coin haut-droit).
    // Quand previewable = true, deux petites icônes sont dessinées :
    // - icône d'aperçu split (à gauche de la paire)
    // - icône d'aperçu full (à droite de la paire)
    // Taper une icône bascule le mode aperçu.
    public static final float PREVIEW_ICON_SIZE_DP = 22f;
    public static final float PREVIEW_ICON_MARGIN_DP = 8f;

    private String currentFileName = "";

    // Hôte d'aperçu découplé. L'app hôte fournit la fonctionnalité
    // d'aperçu (inflation XML, rendu Markdown, etc.) via cette interface.
    // La bibliothèque éditeur ne dépend plus des modules d'aperçu.
    private EditorPreviewHost previewHost;
    /** Feuille popup d'overlay active quand previewMode est un mode SHEET_*. */
    EditorPreviewSheet previewSheet;
    /** B27 : carte d'aperçu « goutte d'eau » (popup de gouttière). */
    private EditorPreviewDrop drop;
    private EditorView.OnPreviewModeChangedListener previewModeListener;
    private final Runnable previewUpdateTask;

    public EditorPreviewController(EditorView view) {
        this.view = view;
        this.drop = new EditorPreviewDrop(view, this);
        this.previewUpdateTask = () -> {
            updateContent();
            view.invalidate();
        };
    }

    /** B27 — la carte « goutte d'eau » (état vivant, accès painter/tests). */
    public EditorPreviewDrop drop() {
        return drop;
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
    public void setHost(EditorPreviewHost host) {
        this.previewHost = host;
    }

    public EditorPreviewHost getHost() {
        return previewHost;
    }

    /**
     * B5 (retour terrain post-J6) — montre ou cache les icônes d'aperçu du
     * coin haut-droit du canvas. La barre de fil d'Ariane les relocalise
     * dans sa zone d'actions ({@code BreadcrumbBar.addPreviewAction}) :
     * l'icône y dessine, le canvas s'en prive (une seule entrée).
     */
    public void setPreviewIconsVisible(boolean visible) {
        view.previewIconsVisible = visible;
        view.invalidate();
    }

    /**
     * Définit le nom de fichier courant pour que l'éditeur puisse détecter
     * si l'aperçu est disponible (.md, .markdown, .html, .htm, layout .xml).
     * Quand prévisualisable, l'éditeur dessine les icônes d'aperçu dans le
     * coin haut-droit.
     */
    public void setFileName(String fileName) {
        this.currentFileName = fileName != null ? fileName : "";
        // Délègue la vérification de prévisualisabilité au hôte.
        view.previewable = (previewHost != null && previewHost.canPreview(this.currentFileName));
        // B27 : plus de sujet pour la carte si le fichier n'est plus
        // prévisualisable (changement d'échantillon) — fermeture
        // COMPLÈTE : l'état de la carte ET le mode d'aperçu retombent
        // (le hôte et le listener sont notifiés, miroir closePreviewDrop).
        if (!view.previewable && view.previewMode == EditorView.PreviewMode.DROP) {
            closePreviewDrop();
        } else {
            drop.onPreviewableChanged(view.previewable);
        }
        view.invalidate();
        // B6 : la visibilité de l'action d'aperçu (barre de fil d'Ariane)
        // dérive de previewable — rafraîchit la barre sœur SANS exiger de
        // code hôte (miroir de EditorView.notifyTextChanged).
        jo.codeeditor.view.chrome.BreadcrumbBar.invalidateActionsIn(view.getParent());
    }

    /**
     * Retourne le dernier nom de fichier défini via
     * {@link EditorView#setFileName(String)}, ou la chaîne vide si aucun
     * n'a été défini. Utilisé par la feuille d'aperçu pour afficher le nom
     * de fichier dans son en-tête.
     */
    public String getFileName() {
        return currentFileName;
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
    public int hitTestIcons(float x, float y) {
        if (!view.previewable || !view.previewIconsVisible
                || view.previewMode != EditorView.PreviewMode.NONE) return 0;
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
    public int previewLeft() {
        if (view.previewMode == EditorView.PreviewMode.NONE
                || view.previewMode == EditorView.PreviewMode.DROP) return view.getWidth();
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
    public int previewWidth() {
        if (view.previewMode == EditorView.PreviewMode.NONE
                || view.previewMode == EditorView.PreviewMode.DROP) return 0;
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
    public int effectiveTextWidth() {
        int fullWidth = (int) (view.getWidth() - view.metrics.getGutterWidth()
                - view.metrics.getPadLeft() - view.metrics.getPadRight());
        if (view.previewMode == EditorView.PreviewMode.NONE
                || view.previewMode.isSheet()
                || view.previewMode == EditorView.PreviewMode.DROP) return fullWidth;
        if (view.previewMode == EditorView.PreviewMode.FULL) return 0;
        // SPLIT : le texte prend la moitié gauche.
        return (int) ((view.getWidth() / 2) - view.metrics.getGutterWidth()
                - view.metrics.getPadLeft() - view.metrics.getPadRight());
    }

    public void setModeListener(EditorView.OnPreviewModeChangedListener listener) {
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
    public void setPreviewMode(EditorView.PreviewMode mode) {
        if (view.previewMode == mode) return;
        // Ferme toute feuille existante avant de changer de mode.
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        // B27 : quitte proprement la carte « goutte d'eau » si on
        // bascule vers un autre mode (l'état meurt, le fondu photographie).
        if (view.previewMode == EditorView.PreviewMode.DROP
                && mode != EditorView.PreviewMode.DROP) {
            drop.close();
        }
        view.previewMode = mode;
        // Reconstruit le modèle de wrap car la largeur de la zone de texte
        // a changé. (Les modes SHEET_*/DROP ne changent PAS la largeur du
        // texte — le modèle de wrap reste valide.)
        if (view.wordWrap && !mode.isSheet() && mode != EditorView.PreviewMode.DROP) {
            view.rebuildWrapModel();
        }
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
        // B6 : l'action d'aperçu de la barre sœur reflète l'état ouvert
        // (accent quand previewMode != NONE).
        jo.codeeditor.view.chrome.BreadcrumbBar.invalidateActionsIn(view.getParent());
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
    public void openPreview(boolean full) {
        if (!view.previewable || previewHost == null) return;
        // B27 : les hôtes « goutte d'eau » (canvas) ouvrent la CARTE —
        // l'alternative mobile au split view, ancrée à l'œil de
        // gouttière, non modale, esquivant la frappe.
        if (previewHost.prefersDropPreview()) {
            openPreviewDropAt(currentCaretLine());
            return;
        }
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

    /** Ligne de document du caret courant (0 si sans session). */
    private int currentCaretLine() {
        if (view.session == null || view.session.getDocument() == null) return 0;
        return view.session.getDocument().lineForOffset(
                view.session.getSelection().start);
    }

    /**
     * B27 — ouvre la carte « goutte d'eau » ancrée à la ligne donnée
     * (l'œil se GÈLE sur cette rangée, la carte pointe vers lui).
     * Pousse le mode {@link EditorView.PreviewMode#DROP}, notifie le
     * hôte du mode et du contenu initial, et programme la mise à jour
     * débouncée (le rendu vivant suit la frappe).
     */
    public void openPreviewDropAt(int line) {
        if (!view.previewable || previewHost == null) return;
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        boolean wasOpen = view.previewMode == EditorView.PreviewMode.DROP;
        view.previewMode = EditorView.PreviewMode.DROP;
        drop.openAt(line);
        if (!wasOpen) {
            if (previewHost != null) {
                previewHost.onPreviewModeChanged(view.previewMode,
                        previewLeft(), previewWidth());
            }
            notifyModeChanged();
            jo.codeeditor.view.chrome.BreadcrumbBar.invalidateActionsIn(view.getParent());
        }
        // Contenu initial immédiat (miroir setPreviewMode).
        if (previewHost != null && view.session != null) {
            previewHost.onPreviewContentChanged(view.session.getText());
        }
        view.invalidate();
    }

    /**
     * B27 — referme la carte « goutte d'eau » et revient à
     * {@link EditorView.PreviewMode#NONE}. Appelé par le ✕ de la carte,
     * le re-tap sur l'œil, le changement de session/fichier.
     */
    public void closePreviewDrop() {
        if (view.previewMode != EditorView.PreviewMode.DROP) return;
        drop.close();
        view.previewMode = EditorView.PreviewMode.NONE;
        if (previewHost != null) {
            previewHost.onPreviewModeChanged(view.previewMode,
                    previewLeft(), previewWidth());
        }
        view.invalidate();
        notifyModeChanged();
        jo.codeeditor.view.chrome.BreadcrumbBar.invalidateActionsIn(view.getParent());
    }

    /**
     * B27 — bascule la carte depuis l'œil de gouttière : ouvre ancrée
     * à la rangée du caret, ou referme si déjà ouverte.
     */
    public void togglePreviewDrop() {
        if (view.previewMode == EditorView.PreviewMode.DROP) {
            closePreviewDrop();
        } else {
            openPreviewDropAt(currentCaretLine());
        }
    }

    /**
     * B27 — changement de session (miroir des purges de setSession :
     * loupe, drag, wrap, recherches) : l'ancre de la carte décrit
     * l'ANCIEN document, la carte se referme proprement et le mode
     * d'aperçu retombe à NONE (notifications hôte/listener posées).
     * Appelé par {@link EditorView#setSession}.
     */
    public void onSessionChanged() {
        if (view.previewMode == EditorView.PreviewMode.DROP) {
            closePreviewDrop();
        } else {
            drop.onSessionChanged();
        }
    }

    /**
     * B27 — hit-test de la carte « goutte d'eau » : vrai quand la carte
     * est ouverte ET que le point tombe dans son rectangle visuel. Le
     * tap appartient alors à la carte (onglets et ✕ agissent via
     * {@code EditorPreviewDrop.hitTest}, le corps engloutit sans agir —
     * le caret ne bouge pas). Résolu par la cascade de tap AVANT le
     * placement du caret ; relais restreint sur la vue
     * ({@code EditorView.hitTestPreviewDrop}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean hitTestDrop(float x, float y) {
        if (view.previewMode != EditorView.PreviewMode.DROP || !drop.isOpen()) {
            return false;
        }
        return drop.hitTest(x, y);
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
    public void closePreviewSheet() {
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
            // B6 : l'action d'aperçu de la barre sœur s'éteint (la feuille
            // est fermée — plus d'état actif).
            jo.codeeditor.view.chrome.BreadcrumbBar.invalidateActionsIn(view.getParent());
        }
    }

    /**
     * Retourne la feuille popup active, ou null quand aucun mode SHEET_*
     * n'est actif. Exposé pour que le renderer et le harnais de test
     * puissent interroger l'état de la feuille sans passer par les champs
     * package-private.
     */
    public EditorPreviewSheet getPreviewSheet() {
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
    public boolean isXmlPreviewActive() {
        if (view.previewMode.isSheet()) return false;
        if (view.previewMode == EditorView.PreviewMode.DROP) return false;
        return view.previewMode != EditorView.PreviewMode.NONE
            && previewHost != null
            && previewHost.hasPreviewContent();
    }

    /** Redimensionne la feuille d'aperçu aux nouvelles bornes de la vue. */
    public void handleSizeChanged() {
        if (previewSheet != null && view.previewMode.isSheet()) {
            previewSheet.switchMode(view.previewMode);
        }
    }

    /**
     * Ferme toute feuille d'aperçu ouverte pour ne pas fuiter un popup
     * pointant vers un éditeur détaché (ce qui planterait au toucher).
     */
    public void onDetachedFromWindow() {
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        // B27 : la carte vit sur le canvas de l'éditeur — pas de
        // fenêtre à refermer, mais l'état meurt avec le détachement
        // (miroir de la purge de la feuille).
        if (view.previewMode == EditorView.PreviewMode.DROP) {
            closePreviewDrop();
        }
    }

    /** Re-programme la mise à jour débouncée (200 ms) du contenu d'aperçu
     *  après une édition. */
    public void scheduleContentUpdate() {
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

package jo.codeeditor.view.preview;

import jo.codeeditor.view.EditorView;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * Contrat côté hôte pour la fonctionnalité d'aperçu.
 *
 * <p>{@link EditorView} ne dépend plus directement des modules d'aperçu.
 * L'application hôte implémente cette interface et l'enregistre via
 * {@link EditorView#setPreviewHost(EditorPreviewHost)}.
 *
 * <p>Ce découplage permet d'utiliser la bibliothèque d'éditeur sans aucun
 * module d'aperçu, et laisse les modules d'aperçu évoluer indépendamment.
 *
 * <h3>Usage typique</h3>
 * <pre>{@code
 * EditorView editor = findViewById(R.id.editor);
 * editor.setPreviewHost(new MyPreviewHost(this));
 * editor.setFileName("layout.xml");  // déclenche le contrôle canPreview()
 * editor.setPreviewMode(EditorView.PreviewMode.SPLIT);
 * }</pre>
 *
 * <p>L'hôte est responsable de :
 * <ul>
 *   <li>Détecter si un fichier est prévisualisable (layout XML, Markdown, HTML)</li>
 *   <li>Rendre l'aperçu quand l'éditeur entre en mode SPLIT/FULL</li>
 *   <li>Dessiner l'aperçu sur le Canvas (appelé par EditorRenderer)</li>
 *   <li>Mettre à jour l'aperçu quand le texte de l'éditeur change (débounce)</li>
 *   <li>Tester les touches dans le panneau d'aperçu</li>
 * </ul>
 */
public interface EditorPreviewHost {

    /**
     * Renvoie vrai si le nom de fichier donné peut être prévisualisé par cet hôte.
     *
     * <p>Appelée par {@link EditorView#setFileName(String)} pour déterminer
     * s'il faut dessiner les icônes d'aperçu dans le coin supérieur droit.
     *
     * @param fileName le nom de fichier courant (peut être null)
     * @return vrai si le fichier est prévisualisable (.xml, .md, .html, etc.)
     */
    boolean canPreview(String fileName);

    /**
     * Appelée quand l'éditeur entre ou sort du mode aperçu.
     *
     * <p>L'hôte doit afficher/masquer sa surface d'aperçu et démarrer/arrêter
     * le rendu. Pour les layouts XML, l'hôte inflate généralement le XML
     * et dessine l'arbre de Views résultant. Pour Markdown/HTML, l'hôte
     * affiche généralement un overlay WebView.
     *
     * @param mode      le nouveau mode d'aperçu (NONE, SPLIT ou FULL)
     * @param previewLeft la coordonnée X où commence le panneau d'aperçu
     * @param previewWidth la largeur du panneau d'aperçu en pixels
     */
    void onPreviewModeChanged(EditorView.PreviewMode mode, int previewLeft, int previewWidth);

    /**
     * Appelée quand le texte de l'éditeur a changé (débounce ~200 ms).
     *
     * <p>L'hôte doit re-rendre son aperçu pour refléter le nouveau texte.
     * Pour les layouts XML, cela signifie ré-inflater le XML.
     *
     * @param text le texte courant de l'éditeur
     */
    void onPreviewContentChanged(CharSequence text);

    /**
     * Dessine l'aperçu sur le Canvas donné.
     *
     * <p>Appelée à chaque frame par {@link jo.codeeditor.view.EditorRenderer}
     * quand le mode aperçu est actif. L'hôte doit dessiner son contenu
     * d'aperçu à l'offset donné.
     *
     * <p>Pour les layouts XML, cela appelle généralement
     * {@code NativeXmlPreviewRenderer.draw(canvas, offsetX, offsetY)}.
     * Pour Markdown/HTML, c'est généralement un no-op (la WebView gère
     * son propre dessin).
     *
     * @param canvas   le Canvas sur lequel dessiner
     * @param offsetX  offset horizontal (bord gauche du panneau d'aperçu)
     * @param offsetY  offset vertical (généralement 0)
     */
    void drawPreview(Canvas canvas, float offsetX, float offsetY);

    /**
     * Renvoie vrai si l'aperçu a un contenu prêt à être dessiné.
     *
     * <p>Utilisé par {@link EditorView#isXmlPreviewActive()} pour déterminer
     * s'il faut appeler {@link #drawPreview(Canvas, float, float)}.
     */
    boolean hasPreviewContent();

    /**
     * Teste si un point du panneau d'aperçu touche un élément.
     *
     * <p>Appelée quand l'utilisateur tape dans la zone d'aperçu. L'hôte
     * doit sélectionner la vue correspondante (pour les layouts XML) ou
     * ignorer le tap (pour les aperçus basés WebView).
     *
     * @param x la coordonnée X relative au panneau d'aperçu
     * @param y la coordonnée Y relative au panneau d'aperçu
     * @return vrai si une vue a été touchée et sélectionnée
     */
    boolean hitTestPreview(float x, float y);

    /**
     * Hook optionnel pour un aperçu basé View (WebView pour Markdown/HTML).
     *
     * <p>Quand l'utilisateur tape le badge d'aperçu scindé/plein écran pour
     * un fichier {@code .md}/{@code .html}, l'éditeur ouvre une feuille
     * popup (voir {@link EditorView#openPreview(boolean)}) et demande à
     * l'hôte de fournir une {@link View} Android qui rend le contenu
     * d'aperçu. L'hôte renvoie typiquement une {@code WebView} configurée
     * pour le rendu Markdown, ou une View canvas personnalisée.
     *
     * <p>La feuille elle-même (chrome, en-tête, bouton fermer, glisser-pour-fermer)
     * appartient à la bibliothèque d'éditeur — l'hôte ne possède que la View
     * de corps. L'éditeur appelle {@link #onPreviewContentChanged(CharSequence)}
     * (débounce ~200 ms) pour que l'hôte re-rende son corps.
     *
     * <p>Renvoyer {@code null} (par défaut) indique à l'éditeur de retomber
     * sur {@link #drawPreview(Canvas, float, float)} pour les hôtes canvas
     * uniquement (ex. aperçu natif de layout XML). Le corps de la feuille
     * héberge alors une {@code View} dont le {@code onDraw} délègue à
     * {@code drawPreview}.
     *
     * <p>Les hôtes qui surchargent cette méthode doivent garantir que la View
     * renvoyée est réutilisable entre appels {@code onPreviewContentChanged} —
     * c.-à-d. mettre à jour son contenu en place plutôt que ré-inflater.
     *
     * @param ctx    le contexte d'application pour inflater de nouvelles vues
     * @param editor la vue éditeur à laquelle la feuille est ancrée
     * @param mode   le mode d'aperçu demandé (un parmi
     *               {@link EditorView.PreviewMode#SHEET_SPLIT} ou
     *               {@link EditorView.PreviewMode#SHEET_FULL})
     * @return une View d'aperçu entièrement initialisée, ou {@code null}
     *         pour retomber sur le dessin canvas via {@link #drawPreview}
     */
    default View onCreatePreviewView(Context ctx, EditorView editor,
                                     EditorView.PreviewMode mode) {
        return null;
    }
}

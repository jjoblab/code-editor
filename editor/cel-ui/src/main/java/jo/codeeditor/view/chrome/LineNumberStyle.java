package jo.codeeditor.view.chrome;

/**
 * B12 (feuille de route « écarts Android Studio », écart P1-4) — mode
 * d'affichage des numéros de ligne de la gouttière.
 *
 * <ul>
 *   <li>{@link #ABSOLUTE} — le mode historique : chaque rangée porte son
 *       numéro de document (1, 2, 3…). Comportement inchangé par défaut :
 *       un hôte qui ne touche pas au style ne voit AUCUNE différence.</li>
 *   <li>{@link #RELATIVE} — distance à la ligne du caret (0 sur la
 *       rangée du caret, 1 au-dessus/en-dessous, 2…). La convention des
 *       éditeurs pilotables au clavier ({@code 5j}/{@code 10k} en Vim)
 *       et d'Android Studio (« relative line numbers ») : la distance se
 *       lit sans calcul mental.</li>
 *   <li>{@link #HYBRID} — numéro RÉEL sur la ligne du caret, distances
 *       relatives partout ailleurs (le mode par défaut de VS Code) :
 *       garde un repère absolu où l'on se trouve, les distances autour.</li>
 * </ul>
 *
 * <p>La ligne du caret reste en gras/couleur texte dans les trois modes
 * (mise en évidence existante inchangée). La distance est calculée sur
 * la ligne de DÉBUT de la sélection — la même ligne que la surbrillance
 * courante de la gouttière, une seule source de vérité.</p>
 *
 * @author jo@Dev
 */
public enum LineNumberStyle {
    ABSOLUTE,
    RELATIVE,
    HYBRID
}

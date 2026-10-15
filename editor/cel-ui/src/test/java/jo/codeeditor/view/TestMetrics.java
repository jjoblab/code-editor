package jo.codeeditor.view;

import java.lang.reflect.Field;

/**
 * Injection de métriques de rendu pour les tests Robolectric (A-06,
 * audit 3.69.0) — le point d'accès de test UNIQUE pour les champs de
 * {@link EditorMetrics}.
 *
 * <p>70 fichiers de test dupliquaient chacun sa copie privée
 * {@code injectMetrics} + {@code setFloat} (réflexion éparpillée :
 * getDeclaredField/setAccessible dans chaque fichier, à maintenir en
 * cas de renommage de champ). La réflexion vit désormais ICI, en un
 * seul exemplaire ; les tests passent leurs valeurs. Aucune méthode
 * publique n'est ajoutée au code de production (cliquet A-01 :
 * EditorView est à 279/280 méthodes publiques — un point d'accès de
 * test côté production est impossible).</p>
 *
 * <p>Les champs restent en accès package/privé dans EditorMetrics :
 * la réflexion est assumée comme OUTIL DE TEST (documentée dans
 * AGENT.md — ré-injecter après tout changement de taille).</p>
 */
public final class TestMetrics {

    private TestMetrics() {}

    /**
     * Le patron canonique de la suite : pads et gouttière DÉRIVÉS des
     * deux unités de base (padTop = ligne/2, padLeft = caractère/2,
     * gouttière = 7 caractères, bande de plis = 2 caractères).
     */
    public static void injecter(EditorView view, float lineHeight, float charWidth) {
        setFloat(view.metrics, "lineHeight", lineHeight);
        setFloat(view.metrics, "charWidth", charWidth);
        setFloat(view.metrics, "padTop", lineHeight * 0.5f);
        setFloat(view.metrics, "padLeft", charWidth * 0.5f);
        setFloat(view.metrics, "gutterWidth", charWidth * 7f);
        setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
    }

    /**
     * Variante avec pad droit explicite (les tests qui vérifient le
     * débordement à droite l'exigent).
     */
    public static void injecter(EditorView view, float lineHeight, float charWidth,
            float padRight) {
        injecter(view, lineHeight, charWidth);
        setFloat(view.metrics, "padRight", padRight);
    }

    /**
     * Pose un champ float par réflexion (le seul exemplaire de
     * setAccessible du domaine métrique).
     */
    public static void setFloat(Object target, String field, float value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setFloat(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

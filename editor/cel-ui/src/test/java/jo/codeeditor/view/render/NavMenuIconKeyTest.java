package jo.codeeditor.view.render;

import org.junit.Test;

import jo.codeeditor.navigation.NavigationMenu;

import static org.junit.Assert.assertEquals;

/**
 * Clé d'icône du nav menu (Phase 2, constat 8) : l'icône d'une option
 * de navigation s'accroche à son IDENTIFIANT STABLE
 * ({@code NavOption.key}), jamais à son libellé — le rendu historique
 * comparait le libellé anglais en dur (« Type declaration ».equals…),
 * donc une option au libellé traduit ou surchargé par l'hôte perdait
 * son icône. La couture {@code navOptionIconKey} est pure.
 */
public class NavMenuIconKeyTest {

    @Test
    public void libelléTraduit_laCléReste() {
        // AVANT la correction : l'hôte traduit « Type declaration » en
        // « Déclaration de type » → l'icône box disparaissait (la
        // comparaison par libellé ne matchait plus). La clé survit.
        NavigationMenu.NavOption option = new NavigationMenu.NavOption(
                "Déclaration de type",
                NavigationMenu.NavOption.KEY_TYPE_DECLARATION,
                java.util.Collections.emptyList());
        assertEquals(NavigationMenu.NavOption.KEY_TYPE_DECLARATION,
                EditorChromePainter.navOptionIconKey(option));
    }

    @Test
    public void chaqueCléCanoniaqueEstRetournéeTelleQuelle() {
        // Les quatre options canoniques exposent leur clé — l'icône
        // (box / layers / pin / code) en dérive côté painter.
        String[][] cas = {
                {NavigationMenu.NavOption.KEY_DECLARATION, "Aller à la définition"},
                {NavigationMenu.NavOption.KEY_IMPLEMENTATIONS, "Implémentations"},
                {NavigationMenu.NavOption.KEY_TYPE_DECLARATION, "Déclaration de type"},
                {NavigationMenu.NavOption.KEY_SUPER, "Super"},
        };
        for (String[] c : cas) {
            NavigationMenu.NavOption o = new NavigationMenu.NavOption(
                    c[1], c[0], java.util.Collections.emptyList());
            assertEquals("la clé prime sur le libellé «" + c[1] + " »",
                    c[0], EditorChromePainter.navOptionIconKey(o));
        }
    }

    @Test
    public void optionSansClé_ouAutreRéférentiel_cléVide() {
        // Constructeur historique (deux arguments) : clé vide, icône
        // générique — aucune exception, aucun dépendance au libellé.
        NavigationMenu.NavOption légacy = new NavigationMenu.NavOption(
                "Declaration", java.util.Collections.emptyList());
        assertEquals("", EditorChromePainter.navOptionIconKey(légacy));
        // Référentiel inconnu (rangée d'action) : clé vide.
        assertEquals("", EditorChromePainter.navOptionIconKey("autre"));
        assertEquals("", EditorChromePainter.navOptionIconKey(null));
    }
}

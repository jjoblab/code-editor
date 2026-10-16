package jo.codeeditor.demo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B6 — moteur d'aperçu Markdown v2 de la démo
 * ({@link DemoPreviewHost#markdownToHtml}) : liens, images, listes
 * ordonnées, listes de tâches, tables GFM, barré, titres profonds —
 * plus le contrat canPreview (.md/.markdown/.html/.htm).
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class DemoPreviewHostTest {

    // ── canPreview ────────────────────────────────────────────────

    @Test
    public void canPreview_familleMarkdownHtml() {
        DemoPreviewHost host = new DemoPreviewHost();
        assertTrue("markdown", host.canPreview("exemple.md"));
        assertTrue("markdown long", host.canPreview("notes.markdown"));
        assertTrue("html", host.canPreview("index.html"));
        assertTrue("htm", host.canPreview("page.htm"));
        assertFalse("java", host.canPreview("Main.java"));
        assertFalse("null", host.canPreview(null));
    }

    // ── B27 : la carte « goutte d'eau » (layouts .xml) ─────────────

    @Test
    public void b27_canPreview_accepteLesLayoutsXml() {
        DemoPreviewHost host = new DemoPreviewHost();
        assertTrue("layout xml", host.canPreview("activity_main.xml"));
        assertFalse("kotlin", host.canPreview("MainActivity.kt"));
        // Après un fichier xml, un retour à un fichier java referme la
        // voie d'aperçu (le mémo de nom pilote le rendu).
        host.canPreview("activity_main.xml");
        assertFalse(host.canPreview("Main.java"));
    }

    @Test
    public void b27_prefersDropPreview_vraiSeulementPourLesLayouts() {
        DemoPreviewHost host = new DemoPreviewHost();
        host.canPreview("activity_main.xml");
        assertTrue("le layout passe par la carte gouttière",
                host.prefersDropPreview());
        host.canPreview("exemple.md");
        assertFalse("le markdown garde la feuille WebView",
                host.prefersDropPreview());
    }

    @Test
    public void b27_ongletsEtBadgesDuFichierLayout() {
        DemoPreviewHost host = new DemoPreviewHost();
        host.canPreview("exemple.md");
        assertTrue("aucun onglet pour la feuille WebView",
                host.previewDropTabs().isEmpty());
        host.canPreview("activity_main.xml");
        assertEquals(2, host.previewDropTabs().size());
        assertEquals("XML", host.previewDropTabs().get(0));
        assertEquals("Compose", host.previewDropTabs().get(1));
        assertEquals("activity_main.xml · gonflé", host.previewDropBadge(0));
        assertEquals("MainActivity.kt · @Composable", host.previewDropBadge(1));
    }

    // ── Retour terrain n°3 : sink de persistance de la barre de taille ──

    @Test
    public void b27_barreDeTaille_sinkDePersistanceBranché() {
        DemoPreviewHost host = new DemoPreviewHost();
        // Sans sink : le crochet par défaut est inoffensif (no-op).
        host.onPreviewDropScaleChanged(1.3f);
        // Avec sink : le facteur choisi dans la barre du pied de la
        // goutte est transmis tel quel à l'atelier (→ EditorPrefs).
        java.util.List<Float> vus = new java.util.ArrayList<>();
        host.setScalePersistence(vus::add);
        host.onPreviewDropScaleChanged(1.3f);
        host.onPreviewDropScaleChanged(1.5f);
        assertEquals(2, vus.size());
        assertEquals(1.3f, vus.get(0), 0.0001f);
        assertEquals(1.5f, vus.get(1), 0.0001f);
    }

    // ── Blocs ─────────────────────────────────────────────────────

    @Test
    public void titresJusquAuNiveau6() {
        String html = DemoPreviewHost.markdownToHtml(
                "# Un\n## Deux\n### Trois\n#### Quatre\n");
        assertTrue(html.contains("<h1>Un</h1>"));
        assertTrue(html.contains("<h2>Deux</h2>"));
        assertTrue(html.contains("<h3>Trois</h3>"));
        assertTrue(html.contains("<h4>Quatre</h4>"));
        assertFalse("pas de h5 fantôme", html.contains("<h5>"));
    }

    @Test
    public void blocsDeCodeEtCodeEnLigne() {
        String html = DemoPreviewHost.markdownToHtml(
                "Texte avec `code inline` simple.\n"
                + "```java\nint x = 1 < 2;\n```\n");
        assertTrue(html.contains("<code>code inline</code>"));
        assertTrue(html.contains("<pre><code>"));
        assertTrue("le contenu du bloc est échappé", html.contains("1 &lt; 2;"));
        assertTrue(html.contains("</code></pre>"));
    }

    @Test
    public void listesNonOrdonneesEtOrdonnees() {
        String html = DemoPreviewHost.markdownToHtml(
                "- un\n- deux\n\n1. premier\n2. second\n");
        assertTrue(html.contains("<ul><li>un</li><li>deux</li></ul>"));
        assertTrue(html.contains("<ol><li>premier</li><li>second</li></ol>"));
    }

    @Test
    public void listesDeTachesCocheesEtACocher() {
        String html = DemoPreviewHost.markdownToHtml(
                "- [x] livré\n- [ ] à faire\n");
        assertTrue(html.contains("<li><span class=\"task done\"></span>livré</li>"));
        assertTrue(html.contains("<li><span class=\"task\"></span>à faire</li>"));
    }

    @Test
    public void tableGfmAvecEnTeteEtCorps() {
        String html = DemoPreviewHost.markdownToHtml(
                "| Module | Rôle |\n|---|---|\n| core | moteur |\n| ui | vue |\n");
        assertTrue(html.contains("<table><thead><tr><th>Module</th><th>Rôle</th>"));
        assertTrue(html.contains("<td>core</td>"));
        assertTrue(html.contains("<td>vue</td>"));
        assertTrue(html.contains("</tbody></table>"));
    }

    @Test
    public void citationsEtReglesHorizontales() {
        String html = DemoPreviewHost.markdownToHtml(
                "> une citation\n---\n");
        assertTrue(html.contains("<blockquote>"));
        assertTrue(html.contains("</blockquote>"));
        assertTrue(html.contains("<hr>"));
    }

    // ── En ligne ──────────────────────────────────────────────────

    @Test
    public void grasItaliqueEtBarre() {
        String html = DemoPreviewHost.markdownToHtml(
                "**gras** et *italique* et _souligné_ et ~~barré~~\n");
        assertTrue(html.contains("<b>gras</b>"));
        assertTrue(html.contains("<i>italique</i>"));
        assertTrue(html.contains("<i>souligné</i>"));
        assertTrue(html.contains("<del>barré</del>"));
    }

    @Test
    public void liensEtImages() {
        String html = DemoPreviewHost.markdownToHtml(
                "Voir la [doc](https://example.com/a_b) et ![logo](https://example.com/x.png)\n");
        assertTrue(html.contains("<a href=\"https://example.com/a_b\">doc</a>"));
        assertTrue(html.contains(
                "<img src=\"https://example.com/x.png\" alt=\"logo\">"));
        // Le tiret bas de l'URL ne doit PAS devenir de l'italique (le
        // scanner ignore l'intérieur des balises déjà posées).
        assertFalse("l'URL garde son tiret bas intact",
                html.contains("<i>"));
    }

    @Test
    public void echappementHtmlAvantInterpretation() {
        String html = DemoPreviewHost.markdownToHtml(
                "<script>alert(1)</script> et &amp; **ok**\n");
        assertFalse("script échappé", html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue("le markdown APRÈS l'échappement reste interprété",
                html.contains("<b>ok</b>"));
    }

    // ── Robustesse ────────────────────────────────────────────────

    @Test
    public void nullEtVideRendentVide() {
        assertEquals("", DemoPreviewHost.markdownToHtml(null));
        assertEquals("", DemoPreviewHost.markdownToHtml(""));
    }

    @Test
    public void marqueursNonAppariesRestentLittéraux() {
        // Un astérisque isolé, un backtick orphelin : pas de balise cassée.
        String html = DemoPreviewHost.markdownToHtml("un * seul, ` seul\n");
        assertFalse(html.contains("<i>"));
        assertFalse(html.contains("<code>"));
        assertTrue(html.contains("un * seul"));
    }

    @Test
    public void lEchantillonMarkdownDeLaDemoSeRend() {
        String html = DemoPreviewHost.markdownToHtml(
                DemoSamples.sampleFor("markdown"));
        assertTrue("titre rendu", html.contains("<h1>"));
        assertTrue("table rendue", html.contains("<table>"));
        assertTrue("tâches rendues", html.contains("task"));
        assertTrue("lien rendu", html.contains("<a href"));
        assertTrue("barré rendu", html.contains("<del>"));
        assertTrue("liste ordonnée rendue", html.contains("<ol>"));
        assertTrue("fence rendu", html.contains("<pre><code>"));
    }

    // ════════════════════════════════════════════════════════════════
    // B7 (retour terrain n°4) — routage HTML brut vs Markdown + le
    // nouvel échantillon HTML de la démo.
    // ════════════════════════════════════════════════════════════════

    @Test
    public void payloadFor_fichierHtml_passeBrutSansConversion() {
        String page = "<!DOCTYPE html><html><head><style>body{}</style></head>"
                + "<body><h1>Salut</h1></body></html>";
        // Avant B7, le HTML passait dans markdownToHtml qui l'ÉCHAPPAIT —
        // les balises devenaient du texte visible.
        assertEquals("le HTML brut passe TEL QUEL",
                page, DemoPreviewHost.payloadFor("page.html", page));
        assertEquals("idem pour .htm",
                page, DemoPreviewHost.payloadFor("page.htm", page));
    }

    @Test
    public void payloadFor_fichierMd_passeDansLaConversion() {
        String payload = DemoPreviewHost.payloadFor("exemple.md", "# Titre\n");
        assertTrue("l'enveloppe sombre de la démo est posée",
                payload.contains("<!DOCTYPE html>"));
        assertTrue("le Markdown est interprété (pas échappé)",
                payload.contains("<h1>Titre</h1>"));
        // Nom absent → défaut sûr : conversion Markdown.
        String defaut = DemoPreviewHost.payloadFor(null, "# Titre\n");
        assertTrue("sans nom de fichier : conversion Markdown",
                defaut.contains("<h1>Titre</h1>"));
    }

    @Test
    public void lEchantillonHtmlDeLaDemoPasseBrutEtEstComplet() {
        String sample = DemoSamples.sampleFor("html");
        assertTrue("l'échantillon HTML existe",
                sample.contains("<!DOCTYPE html>"));
        // Rendu BRUT : strictement identique (aucune conversion, aucun
        // échappement — la page porte ses propres styles).
        assertEquals("la page HTML passe brute dans la feuille",
                sample, DemoPreviewHost.payloadFor("page.html", sample));
        assertTrue("page autoportante (styles intégrés)",
                sample.contains("<style>"));
        assertTrue("viewport pour l'ajustement mobile",
                sample.contains("width=device-width"));
        // L'échantillon XML n'est PLUS mappé à un faux nom .html.
        String xml = DemoSamples.sampleFor("xml");
        assertFalse("l'échantillon XML reste du XML",
                xml.startsWith("<!DOCTYPE html>"));
    }

    @Test
    public void webviewCree_avecZoomTexteVerrouilleEtApercuAjuste() {
        org.robolectric.RuntimeEnvironment.getApplication();
        DemoPreviewHost host = new DemoPreviewHost();
        android.content.Context ctx =
                org.robolectric.RuntimeEnvironment.getApplication();
        jo.codeeditor.view.EditorView editor =
                new jo.codeeditor.view.EditorView(ctx);
        android.view.View body = host.onCreatePreviewView(
                ctx, editor, jo.codeeditor.view.EditorView.PreviewMode.SHEET_SPLIT);
        assertTrue("le corps est un WebView", body instanceof android.webkit.WebView);
        android.webkit.WebSettings settings = ((android.webkit.WebView) body).getSettings();
        assertEquals("zoom de texte verrouillé à 100 (échelle système ignorée)",
                100, settings.getTextZoom());
        assertTrue("mode aperçu : page ajustée à la feuille",
                settings.getLoadWithOverviewMode());
        assertTrue("viewport large actif", settings.getUseWideViewPort());
        assertFalse("pas de JavaScript dans l'aperçu",
                settings.getJavaScriptEnabled());
    }

    // ════════════════════════════════════════════════════════════════
    // B8 (retour terrain n°5) — « preview en full : le zoom est trop, pas
    // fit, je ne peux pas zoom ou dezoomer ».
    // ════════════════════════════════════════════════════════════════

    @Test
    public void webviewCree_pincementZoomActifSansBoutons() {
        // « je ne peux pas zoom ou dezoomer » : les contrôles de zoom
        // intégrés étaient désactivés (défaut Android) — le pincement ne
        // faisait RIEN dans la feuille d'aperçu.
        org.robolectric.RuntimeEnvironment.getApplication();
        DemoPreviewHost host = new DemoPreviewHost();
        android.content.Context ctx =
                org.robolectric.RuntimeEnvironment.getApplication();
        jo.codeeditor.view.EditorView editor =
                new jo.codeeditor.view.EditorView(ctx);
        android.view.View body = host.onCreatePreviewView(
                ctx, editor, jo.codeeditor.view.EditorView.PreviewMode.SHEET_SPLIT);
        android.webkit.WebSettings settings = ((android.webkit.WebView) body).getSettings();
        assertTrue("contrôles de zoom intégrés ACTIFS (pincement)",
                settings.getBuiltInZoomControls());
        assertFalse("sans les boutons +/- qui polluent la carte flottante",
                settings.getDisplayZoomControls());
    }

    @Test
    public void changementDeTaille_rechargeLePayload_pourReAjuster() {
        // « n'est toujours pas fit » en plein : scindé → plein, le WebView
        // gardait l'échelle de l'ANCIENNE largeur. B8 : onPreviewModeChanged
        // (appelé par la bascule du segmented control ET le resize) recharge
        // le payload — overview mode ré-ajuste à la nouvelle largeur.
        org.robolectric.RuntimeEnvironment.getApplication();
        DemoPreviewHost host = new DemoPreviewHost();
        android.content.Context ctx =
                org.robolectric.RuntimeEnvironment.getApplication();
        jo.codeeditor.view.EditorView editor =
                new jo.codeeditor.view.EditorView(ctx);
        editor.setSession(new jo.codeeditor.session.EditorSession(
                jo.codeeditor.document.EditorDocument.of("# Titre\n")));
        host.canPreview("exemple.md");

        // Ouverture initiale : le contenu est chargé une première fois.
        host.onCreatePreviewView(ctx, editor,
                jo.codeeditor.view.EditorView.PreviewMode.SHEET_SPLIT);
        int afterOpen = host.reloadCount;

        // Bascule scindé → plein (la feuille vient d'être agrandie).
        host.onPreviewModeChanged(jo.codeeditor.view.EditorView.PreviewMode.SHEET_FULL,
                0, 1080);
        assertEquals("la bascule de taille recharge le payload (re-fit)",
                afterOpen + 1, host.reloadCount);

        // Retour plein → scindé : idem (rétrécissement symétrique).
        host.onPreviewModeChanged(jo.codeeditor.view.EditorView.PreviewMode.SHEET_SPLIT,
                540, 540);
        assertEquals("le retour en scindé recharge aussi",
                afterOpen + 2, host.reloadCount);

        // Fermeture (mode NONE) : aucun rechargement inutile — la WebView
        // vit encore (cache de l'hôte), on ne paie pas un reload fantôme.
        host.onPreviewModeChanged(jo.codeeditor.view.EditorView.PreviewMode.NONE, 0, 0);
        assertEquals("la fermeture ne recharge rien",
                afterOpen + 2, host.reloadCount);
    }

    @Test
    public void contenuAvantOuverture_premierChargementALouverture_puisReFitPossible() {
        // L'éditeur pousse le contenu AVANT que la feuille n'existe
        // (setPreviewMode → onPreviewContentChanged, la WebView n'est pas
        // encore créée) : le dernier texte est retenu pour le re-fit.
        org.robolectric.RuntimeEnvironment.getApplication();
        DemoPreviewHost host = new DemoPreviewHost();
        android.content.Context ctx =
                org.robolectric.RuntimeEnvironment.getApplication();
        jo.codeeditor.view.EditorView editor =
                new jo.codeeditor.view.EditorView(ctx);
        editor.setSession(new jo.codeeditor.session.EditorSession(
                jo.codeeditor.document.EditorDocument.of("# Bonjour\n")));
        host.canPreview("exemple.md");

        // Contenu poussé SANS WebView vivante : rien ne charge encore…
        host.onPreviewContentChanged("# Bonjour\n");
        assertEquals("rien ne charge sans WebView", 0, host.reloadCount);
        // …mais le texte est retenu : l'ouverture le charge,
        host.onCreatePreviewView(ctx, editor,
                jo.codeeditor.view.EditorView.PreviewMode.SHEET_FULL);
        assertEquals("l'ouverture charge le contenu retenu", 1, host.reloadCount);
        // …et le premier changement de taille peut re-fiter dessus.
        host.onPreviewModeChanged(jo.codeeditor.view.EditorView.PreviewMode.SHEET_SPLIT,
                540, 540);
        assertEquals("le re-fit a bien un texte à re-rendre", 2, host.reloadCount);
    }
}

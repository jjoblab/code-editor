package jo.codeeditor.demo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.webkit.WebView;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.preview.EditorPreviewHost;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hôte d'aperçu de la démo (B5 → <b>v2 B6</b> → <b>v3 B7</b>) : rend les
 * échantillons <b>Markdown/HTML</b> dans la feuille d'aperçu de l'éditeur
 * (WebView, conversion HTML maison). C'est la preuve APK que l'action « œil »
 * de la barre de fil d'Ariane ouvre un aperçu vivant — la feuille flotte sur
 * l'éditeur (carte modernisée B6), et le contenu se re-rend en frappant
 * (débounce ~200 ms côté bibliothèque).
 *
 * <p><b>Moteur v2 (B6)</b> — {@link #markdownToHtml(CharSequence)} gagne
 * : liens {@code [texte](url)}, images {@code ![alt](url)}, listes
 * <b>ordonnées</b>, <b>listes de tâches</b> ({@code - [ ] / - [x]}),
 * <b>tables</b> GFM, <b>barré</b> ({@code ~~…~~}), titres jusqu'au
 * niveau 6 — toujours sans dépendance externe (assez pour juger le flux,
 * pas assez pour prétendre à CommonMark). Le style passe au registre
 * « GitHub Dark » moderne : police lisible pour la prose, code en carte
 * bordée, citations à barre d'accent, tableaux à en-têtes contrastés.</p>
 *
 * <p><b>Moteur v3 (B7, retour terrain n°4)</b> — le rendu « fit » enfin
 * honnête :</p>
 * <ul>
 *   <li><b>HTML brut</b> : les fichiers {@code .html}/{@code .htm} passent
 *       TELS QUELS au WebView (plus de conversion Markdown qui échappait
 *       les balises — l'échantillon XML du même nom rendait n'importe quoi) ;
 *       la conversion maison ne s'applique qu'aux {@code .md} ;</li>
 *   <li><b>Zoom de texte verrouillé à 100 %</b> : le WebView suit par défaut
 *       l'échelle de police SYSTÈME — un appareil réglé « grand » gonflait
 *       la prose, débordait la carte et cassait l'ajustement ;</li>
 *   <li><b>Mode aperçu</b> ({@code loadWithOverviewMode} + viewport large)
 *       : la page s'ouvre ajustée à la feuille, pas à une largeur fantôme ;</li>
 *   <li><b>Scroll préservé</b> : le rechargement débouncé pendant la frappe
 *       ne remonte plus la feuille en haut à chaque 200 ms.</li>
 * </ul>
 *
 * <p><b>Moteur v4 (B8, retour terrain n°5)</b> — le « fit » du mode PLEIN et
 * le zoom :</p>
 * <ul>
 *   <li><b>Re-rendre au changement de taille</b> ({@code onPreviewModeChanged})
 *       : scindé → plein recharge le payload, sinon le WebView gardait
 *       l'échelle de l'ancienne largeur (« le zoom est trop, pas fit ») ;</li>
 *   <li><b>Pincement actif</b> : contrôles de zoom intégrés sans boutons —
 *       la page se zoome/dézoome au doigt ;</li>
 *   <li><b>Contexte réel</b> : le WebView naît du contexte de l'ÉDITEUR (le
 *       contexte applicatif peut porter des DisplayMetrics étrangères à
 *       l'affichage courant).</li>
 * </ul>
 */
public class DemoPreviewHost implements EditorPreviewHost {

    private WebView webView;
    /** Dernier nom vu via {@link #canPreview} / {@link #onCreatePreviewView} —
     *  pilote le choix HTML brut vs conversion Markdown. */
    private String currentFileName = "";
    /** Dernier contenu vu — sert au re-rendre « fit » quand la feuille
     *  change de taille (B8 : scindé ↔ plein). */
    private CharSequence lastText = null;
    /** Compteur de chargements (observable par le harnais de test). */
    int reloadCount = 0;

    @Override
    public boolean canPreview(String fileName) {
        if (fileName == null) return false;
        // Mémo du type courant — le rendu s'en sert (HTML brut vs Markdown).
        currentFileName = fileName;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    @Override
    public void onPreviewModeChanged(EditorView.PreviewMode mode,
            int previewLeft, int previewWidth) {
        // B8 (retour terrain n°5) : la feuille vient de changer de taille
        // (scindé ↔ plein via le segmented control, ou resize d'activité).
        // Le WebView gardait ALORS son échelle et sa mise en page de
        // l'ANCIENNE largeur — « le zoom est trop, pas fit ». On recharge
        // le payload : overview mode ré-ajuste la page à la NOUVELLE
        // largeur (scroll de lecture préservé par loadInto).
        if (webView != null && lastText != null
                && mode != EditorView.PreviewMode.NONE) {
            loadInto(webView, lastText);
        }
    }

    @Override
    public void onPreviewContentChanged(CharSequence text) {
        lastText = text;
        if (webView != null) {
            loadInto(webView, text);
        }
    }

    @Override
    public void drawPreview(android.graphics.Canvas canvas, float offsetX, float offsetY) {
        // Aperçu basé View (WebView) : rien à dessiner sur le canvas éditeur.
    }

    @Override
    public boolean hasPreviewContent() {
        return false;
    }

    @Override
    public boolean hitTestPreview(float x, float y) {
        return false;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    public View onCreatePreviewView(Context ctx, EditorView editor,
            EditorView.PreviewMode mode) {
        if (webView == null) {
            // B8 : contexte RÉEL de l'éditeur (pas ApplicationContext) — le
            // WebView calcule sa densité/son échelle initiale sur les
            // DisplayMetrics de SON contexte ; le contexte applicatif peut
            // porter des métriques périmées ou étrangères à l'affichage
            // courant (multi-fenêtre, densité modifiée) → « zoom trop ».
            // L'hôte meurt avec l'éditeur qui le porte : aucune fuite.
            webView = new WebView(ctx);
            webView.getSettings().setJavaScriptEnabled(false);
            // B7 « fit » : verrouille le zoom de texte — le WebView suit
            // sinon l'échelle de police SYSTÈME et un appareil réglé
            // « grand » gonflait la prose hors de la feuille.
            webView.getSettings().setTextZoom(100);
            // B8 (retour terrain n°5) : le pincement zoome/dézoome la page
            // (« je ne peux pas zoom ou dezoomer ») — contrôles intégrés
            // SANS les boutons +/- qui polluent la carte flottante.
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDisplayZoomControls(false);
            // B7 « fit » : la page s'ouvre ajustée à la largeur réelle de
            // la feuille (pas à une largeur fantôme puis dézoomée).
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.setBackgroundColor(0xFF0D1117);
        }
        // Mémo du nom depuis l'éditeur (canPreview peut ne pas avoir été
        // rappelé pour CE fichier après un changement d'échantillon).
        String name = editor.getFileName();
        if (name != null) currentFileName = name;
        if (editor.getSession() != null) {
            CharSequence text = editor.getSession().getText();
            lastText = text;
            loadInto(webView, text);
        }
        return webView;
    }

    /**
     * Charge le texte dans le WebView cible selon le type de fichier
     * courant, en préservant la position de lecture : le rechargement
     * débouncé (frappe en scindé) ne doit PAS remettre la feuille en
     * haut toutes les 200 ms.
     */
    private void loadInto(WebView target, CharSequence text) {
        final int saveX = target.getScrollX();
        final int saveY = target.getScrollY();
        target.loadDataWithBaseURL(null, payloadFor(currentFileName, text),
                "text/html", "utf-8", null);
        reloadCount++;
        if (saveX != 0 || saveY != 0) {
            target.post(() -> target.scrollTo(saveX, saveY));
        }
    }

    /**
     * B7 — le corps envoyé au WebView selon le type de fichier : les
     * {@code .html}/{@code .htm} passent BRUTS (leurs propres styles),
     * tout le reste file dans l'enveloppe Markdown de la démo.
     * Statique et pure — le routage est testable sans WebView.
     */
    static String payloadFor(String fileName, CharSequence text) {
        String lower = fileName == null
                ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return text.toString();
        }
        return wrapHtml(text);
    }

    /** Enveloppe le Markdown rendu dans la feuille sombre de la démo (le nom
     *  de fichier est déjà porté par l'en-tête de la feuille). */
    private static String wrapHtml(CharSequence text) {
        String body = markdownToHtml(text);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\">"
                + "<style>" + CSS + "</style></head><body>"
                + body + "</body></html>";
    }

    /**
     * Feuille de style « GitHub Dark » moderne (B6) — prose en police
     * système lisible, code en cartes bordées, citations à barre d'accent,
     * tâches à cases cochées, tableaux contrastés.
     */
    private static final String CSS =
            ":root{color-scheme:dark}"
            + "body{background:#0d1117;color:#e6edf3;margin:0;"
            + "padding:16px 18px 26px;font:15px/1.65 -apple-system,"
            + "'Segoe UI',Roboto,'Helvetica Neue',sans-serif;"
            + "overflow-wrap:break-word;-webkit-text-size-adjust:100%}"
            + "h1,h2,h3,h4,h5,h6{color:#f0f6fc;line-height:1.25;"
            + "margin:1.05em 0 .4em}"
            + "h1{font-size:1.55em;border-bottom:1px solid #21262d;"
            + "padding-bottom:.3em}"
            + "h2{font-size:1.3em;border-bottom:1px solid #21262d;"
            + "padding-bottom:.25em}"
            + "h3{font-size:1.12em}h4{font-size:1em;color:#c9d1d9}"
            + "p{margin:.5em 0}"
            + "a{color:#58a6ff;text-decoration:none}a:active{color:#79c0ff}"
            + "strong{color:#f0f6fc}del{color:#8b949e}"
            + "code{font:12.5px/1.5 Menlo,Consolas,'JetBrains Mono',"
            + "monospace;background:#1c2128;color:#f0c674;"
            + "padding:2px 5px;border-radius:5px;border:1px solid #2d333b}"
            + "pre{background:#161b22;border:1px solid #21262d;"
            + "border-radius:9px;padding:11px 13px;overflow-x:auto;"
            + "margin:.7em 0}"
            + "pre code{background:none;border:0;padding:0;color:#e6edf3}"
            + "blockquote{border-left:3px solid #388bfd80;margin:.6em 0;"
            + "padding:2px 0 2px 12px;color:#a9b1ba}"
            + "ul,ol{padding-left:1.35em;margin:.5em 0}li{margin:.26em 0}"
            + ".task{display:inline-block;width:13px;height:13px;"
            + "border:1.5px solid #6e7681;border-radius:3px;"
            + "margin-right:7px;vertical-align:-2px}"
            + ".task.done{background:#2ea04340;border-color:#2ea043}"
            + "hr{border:0;border-top:1px solid #21262d;margin:1.15em 0}"
            + "table{border-collapse:collapse;margin:.7em 0;max-width:100%}"
            + "th,td{border:1px solid #21262d;padding:6px 10px;"
            + "text-align:left}"
            + "th{background:#161b22;color:#f0f6fc}"
            + "img{max-width:100%;border-radius:8px}";

    // ════════════════════════════════════════════════════════════════
    // Conversion Markdown → HTML (v2 B6)
    // ════════════════════════════════════════════════════════════════

    /** Lien [texte](url) — texte non vide, url sans espace. */
    private static final Pattern LINK =
            Pattern.compile("\\[([^\\]\\n]+)\\]\\(([^)\\s]+)[^)]*\\)");
    /** Image ![alt](url). */
    private static final Pattern IMAGE =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)");
    /** Ligne de tâche : « - [x] reste » / « * [ ] à faire ». */
    private static final Pattern TASK =
            Pattern.compile("^([-*+]) \\[([ xX])\\] (.*)$");
    /** Marqueur de liste non ordonnée : « - », « * », « + ». */
    private static final Pattern UL =
            Pattern.compile("^([-*+]) (.*)$");
    /** Marqueur de liste ordonnée : « 1. » / « 3) ». */
    private static final Pattern OL =
            Pattern.compile("^(\\d+)[.)] (.*)$");

    /**
     * Conversion Markdown → HTML maison (v2) : échappe d'abord le HTML,
     * puis blocs de code (``` … ```), titres (# … ######), règles
     * horizontales, citations (&gt;), listes non ordonnées / ordonnées /
     * de tâches, tables GFM, paragraphes ; en ligne : code (`…`), gras
     * (**…**), italique (*…* / _…_), barré (~~…~~), liens et images.
     */
    static String markdownToHtml(CharSequence src) {
        if (src == null) return "";
        List<String> lines = new ArrayList<>();
        for (String l : src.toString().split("\n", -1)) lines.add(l);
        StringBuilder out = new StringBuilder();
        Blocks blocks = new Blocks();
        boolean inCode = false;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.trim();

            // Bloc de code fence.
            if (trimmed.startsWith("```")) {
                blocks.close(out);
                if (inCode) {
                    out.append("</code></pre>");
                } else {
                    out.append("<pre><code>");
                }
                inCode = !inCode;
                i++;
                continue;
            }
            if (inCode) {
                out.append(escapeHtml(line)).append('\n');
                i++;
                continue;
            }

            // Règle horizontale.
            if (trimmed.matches("(-{3,}|\\*{3,}|_{3,})")) {
                blocks.close(out);
                out.append("<hr>");
                i++;
                continue;
            }

            // Table GFM — en-tête + séparateur |---|---| + lignes.
            if (trimmed.startsWith("|") && i + 1 < lines.size()
                    && isTableSeparator(lines.get(i + 1))) {
                blocks.close(out);
                renderTable(out, lines, i);
                // Saute l'en-tête + le séparateur + les lignes de corps.
                i += 2;
                while (i < lines.size() && lines.get(i).trim().startsWith("|")) i++;
                continue;
            }

            // Titres (niveaux 1 à 6).
            int level = headingLevel(trimmed);
            if (level > 0) {
                blocks.close(out);
                out.append("<h").append(level).append(">")
                        .append(inline(trimmed.substring(level + 1)))
                        .append("</h").append(level).append(">");
                i++;
                continue;
            }

            // Citation.
            if (trimmed.startsWith(">")) {
                if (!blocks.quote) {
                    blocks.openQuote(out);
                }
                out.append(inline(trimmed.substring(1).trim()))
                        .append("<br>");
                i++;
                continue;
            }
            if (blocks.quote) {
                out.append("</blockquote>");
                blocks.quote = false;
            }

            // Listes de tâches / non ordonnées / ordonnées.
            Matcher task = TASK.matcher(trimmed);
            if (task.matches()) {
                blocks.openUl(out);
                boolean done = !task.group(2).trim().isEmpty();
                out.append("<li>").append(done ? TASK_DONE : TASK_TODO)
                        .append(inline(task.group(3))).append("</li>");
                i++;
                continue;
            }
            Matcher ul = UL.matcher(trimmed);
            if (ul.matches()) {
                blocks.openUl(out);
                out.append("<li>").append(inline(ul.group(2))).append("</li>");
                i++;
                continue;
            }
            Matcher ol = OL.matcher(trimmed);
            if (ol.matches()) {
                blocks.openOl(out);
                out.append("<li>").append(inline(ol.group(2))).append("</li>");
                i++;
                continue;
            }
            blocks.close(out);

            if (trimmed.isEmpty()) {
                // Ligne vide : respiration, pas de <br> en cascade.
                i++;
                continue;
            }
            out.append("<p>").append(inline(line)).append("</p>");
            i++;
        }
        blocks.close(out);
        if (inCode) out.append("</code></pre>");
        return out.toString();
    }

    private static final String TASK_TODO = "<span class=\"task\"></span>";
    private static final String TASK_DONE = "<span class=\"task done\"></span>";

    /** État des blocs ouverts pendant UNE conversion (local — pas d'état
     *  partagé entre appels, conversions indépendantes et testables). */
    private static final class Blocks {
        boolean ul, ol, quote;

        void close(StringBuilder out) {
            if (ul) {
                out.append("</ul>");
                ul = false;
            }
            if (ol) {
                out.append("</ol>");
                ol = false;
            }
            if (quote) {
                out.append("</blockquote>");
                quote = false;
            }
        }

        void openUl(StringBuilder out) {
            if (ol) {
                out.append("</ol>");
                ol = false;
            }
            if (!ul) {
                out.append("<ul>");
                ul = true;
            }
        }

        void openOl(StringBuilder out) {
            if (ul) {
                out.append("</ul>");
                ul = false;
            }
            if (!ol) {
                out.append("<ol>");
                ol = true;
            }
        }

        void openQuote(StringBuilder out) {
            if (ul) {
                out.append("</ul>");
                ul = false;
            }
            if (ol) {
                out.append("</ol>");
                ol = false;
            }
            if (!quote) {
                out.append("<blockquote>");
                quote = true;
            }
        }
    }

    /** Niveau du titre (1 à 6), 0 si la ligne n'est pas un titre. */
    private static int headingLevel(String trimmed) {
        for (int level = 1; level <= 6; level++) {
            if (trimmed.startsWith("#".repeat(level) + " ")) {
                return level;
            }
        }
        return 0;
    }

    /** Ligne séparateur de table GFM : |, |-, |:-:|, | --- | :--- |. */
    private static boolean isTableSeparator(String line) {
        String t = line.trim();
        if (t.isEmpty() || !t.contains("-")) return false;
        return t.matches("\\|?[\\s:|-]+\\|?");
    }

    /** Rend une table GFM depuis la ligne d'en-tête (index i). */
    private static void renderTable(StringBuilder out, List<String> lines, int i) {
        out.append("<table><thead><tr>");
        for (String cell : splitRow(lines.get(i))) {
            out.append("<th>").append(inline(cell)).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        for (int r = i + 2; r < lines.size() && lines.get(r).trim().startsWith("|"); r++) {
            out.append("<tr>");
            for (String cell : splitRow(lines.get(r))) {
                out.append("<td>").append(inline(cell)).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table>");
    }

    /** Découpe « | a | b | » en cellules (bordures optionnelles retirées). */
    private static List<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : t.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    /**
     * Échappement HTML, puis marques en ligne : images et liens (regex,
     * AVANT le scanner), code, gras, italique, barré — le scanner ignore
     * l'intérieur des balises HTML déjà posées (les URLs à tiret bas n'y
     * sont jamais prises pour de l'italique).
     */
    private static String inline(String s) {
        String e = escapeHtml(s.trim());
        // Images d'abord (sinon le lien capture le ! alt ]( …).
        e = IMAGE.matcher(e).replaceAll("<img src=\"$2\" alt=\"$1\">");
        e = LINK.matcher(e).replaceAll("<a href=\"$2\">$1</a>");

        StringBuilder out = new StringBuilder();
        char[] c = e.toCharArray();
        int i = 0;
        while (i < c.length) {
            // Balise HTML déjà posée (a/img/span) : copiée telle quelle —
            // son href/alt n'est pas du markdown à interpréter.
            if (c[i] == '<') {
                int end = e.indexOf('>', i);
                if (end > i) {
                    out.append(c, i, end - i + 1);
                    i = end + 1;
                    continue;
                }
            }
            // Code en ligne.
            if (c[i] == '`') {
                int end = e.indexOf('`', i + 1);
                if (end > i) {
                    out.append("<code>").append(e, i + 1, end).append("</code>");
                    i = end + 1;
                    continue;
                }
            }
            // Gras.
            if (i + 1 < c.length && c[i] == '*' && c[i + 1] == '*') {
                int end = e.indexOf("**", i + 2);
                if (end > i + 1) {
                    out.append("<b>").append(e, i + 2, end).append("</b>");
                    i = end + 2;
                    continue;
                }
            }
            // Barré.
            if (i + 1 < c.length && c[i] == '~' && c[i + 1] == '~') {
                int end = e.indexOf("~~", i + 2);
                if (end > i + 1) {
                    out.append("<del>").append(e, i + 2, end).append("</del>");
                    i = end + 2;
                    continue;
                }
            }
            // Italique.
            if (c[i] == '*' || c[i] == '_') {
                char mark = c[i];
                int end = e.indexOf(mark, i + 1);
                if (end > i + 1) {
                    out.append("<i>").append(e, i + 1, end).append("</i>");
                    i = end + 1;
                    continue;
                }
            }
            out.append(c[i]);
            i++;
        }
        return out.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package jo.codeeditor.demo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.webkit.WebView;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.preview.EditorPreviewHost;

/**
 * Hôte d'aperçu de la démo (B5 — retour terrain post-J6) : rend les
 * échantillons <b>Markdown</b> dans la feuille d'aperçu de l'éditeur
 * (WebView, conversion HTML minimaliste). C'est la preuve APK que
 * l'action « œil » de la barre de fil d'Ariane ouvre bel et bien un
 * aperçu vivant — la feuille scindée flotte sur l'éditeur, son en-tête
 * bascule plein écran, et le contenu se re-rend en frappant (débounce
 * ~200 ms côté bibliothèque).
 *
 * <p>Le HTML est généré par {@link #markdownToHtml(CharSequence)} :
 * titres, gras, italique, code en ligne et blocs, listes, citations et
 * règles horizontales — assez pour juger le flux, pas assez pour prétendre
 * à CommonMark. Les fichiers {@code .html} bruts passent tels quels.</p>
 */
public class DemoPreviewHost implements EditorPreviewHost {

    private WebView webView;

    @Override
    public boolean canPreview(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    @Override
    public void onPreviewModeChanged(EditorView.PreviewMode mode,
            int previewLeft, int previewWidth) {
        // La feuille popup de la bibliothèque gère sa surface ; le WebView
        // vit dans son corps (onCreatePreviewView).
    }

    @Override
    public void onPreviewContentChanged(CharSequence text) {
        if (webView != null) {
            webView.loadDataWithBaseURL(null, wrapHtml(text),
                    "text/html", "utf-8", null);
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
            webView = new WebView(ctx.getApplicationContext());
            webView.getSettings().setJavaScriptEnabled(false);
            webView.setBackgroundColor(0xFF111318);
        }
        if (editor.getSession() != null) {
            webView.loadDataWithBaseURL(null, wrapHtml(editor.getSession().getText()),
                    "text/html", "utf-8", null);
        }
        return webView;
    }

    /** Enveloppe le HTML rendu dans la feuille sombre de la démo (le nom
     *  de fichier est déjà porté par l'en-tête de la feuille). */
    private String wrapHtml(CharSequence text) {
        String body = markdownToHtml(text);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\">"
                + "<style>body{background:#111318;color:#d4d4d4;"
                + "font-family:monospace;padding:12px;line-height:1.45}"
                + "h1,h2,h3{color:#e4e4e7}code{color:#f0c674;"
                + "background:#1e2027;padding:1px 4px;border-radius:3px}"
                + "pre{background:#1e2027;padding:8px;border-radius:6px;"
                + "overflow-x:auto}pre code{background:transparent;padding:0}"
                + "blockquote{border-left:3px solid #3a3d46;margin:0;"
                + "padding-left:10px;color:#858585}a{color:#78a9ff}"
                + "hr{border:0;border-top:1px solid #2a2d35}"
                + "li{margin:2px 0}</style></head><body>"
                + body + "</body></html>";
    }

    /**
     * Conversion Markdown → HTML minimaliste : échappe d'abord le HTML,
     * puis titres (#/##/###), blocs de code (``` … ```), code en ligne
     * (`…`), gras (**…**), italique (*…* / _…_), listes (- / *),
     * citations (&gt;) et règles horizontales (---).
     */
    static String markdownToHtml(CharSequence src) {
        if (src == null) return "";
        String[] lines = src.toString().split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        boolean inList = false;
        boolean inQuote = false;
        for (String raw : lines) {
            String line = raw;

            // Bloc de code fence.
            if (line.trim().startsWith("```")) {
                closeBlocks(out, inList, inQuote);
                inList = false;
                inQuote = false;
                if (inCode) {
                    out.append("</code></pre>");
                } else {
                    out.append("<pre><code>");
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                out.append(escapeHtml(line)).append('\n');
                continue;
            }

            // Règle horizontale.
            if (line.trim().matches("(-{3,}|\\*{3,}|_{3,})")) {
                closeBlocks(out, inList, inQuote);
                inList = false;
                inQuote = false;
                out.append("<hr>");
                continue;
            }

            // Citation.
            boolean quote = line.trim().startsWith(">");
            if (quote) {
                if (!inQuote) {
                    closeBlocks(out, inList, false);
                    inList = false;
                    out.append("<blockquote>");
                }
                inQuote = true;
                out.append(inline(line.trim().substring(1).trim()))
                        .append("<br>");
                continue;
            }
            if (inQuote) {
                out.append("</blockquote>");
                inQuote = false;
            }

            // Liste.
            boolean item = line.trim().startsWith("- ") || line.trim().startsWith("* ");
            if (item) {
                if (!inList) {
                    out.append("<ul>");
                    inList = true;
                }
                out.append("<li>").append(inline(line.trim().substring(2)))
                        .append("</li>");
                continue;
            }
            if (inList) {
                out.append("</ul>");
                inList = false;
            }

            // Titres.
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                out.append("<h3>").append(inline(trimmed.substring(4))).append("</h3>");
            } else if (trimmed.startsWith("## ")) {
                out.append("<h2>").append(inline(trimmed.substring(3))).append("</h2>");
            } else if (trimmed.startsWith("# ")) {
                out.append("<h1>").append(inline(trimmed.substring(2))).append("</h1>");
            } else if (trimmed.isEmpty()) {
                // Ligne vide : respiration, pas de <br> en cascade.
            } else {
                out.append("<p>").append(inline(line)).append("</p>");
            }
        }
        closeBlocks(out, inList, inQuote);
        if (inCode) out.append("</code></pre>");
        return out.toString();
    }

    private static void closeBlocks(StringBuilder out, boolean inList, boolean inQuote) {
        if (inList) out.append("</ul>");
        if (inQuote) out.append("</blockquote>");
    }

    /** Échappement HTML puis marques en ligne (code, gras, italique). */
    private static String inline(String s) {
        String e = escapeHtml(s.trim());
        StringBuilder out = new StringBuilder();
        char[] c = e.toCharArray();
        int i = 0;
        while (i < c.length) {
            if (c[i] == '`') {
                int end = e.indexOf('`', i + 1);
                if (end > i) {
                    out.append("<code>").append(e, i + 1, end).append("</code>");
                    i = end + 1;
                    continue;
                }
            }
            if (i + 1 < c.length && c[i] == '*' && c[i + 1] == '*') {
                int end = e.indexOf("**", i + 2);
                if (end > i + 1) {
                    out.append("<b>").append(e, i + 2, end).append("</b>");
                    i = end + 2;
                    continue;
                }
            }
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

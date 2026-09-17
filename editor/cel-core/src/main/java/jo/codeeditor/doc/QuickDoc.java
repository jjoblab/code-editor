package jo.codeeditor.doc;

import java.util.*;

/**
 * Analyse de documentation rapide : extraction de contenu Javadoc/KDoc.
 * Analyse les commentaires de doc en contenu structuré avec description
 * et sections. Gère {@code}, {@link}, les balises HTML et le Markdown.
 * Reprend le design du {@code QuickDoc.kt} de CodeAssist.
 */
public class QuickDoc {

    // ── Types de données ──────────────────────────────────────────

    /** Une section d'un commentaire de doc (ex. @param, @return, @throws). */
    public static final class DocSection {
        public final String title;
        public final List<String> items;

        public DocSection(String title, List<String> items) {
            this.title = title != null ? title : "";
            this.items = items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
        }

        public DocSection(String title) {
            this(title, new ArrayList<>());
        }

        @Override
        public String toString() {
            return "DocSection(\"" + title + "\", items=" + items.size() + ")";
        }
    }

    /** Contenu de doc rapide analysé. */
    public static final class QuickDocContent {
        /**
         * Contenu des fences ``` (typiquement la signature exacte
         * renvoyée par le serveur hover LSP). Rendu en tête du popup
         * en monospace sur fond teinté (motif QuickDocPopup de
         * CodeAssist). Vide quand il n'y a pas de fence.
         */
        public final String signature;
        public final String description;
        public final List<DocSection> sections;

        public QuickDocContent(String signature, String description, List<DocSection> sections) {
            this.signature = signature != null ? signature : "";
            this.description = description != null ? description : "";
            this.sections = sections != null ? Collections.unmodifiableList(sections) : Collections.emptyList();
        }

        public QuickDocContent(String description, List<DocSection> sections) {
            this("", description, sections);
        }

        public QuickDocContent(String description) {
            this("", description, new ArrayList<>());
        }

        /** Renvoie true si ce contenu de doc est vide. */
        public boolean isEmpty() {
            return signature.isEmpty() && description.isEmpty() && sections.isEmpty();
        }

        @Override
        public String toString() {
            return "QuickDocContent(\"" + description.substring(0, Math.min(40, description.length()))
                + "...\", sections=" + sections.size() + ")";
        }
    }

    // ── Analyse ───────────────────────────────────────────────────

    /**
     * Analyse un commentaire Javadoc/KDoc en contenu structuré.
     *
     * @param doc       le texte de doc brut (avec les marqueurs de commentaire)
     * @param codeStyle "java" ou "kotlin"
     * @return le contenu analysé
     */
    public static QuickDocContent parseQuickDoc(String doc, String codeStyle) {
        if (doc == null || doc.isEmpty()) return new QuickDocContent("");

        // Retire les marqueurs de doc
        String stripped = stripDocMarkers(doc);

        // Extraire les fences ``` (le serveur hover LSP envoie la
        // signature exacte dans un fence ```java … ```). Double bénéfice :
        // le fence devient l'en-tête signature du popup ET son contenu est
        // retiré AVANT la détection des tags (une annotation @Override dans
        // une signature ne crée plus une section parasite).
        StringBuilder fences = new StringBuilder();
        stripped = extractCodeFences(stripped, fences);

        // Sépare description et sections de tags
        int tagStart = findFirstTag(stripped);
        String description;
        String tagBlock;

        if (tagStart >= 0) {
            description = stripped.substring(0, tagStart).trim();
            tagBlock = stripped.substring(tagStart);
        } else {
            description = stripped.trim();
            tagBlock = "";
        }

        // Traite le markup en ligne dans la description
        description = inlineMarkup(description);

        // Analyse les sections de tags
        List<DocSection> sections = parseTags(tagBlock);

        return new QuickDocContent(fences.toString().trim(), description, sections);
    }

    /**
     * Retire les fences ``` (avec langage optionnel) du texte et
     * collecte leur contenu dans {@code out} (lignes conservées,
     * multi-fences possibles). Un fence non fermé est toléré (tout le reste
     * du texte est le contenu — le serveur ne devrait jamais l'envoyer,
     * mais un document partiel ne doit pas crasher le parseur).
     */
    static String extractCodeFences(String text, StringBuilder out) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inFence = false;
        boolean fenceJustClosed = false;
        int fenceContentLines = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inFence && trimmed.startsWith("```")) {
                inFence = true;
                fenceJustClosed = false;
                fenceContentLines = 0;
                continue;
            }
            if (inFence && trimmed.equals("```")) {
                inFence = false;
                fenceJustClosed = true;
                if (out.length() > 0) out.append('\n');
                continue;
            }
            if (inFence) {
                // Séparateur de ligne à l'intérieur d'un fence multi-lignes.
                if (fenceContentLines > 0) out.append('\n');
                out.append(line.trim());
                fenceContentLines++;
            } else {
                // Compresse les trous laissés par les fences retirées.
                if (fenceJustClosed && trimmed.isEmpty()) {
                    fenceJustClosed = false;
                    continue;
                }
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Retire les marqueurs de commentaire de doc du texte.
     *
     * @param doc texte de doc brut
     * @return texte sans les marqueurs
     */
    public static String stripDocMarkers(String doc) {
        if (doc == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = doc.split("\n", -1);
        boolean first = true;

        for (String line : lines) {
            String trimmed = line.trim();

            // Retire le /** de tête
            if (first && trimmed.startsWith("/**")) {
                trimmed = trimmed.substring(3);
                first = false;
            } else if (first && trimmed.startsWith("/*")) {
                trimmed = trimmed.substring(2);
                first = false;
            } else {
                first = false;
            }

            // Retire le */ final
            if (trimmed.endsWith("*/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 2);
            }

            // Retire le * de tête
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1);
                if (trimmed.startsWith(" ")) {
                    trimmed = trimmed.substring(1);
                }
            }

            if (sb.length() > 0) sb.append('\n');
            sb.append(trimmed);
        }

        return sb.toString().trim();
    }

    /**
     * Traite le markup en ligne : {@code}, {@link}, balises HTML, Markdown.
     *
     * @param text texte avec markup en ligne
     * @return texte avec le markup traité
     */
    public static String inlineMarkup(String text) {
        if (text == null) return "";

        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            // {@code text}
            if (text.startsWith("{@code ", i) || text.startsWith("{@code\t", i)) {
                int end = text.indexOf('}', i);
                if (end >= 0) {
                    String code = text.substring(i + 6, end).trim();
                    sb.append("`").append(code).append("`");
                    i = end + 1;
                    continue;
                }
            }

            // {@link target} ou {@linkplain target}
            if (text.startsWith("{@link ", i) || text.startsWith("{@linkplain ", i)) {
                int end = text.indexOf('}', i);
                if (end >= 0) {
                    // {@link  fait 7 caractères, {@linkplain  en fait 12 —
                    // contentStart doit pointer juste après l'espace.
                    int contentStart = text.startsWith("{@link ", i) ? i + 7 : i + 12;
                    String link = text.substring(contentStart, end).trim();
                    // Extrait le texte affichable si « target#method display »
                    int spaceIdx = link.indexOf(' ');
                    String display = spaceIdx >= 0 ? link.substring(spaceIdx + 1) : link;
                    sb.append(display);
                    i = end + 1;
                    continue;
                }
            }

            // {@literal text}
            if (text.startsWith("{@literal ", i)) {
                int end = text.indexOf('}', i);
                if (end >= 0) {
                    sb.append(text, i + 10, end);
                    i = end + 1;
                    continue;
                }
            }

            // {@value}
            if (text.startsWith("{@value}", i)) {
                sb.append("(value)");
                i += 8;
                continue;
            }

            // Balises HTML : <p>, <br>, <ul>, <li>, <pre>, <code>
            if (text.charAt(i) == '<') {
                int close = text.indexOf('>', i);
                if (close >= 0) {
                    String tag = text.substring(i + 1, close).trim().toLowerCase(java.util.Locale.ROOT);
                    if (tag.equals("p") || tag.equals("/p")) {
                        sb.append("\n\n");
                    } else if (tag.equals("br") || tag.equals("br/")) {
                        sb.append("\n");
                    } else if (tag.equals("li")) {
                        sb.append("\n• ");
                    } else if (tag.equals("pre") || tag.equals("code")) {
                        sb.append("`");
                    } else if (tag.equals("/pre") || tag.equals("/code")) {
                        sb.append("`");
                    } else if (tag.equals("ul") || tag.equals("/ul") || tag.equals("ol") || tag.equals("/ol")) {
                        sb.append("\n");
                    } else if (tag.startsWith("/")) {
                        // Ignore les autres balises fermantes
                    }
                    // Ignore les autres balises ouvrantes
                    i = close + 1;
                    continue;
                }
            }

            // Markdown gras : **texte**
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int close = text.indexOf("**", i + 2);
                if (close >= 0) {
                    sb.append(text, i + 2, close);
                    i = close + 2;
                    continue;
                }
            }

            // Markdown code en ligne : `texte`
            if (text.charAt(i) == '`') {
                int close = text.indexOf('`', i + 1);
                if (close >= 0) {
                    sb.append(text, i, close + 1);
                    i = close + 1;
                    continue;
                }
            }

            sb.append(text.charAt(i));
            i++;
        }

        return sb.toString().trim();
    }

    /**
     * Analyse les sections @param, @return, @throws, @see, @since d'un bloc de tags.
     *
     * @param tagBlock texte commençant au premier tag @
     * @return liste des sections analysées
     */
    public static List<DocSection> parseTags(String tagBlock) {
        if (tagBlock == null || tagBlock.isEmpty()) return Collections.emptyList();

        List<DocSection> sections = new ArrayList<>();
        Map<String, List<String>> tagItems = new LinkedHashMap<>();

        String[] lines = tagBlock.split("\n", -1);
        String currentTag = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            // Cherche un tag @
            int tagIdx = findTagStart(trimmed);
            if (tagIdx >= 0) {
                // Sauvegarde le contenu du tag précédent
                if (currentTag != null) {
                    tagItems.computeIfAbsent(currentTag, k -> new ArrayList<>())
                        .add(currentContent.toString().trim());
                }

                // Analyse le nouveau tag
                int spaceIdx = trimmed.indexOf(' ', tagIdx);
                if (spaceIdx >= 0) {
                    currentTag = trimmed.substring(tagIdx, spaceIdx).trim();
                    currentContent = new StringBuilder(trimmed.substring(spaceIdx + 1).trim());
                } else {
                    currentTag = trimmed.substring(tagIdx).trim();
                    currentContent = new StringBuilder();
                }
            } else {
                // Suite du tag courant
                if (currentContent.length() > 0) currentContent.append(' ');
                currentContent.append(trimmed);
            }
        }

        // Sauvegarde le dernier tag
        if (currentTag != null) {
            tagItems.computeIfAbsent(currentTag, k -> new ArrayList<>())
                .add(currentContent.toString().trim());
        }

        // Construit les sections
        for (Map.Entry<String, List<String>> entry : tagItems.entrySet()) {
            List<String> processed = new ArrayList<>();
            for (String item : entry.getValue()) {
                processed.add(inlineMarkup(item));
            }
            sections.add(new DocSection(entry.getKey(), processed));
        }

        return sections;
    }

    // ── Utilitaires ───────────────────────────────────────────────

    /**
     * Trouve le premier tag @ dans le texte.
     */
    private static int findFirstTag(String text) {
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '@' && (i == 0 || text.charAt(i - 1) == '\n' || text.charAt(i - 1) == ' ')) {
                // Vérifie que cela ressemble à un tag (lettre après @)
                if (i + 1 < text.length() && Character.isLetter(text.charAt(i + 1))) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /**
     * Trouve le début d'un tag @ en début de ligne.
     */
    private static int findTagStart(String line) {
        if (line.isEmpty()) return -1;
        if (line.charAt(0) == '@' && line.length() > 1 && Character.isLetter(line.charAt(1))) {
            return 0;
        }
        return -1;
    }

    /**
     * Retire les marqueurs de doc d'un commentaire KDoc Kotlin.
     */
    public static String stripKDocMarkers(String doc) {
        // Le KDoc utilise les mêmes marqueurs /** */
        return stripDocMarkers(doc);
    }
}

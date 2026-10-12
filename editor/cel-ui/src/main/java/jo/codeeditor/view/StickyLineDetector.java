package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

import java.util.ArrayList;
import java.util.List;

/**
 * Détecteur de lignes sticky (en-têtes de bloc de code qui restent
 * visibles en haut pendant le scroll). Inspiré d'les IDE de bureau 2024.x.
 *
 * <p>Une ligne sticky est une ligne qui ouvre un bloc de code et
 * contient un identifiant significatif (nom de classe, méthode,
 * fonction). Quand cette ligne est scrollée hors du viewport, elle
 * est redessinée en haut (pinned) pour garder le contexte.</p>
 *
 * <p><b>Détection</b> : les lignes sticky sont les lignes qui
 * contiennent une accolade ouvrante {@code {} (C_LIKE) ou un
 * deux-points {@code :} (Python) ET qui commencent par un mot-clé
 * de définition de bloc :</p>
 * <ul>
 *   <li><b>C_LIKE</b> : {@code class}, {@code interface},
 *       {@code enum}, {@code public}, {@code private},
 *       {@code protected}, {@code static}, {@code fun},
 *       {@code void}, {@code int}, {@code def} (Groovy),
 *       {@code func} (Swift/Go), {@code fn} (Rust/Dart),
 *       {@code function} (JS/TS), {@code def} (Python si C_LIKE)</li>
 *   <li><b>Python</b> : {@code def}, {@code class},
 *       {@code if}, {@code for}, {@code while}, {@code with},
 *       {@code try}, {@code except}, {@code else}, {@code finally}</li>
 * </ul>
 *
 * <p><b>Limitation mobile</b> : au plus 2 lignes sticky simultanées
 * (classe + méthode) pour ne pas consommer trop d'espace sur petit
 * écran.</p>
 *
 * @author jjoblab
 */
final class StickyLineDetector {

    private final EditorView view;

    StickyLineDetector(EditorView view) {
        this.view = view;
    }

    /**
     * Détecte les lignes sticky pour le document courant.
     *
     * @param doc le document
     * @return liste de {@code [docLine, yOffset]} — les lignes sticky
     *         triées par position (haut → bas). {@code yOffset} est la
     *         position Y de la ligne dans l'espace contenu (avant
     *         vOffset).
     */
    List<int[]> detect(EditorDocument doc) {
        List<int[]> stickyLines = new ArrayList<>();
        if (view.session == null || doc == null) return stickyLines;

        LanguageProfile profile = view.session.getLanguageProfile();
        if (profile == null) return stickyLines;
        boolean isPython = profile.family == SyntaxFamily.PYTHON;
        boolean isCLike = profile.family == SyntaxFamily.C_LIKE;
        if (!isPython && !isCLike) return stickyLines;

        CharSequence chars = doc.charSequence();
        int lineCount = doc.lineCount();

        for (int li = 0; li < lineCount; li++) {
            int lineStart = doc.lineStart(li);
            int lineEnd = doc.lineEnd(li);
            if (lineEnd - lineStart > 200) continue; // ligne trop longue, skip

            String lineText = chars.subSequence(lineStart, lineEnd).toString();
            String trimmed = lineText.trim();
            if (trimmed.isEmpty()) continue;

            boolean isSticky = false;
            if (isCLike) {
                // C_LIKE : ligne avec { qui commence par un mot-clé
                // de définition de bloc.
                if (lineText.contains("{") && isBlockHeaderCLike(trimmed)) {
                    isSticky = true;
                }
            } else if (isPython) {
                // Python : ligne avec : qui commence par un
                // mot-clé de définition de bloc.
                if (trimmed.endsWith(":") && isBlockHeaderPython(trimmed)) {
                    isSticky = true;
                }
            }

            if (isSticky) {
                float y = view.docLineToY(li);
                stickyLines.add(new int[]{li, (int) y});
            }
        }
        return stickyLines;
    }

    private static boolean isBlockHeaderCLike(String trimmed) {
        // En-têtes de classe/méthode en C_LIKE.
        String[] keywords = {
            "class ", "interface ", "enum ", "public ", "private ",
            "protected ", "static ", "fun ", "void ", "int ", "long ",
            "double ", "float ", "boolean ", "char ", "byte ", "short ",
            "def ", "func ", "fn ", "function ", "var ", "val ",
            "async ", "suspend ", "override ", "open ", "abstract ",
            "final ", "internal ", "const ", "let ", "export ",
            "@interface "
        };
        for (String kw : keywords) {
            if (trimmed.startsWith(kw)) return true;
        }
        return false;
    }

    private static boolean isBlockHeaderPython(String trimmed) {
        // En-têtes de def/class/if/for/while/with/try/except/else/finally.
        String[] keywords = {
            "def ", "class ", "if ", "elif ", "for ", "while ",
            "with ", "try:", "except ", "else:", "finally:",
            "async def "
        };
        for (String kw : keywords) {
            if (trimmed.startsWith(kw)) return true;
        }
        return false;
    }
}

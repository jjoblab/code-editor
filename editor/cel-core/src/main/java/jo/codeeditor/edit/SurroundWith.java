package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

/**
 * Code surround with — inspiré d'les IDE de bureau (Ctrl+Alt+T).
 *
 * <p>Entoure une sélection avec une structure de contrôle :</p>
 * <ul>
 *   <li>{@code if} → {@code if (expr) { selection }}</li>
 *   <li>{@code try} → {@code try { selection } catch (e) { }}</li>
 *   <li>{@code for} → {@code for (item : collection) { selection }}</li>
 *   <li>{@code while} → {@code while (expr) { selection }}</li>
 * </ul>
 *
 * @author jjoblab
 */
final class SurroundWith {

    private SurroundWith() {}

    static final class Result {
        final int start;
        final int end;
        final String insertText;
        final int caret;

        Result(int start, int end, String insertText, int caret) {
            this.start = start;
            this.end = end;
            this.insertText = insertText;
            this.caret = caret;
        }
    }

    /**
     * Entoure la sélection [selStart, selEnd) avec la structure
     * demandée.
     *
     * @param text le texte du document
     * @param selStart début de la sélection
     * @param selEnd fin de la sélection
     * @param type le type de structure ("if", "try", "for", "while")
     * @param language l'identifiant de langage
     * @param indent l'indentation courante
     * @return le résultat, ou null si le type est inconnu
     */
    static Result surround(CharSequence text, int selStart, int selEnd,
            String type, String language, String indent) {
        if (type == null) return null;
        String selected = text.subSequence(selStart, selEnd).toString();

        LanguageProfile profile = LanguageRegistry.forName(language);
        boolean isPython = profile != null
                && profile.family == SyntaxFamily.PYTHON;

        String inner = indent + "    ";
        String generated;

        switch (type) {
            case "if":
                if (isPython) {
                    generated = "if True:\n" + inner + selected;
                } else {
                    generated = "if (condition) {\n" + inner + selected
                            + "\n" + indent + "}";
                }
                break;
            case "try":
                if (isPython) {
                    generated = "try:\n" + inner + selected
                            + "\n" + indent + "except Exception:\n" + inner + "pass";
                } else {
                    generated = "try {\n" + inner + selected
                            + "\n" + indent + "} catch (Exception e) {\n" + inner + "\n" + indent + "}";
                }
                break;
            case "for":
                if (isPython) {
                    generated = "for item in collection:\n" + inner + selected;
                } else {
                    generated = "for (var item : collection) {\n" + inner + selected
                            + "\n" + indent + "}";
                }
                break;
            case "while":
                if (isPython) {
                    generated = "while True:\n" + inner + selected;
                } else {
                    generated = "while (condition) {\n" + inner + selected
                            + "\n" + indent + "}";
                }
                break;
            default:
                return null;
        }

        int newCaret = selStart + generated.indexOf('\n') + 1;
        return new Result(selStart, selEnd, generated, newCaret);
    }
}

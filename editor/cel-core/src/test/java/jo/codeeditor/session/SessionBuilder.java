package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;

/**
 * Utilitaire de test pour créer un EditorSession avec un texte initial.
 */
class SessionBuilder {
    private final String initialText;

    SessionBuilder(String initialText) {
        this.initialText = initialText;
    }

    EditorSession build() {
        return new EditorSession(EditorDocument.of(initialText));
    }
}

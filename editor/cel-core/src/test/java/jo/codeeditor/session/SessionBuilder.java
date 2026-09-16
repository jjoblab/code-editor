package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;

/**
 * Test helper to create an EditorSession with initial text.
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

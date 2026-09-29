package jo.codeeditor.demo;

import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Thème « nuit émeraude » de la démo — assorti à l'habillage Material 3
 * zinc/émeraude de l'application (voir res/values/themes.xml).
 *
 * <p>La construction suit l'ordre documenté des 31 couleurs de
 * {@link EditorTheme} : fond, gouttière, caret, sélection, sévérités,
 * puis la palette syntaxique.</p>
 */
final class DemoTheme {

    static EditorTheme emeraldNight() {
        return new EditorTheme(
            /* editorBg    = */ 0xFF09090B,
            /* gutterBg    = */ 0xFF09090B,
            /* gutterText  = */ 0xFF52525B,
            /* gutterBorder= */ 0xFF27272A,
            /* caret       = */ 0xFF34D399,
            /* selection   = */ 0xFF0A5B47,
            /* currentLine = */ 0xFF111114,
            /* error       = */ 0xFFF87171,
            /* warning     = */ 0xFFFBBF24,
            /* info        = */ 0xFF7DD3FC,
            /* keyword     = */ 0xFF34D399,
            /* string      = */ 0xFFD8A657,
            /* comment     = */ 0xFF7C8494,
            /* number      = */ 0xFFC4B5FD,
            /* annotation  = */ 0xFFD7BA7D,
            /* func        = */ 0xFFE5C890,
            /* type        = */ 0xFF5EEAD4,
            /* punct       = */ 0xFFD4D4D8,
            /* operator    = */ 0xFFD4D4D8,
            /* escape      = */ 0xFFD8A657,
            /* label       = */ 0xFFA7F3D0,
            /* property    = */ 0xFF93C5FD,
            /* variable    = */ 0xFF93C5FD,
            /* constant    = */ 0xFF7DD3FC,
            /* regexp      = */ 0xFFF0ABFC,
            /* findMatch   = */ 0xFF5A4426,
            /* findCurrent = */ 0xFF6B6B2A,
            /* occurrence  = */ 0xFF57572C,
            /* indentGuide = */ 0xFF27272A,
            /* composing   = */ 0xFF3F3F46,
            /* textColor   = */ 0xFFE4E4E7
        );
    }

    private DemoTheme() {
    }
}

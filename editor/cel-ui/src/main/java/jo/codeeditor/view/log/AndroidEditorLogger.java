package jo.codeeditor.view.log;

import android.util.Log;
import jo.codeeditor.util.EditorLogger;

/**
 * {@link EditorLogger} branché sur {@code android.util.Log} — installé
 * automatiquement à l'initialisation de {@code EditorView} (première vue
 * créée), remplaçant l'implémentation inerte.
 */
public final class AndroidEditorLogger implements EditorLogger {

    @Override
    public void info(String tag, String message) {
        Log.i(safeTag(tag), message);
    }

    @Override
    public void warn(String tag, String message) {
        Log.w(safeTag(tag), message);
    }

    @Override
    public void warn(String tag, String message, Throwable cause) {
        Log.w(safeTag(tag), message, cause);
    }

    @Override
    public void error(String tag, String message, Throwable cause) {
        Log.e(safeTag(tag), message, cause);
    }

    /** Tag logcat borné (23 caractères max recommandés). */
    private static String safeTag(String tag) {
        if (tag == null || tag.isEmpty()) return "code-editor";
        return tag.length() <= 23 ? tag : tag.substring(0, 23);
    }
}

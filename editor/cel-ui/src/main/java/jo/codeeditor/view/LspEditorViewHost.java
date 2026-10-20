package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.host.EditorHost;
import jo.codeeditor.session.EditorSession;

/**
 * Adaptateur de référence du contrat {@link EditorHost} autour d'une
 * {@link EditorView} — câblage du client LSP sans que celui-ci connaisse
 * la vue (plan 03, inversion de dépendance {@code cel-lsp → cel-ui}).
 *
 * <p>Usage hôte : {@code editor.setHost(new LspEditorViewHost(view))}
 * avant {@code connect()}. Toutes les méthodes délèguent à l'API PUBLIQUE
 * de la vue (aucun scell {@code @RestrictTo} n'est touché) — c'est le
 * SEUL point où la couche LSP rencontre la vue Android.</p>
 *
 * <p>L'adaptateur ne détient AUCUN état : il vit et meurt avec la vue,
 * aucune libération n'est requise. {@code session()}/{@code document()}
 * retournent {@code null} tant que la vue n'a pas de session posée
 * (contrat de nullité de l'interface).</p>
 */
public class LspEditorViewHost implements EditorHost {

    private final EditorView view;

    public LspEditorViewHost(EditorView view) {
        this.view = view;
    }

    @Override
    public EditorSession session() {
        return view.getSession();
    }

    @Override
    public EditorDocument document() {
        EditorSession session = view.getSession();
        return session != null ? session.getDocument() : null;
    }

    @Override
    public void setLanguage(Language language) {
        view.setLanguage(language);
    }

    @Override
    public void notifyDiagnosticsChanged() {
        view.notifyDiagnosticsChanged();
    }

    @Override
    public void notifyTextChanged() {
        view.notifyTextChanged();
    }

    @Override
    public void invalidate() {
        view.invalidate();
    }

    @Override
    public boolean isAttachedToWindow() {
        return view.isAttachedToWindow();
    }

    @Override
    public void post(Runnable task) {
        view.post(task);
    }

    @Override
    public void postDelayed(Runnable task, long delayMillis) {
        view.postDelayed(task, delayMillis);
    }

    /**
     * ★ 3.74.0 — appartenance réelle au thread UI : une mutation de
     * l'hôte (poser/retirer le langage) demandée depuis le thread UI
     * d'une vue attachée s'exécute immédiatement, sans passer derrière
     * la file (elle serait sinon rétrogradée APRÈS les poses du nouvel
     * onglet faites par l'application, qu'elle effacerait).
     */
    @Override
    public boolean isOnUiThread() {
        android.os.Looper main = android.os.Looper.getMainLooper();
        return main != null && main == android.os.Looper.myLooper();
    }
}

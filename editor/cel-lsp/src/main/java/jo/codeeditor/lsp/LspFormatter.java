package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.Formatter;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Traduit le formatage du SPI éditeur en requêtes LSP puis applique les
 * éditions retournées au texte courant, en ordre d'offset décroissant.
 * Instancié par {@link LspLanguage} quand le serveur annonce la capability
 * documentFormattingProvider.
 *
 * <p>★ 3.74.0 : document entier → {@code textDocument/formatting} ;
 * plage partielle → {@code textDocument/rangeFormatting} quand le serveur
 * annonce {@code documentRangeFormattingProvider} — sinon refus propre
 * (texte inchangé, aucune requête : le document entier n'est JAMAIS
 * formaté à la place de la plage demandée).</p>
 */
class LspFormatter implements Formatter {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspFormatter(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public boolean supportsRange() {
        if (wrapper.getCapabilities() == null) return false;
        // La capacité est soit absente (null), soit un booléen, soit des
        // options d'enregistrement (présentes = annoncée). Un booléen
        // FAUX explicite n'est PAS une capacité.
        org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean,
                org.eclipse.lsp4j.DocumentRangeFormattingOptions> cap =
                wrapper.getCapabilities().getDocumentRangeFormattingProvider();
        if (cap == null) return false;
        if (cap.isLeft()) return Boolean.TRUE.equals(cap.getLeft());
        return true;
    }

    @Override
    public CharSequence format(CharSequence text, int startOffset, int endOffset) {
        LanguageServer server = editor.readyServer(wrapper); // garde commun 3.74.0 : null si déconnecté (C1)
        if (server == null) return text;
        // Flush didChange en attente (cf. complete / inlayHints).
        // Le formatter LSP calcule des éditions sur le texte serveur — si
        // le texte est STALE (didChange debouncé à 300 ms pas encore envoyé),
        // les éditions seront calculées sur une version obsolète et
        // appliqueront des offsets hors-sync.
        editor.flushPendingChange();
        // Bornage défensif de la plage demandée.
        int start = Math.max(0, Math.min(startOffset, text.length()));
        int end = Math.max(start, Math.min(endOffset, text.length()));
        boolean documentEntier = start <= 0 && end >= text.length();
        if (!documentEntier && !supportsRange()) {
            // Refus propre (C3) : le serveur ne sait pas formater une
            // plage ; formater le document ENTIER à la place serait une
            // surprise destructive. Aucune requête ne part.
            return text;
        }
        if (!documentEntier && start >= end) {
            // Plage vide : rien à formater, aucune requête.
            return text;
        }
        try {
            CompletableFuture<List<org.eclipse.lsp4j.TextEdit>> future;
            if (documentEntier) {
                org.eclipse.lsp4j.DocumentFormattingParams params =
                    new org.eclipse.lsp4j.DocumentFormattingParams();
                params.setTextDocument(editor.getTextDocumentIdentifier());
                // ★ 3.74.0 : le protocole exige des options de formatage
                // (tabSize, insertSpaces) — sans elles, un serveur strict
                // peut rejeter la requête.
                params.setOptions(optionsDeFormatage(text));
                future = (CompletableFuture<List<org.eclipse.lsp4j.TextEdit>>) (CompletableFuture<?>)
                    server.getTextDocumentService().formatting(params);
            } else {
                org.eclipse.lsp4j.DocumentRangeFormattingParams params =
                    new org.eclipse.lsp4j.DocumentRangeFormattingParams();
                params.setTextDocument(editor.getTextDocumentIdentifier());
                params.setOptions(optionsDeFormatage(text));
                // La plage LSP est calculée depuis les offsets éditeur sur
                // le document vivant de l'hôte (lignes/colonnes).
                params.setRange(new org.eclipse.lsp4j.Range(
                        editor.offsetToPosition(start),
                        editor.offsetToPosition(end)));
                future = (CompletableFuture<List<org.eclipse.lsp4j.TextEdit>>) (CompletableFuture<?>)
                    server.getTextDocumentService().rangeFormatting(params);
            }
            List<org.eclipse.lsp4j.TextEdit> edits =
                    LspRequestTimeouts.await(future, wrapper.requestTimeouts().formattingMs());
            if (edits == null || edits.isEmpty()) return text;
            // Applique les éditions en ordre inverse (offset décroissant).
            StringBuilder sb = new StringBuilder(text);
            edits.sort((a, b) -> Integer.compare(
                editor.positionToOffset(b.getRange().getStart()),
                editor.positionToOffset(a.getRange().getStart())));
            for (org.eclipse.lsp4j.TextEdit te : edits) {
                int s = editor.positionToOffset(te.getRange().getStart());
                int e = editor.positionToOffset(te.getRange().getEnd());
                sb.replace(s, e, te.getNewText());
            }
            return sb.toString();
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * ★ 3.74.0 — options de formatage de la requête (tabSize,
     * insertSpaces). Source par ordre de priorité :
     * <ol>
     *   <li>valeur posée par l'application hôte
     *       ({@link jo.codeeditor.lang.host.EditorHost#formattingTabSize()}
     *       / {@link jo.codeeditor.lang.host.EditorHost#formattingInsertSpaces()},
     *       relayées par {@code EditorView.setFormattingOptions}) ;</li>
     *   <li>sinon indentation détectée dans le document (unité
     *       statistique tabulation contre 2/4/8 espaces — tabulations
     *       détectées → tabulations conservées) ;</li>
     *   <li>sinon 4 espaces.</li>
     * </ol>
     * Les options LSP facultatives (trimTrailingWhitespace,
     * insertFinalNewline…) restent hors périmètre.
     */
    private org.eclipse.lsp4j.FormattingOptions optionsDeFormatage(CharSequence text) {
        int tabSize = -1;
        Boolean insertSpaces = null;
        jo.codeeditor.lang.host.EditorHost host = editor.getHost();
        if (host != null) {
            int posed = host.formattingTabSize();
            if (posed > 0) tabSize = posed;
            insertSpaces = host.formattingInsertSpaces();
        }
        if (tabSize < 0 || insertSpaces == null) {
            String unit = jo.codeeditor.edit.EditOps.detectIndentUnit(text);
            if ("\t".equals(unit)) {
                // Tabulations détectées → tabulations conservées ; la
                // largeur visuelle d'une tabulation n'est pas décelable
                // dans le texte, repli 4.
                if (tabSize < 0) tabSize = 4;
                if (insertSpaces == null) insertSpaces = Boolean.FALSE;
            } else {
                if (tabSize < 0) tabSize = unit.length();
                if (insertSpaces == null) insertSpaces = Boolean.TRUE;
            }
        }
        return new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces.booleanValue());
    }
}

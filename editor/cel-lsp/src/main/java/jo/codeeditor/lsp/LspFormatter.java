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
 *
 * <p>★ 3.74.0 (L5, robustesse) : chaque échec est JOURNALISÉ via le
 * récepteur de journal du projet — délai dépassé, exception interceptée,
 * résultat vide (information, pas erreur), éditions rejetées. Les
 * journaux ne contiennent JAMAIS le contenu du document (seuls offsets
 * et compteurs y figurent). L'application des éditions valide les bornes
 * de CHAQUE édition, trie de façon stable et ignore — sans lever — les
 * éditions invalides (hors document, bornes inversées) ou chevauchant
 * une zone déjà traitée.</p>
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
            journal("formatage: plage refusée (le serveur n'annonce pas le formatage de plage)");
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
            if (edits == null || edits.isEmpty()) {
                // Résultat vide ≠ erreur : information distincte.
                journal("formatage: aucune édition retournée (document déjà conforme)");
                return text;
            }
            return appliquerÉditions(text, edits);
        } catch (java.util.concurrent.TimeoutException e) {
            // Délai dépassé : texte intact, échec journalisé (jamais le
            // contenu du document — seulement le délai et la requête).
            journal("formatage: délai dépassé ("
                    + wrapper.requestTimeouts().formattingMs() + " ms) — texte inchangé");
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            journal("formatage: interrompu pendant l'attente — texte inchangé");
            return text;
        } catch (Exception e) {
            // Exception interceptée (erreur de transport, réponse inval…) :
            // texte intact, échec journalisé. Le message d'exception est
            // utile au diagnostic ; le document, jamais.
            journal("formatage: erreur " + e.getClass().getSimpleName()
                    + " — " + String.valueOf(e.getMessage()) + " — texte inchangé");
            return text;
        }
    }

    /**
     * ★ 3.74.0 (L5) — application robuste des éditions : bornes validées
     * pour CHAQUE édition, tri stable par offset de DÉBUT décroissant,
     * éditions invalides (hors document, bornes inversées, newText null)
     * ou chevauchant une zone déjà traitée IGNORÉES avec journal —
     * jamais d'exception, jamais de corruption partielle du document.
     *
     * <p>Tri stable : deux éditions de même offset de début conservent
     * leur ordre d'arrivée (le tri LSP est documenté comme instable —
     * {@code List.sort} préserve l'ordre des éléments égaux).</p>
     */
    private CharSequence appliquerÉditions(CharSequence text,
            List<org.eclipse.lsp4j.TextEdit> edits) {
        final int longueur = text.length();
        // Document vivant de l'hôte pour la validation des positions
        // (une Position hors document — ligne au-delà de la dernière —
        // est un défaut serveur, pas une donnée à clamper silencieusement).
        jo.codeeditor.document.EditorDocument doc = null;
        if (editor.getHost() != null) doc = editor.getHost().document();
        // Matérialise les offsets UNE fois, filtre les éditions inconvertibles.
        java.util.List<int[]> bornes = new java.util.ArrayList<>(edits.size());
        java.util.List<org.eclipse.lsp4j.TextEdit> valides =
                new java.util.ArrayList<>(edits.size());
        int rejetsBornes = 0;
        for (org.eclipse.lsp4j.TextEdit te : edits) {
            if (te == null || te.getRange() == null || te.getNewText() == null
                    || te.getRange().getStart() == null || te.getRange().getEnd() == null) {
                rejetsBornes++;
                continue;
            }
            if (!bornesValides(te.getRange(), doc)) {
                rejetsBornes++;
                continue;
            }
            int s, e;
            try {
                s = editor.positionToOffset(te.getRange().getStart());
                e = editor.positionToOffset(te.getRange().getEnd());
            } catch (RuntimeException conversionÉchouée) {
                rejetsBornes++;
                continue;
            }
            // Bornes valides : 0 <= s <= e <= longueur. Une édition hors
            // document ou inversée est ignorée (journal), pas appliquée.
            if (s < 0 || e > longueur || s > e) {
                rejetsBornes++;
                continue;
            }
            bornes.add(new int[]{s, e});
            valides.add(te);
        }
        if (rejetsBornes > 0) {
            journal("formatage: " + rejetsBornes + " édition(s) ignorée(s) (bornes invalides)");
        }
        // Tri stable : offset de début décroissant, ordre d'arrivée préservé
        // à offset égal (le tri par paire (offset, index) est stable).
        Integer[] ordre = new Integer[valides.size()];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        final java.util.List<int[]> b = bornes;
        java.util.Arrays.sort(ordre, (x, y) -> {
            int cmp = Integer.compare(b.get(y)[0], b.get(x)[0]); // décroissant
            return cmp != 0 ? cmp : Integer.compare(x, y);        // stable
        });
        StringBuilder sb = new StringBuilder(text);
        int rejetsChevauchement = 0;
        // Limite basse des zones déjà réécrites : une édition ne peut
        // pas toucher [quelque chose < déjàTraitéJusquà).
        int déjàTraitéJusquà = Integer.MAX_VALUE;
        for (int idx : ordre) {
            int s = bornes.get(idx)[0];
            int e = bornes.get(idx)[1];
            if (e > déjàTraitéJusquà) {
                // Chevauche une zone déjà remplacée : ignorer plutôt que
                // détruire le texte fraîchement écrit.
                rejetsChevauchement++;
                continue;
            }
            sb.replace(s, e, valides.get(idx).getNewText());
            déjàTraitéJusquà = s;
        }
        if (rejetsChevauchement > 0) {
            journal("formatage: " + rejetsChevauchement
                    + " édition(s) ignorée(s) (chevauchement avec une zone déjà appliquée)");
        }
        return sb.toString();
    }

    /**
     * Bornes d'une {@link org.eclipse.lsp4j.Range} valides pour CE document :
     * lignes à l'intérieur du document (une ligne au-delà de la dernière
     * est un défaut serveur — le clamper silencieusement ferait porter
     * l'édition au mauvais endroit), et début jamais après la fin
     * (comparaison ligne puis colonne, AVANT tout clamp).
     * Sans document lié ({@code doc == null}) : seules les inversions
     * lexicales sont détectées.
     */
    private static boolean bornesValides(org.eclipse.lsp4j.Range range,
            jo.codeeditor.document.EditorDocument doc) {
        org.eclipse.lsp4j.Position start = range.getStart();
        org.eclipse.lsp4j.Position end = range.getEnd();
        // Début > fin (ligne, puis colonne) : bornes inversées.
        if (start.getLine() > end.getLine()
                || (start.getLine() == end.getLine()
                    && start.getCharacter() > end.getCharacter())) {
            return false;
        }
        if (doc != null) {
            int lignes = doc.lineCount();
            if (start.getLine() >= lignes || end.getLine() >= lignes
                    || start.getLine() < 0 || end.getLine() < 0) {
                return false;
            }
        }
        return true;
    }

    /** Journalise via le récepteur du projet (null = silencieux, tests JVM). */
    private void journal(String message) {
        if (logSink != null) logSink.log(message);
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

package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.Language;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Pont SPI Language → UI : branche tous les providers du
 * {@link Language} courant (complétion, aide de signature, hover,
 * code actions, symboles, diagnostics, definition/type/impl/super,
 * references, document highlights, rename, formatter, inlay hints)
 * sur les résolveurs et chemins de rendu d'EditorView, et possède les
 * rafraîchissements débouncés (code actions, inlay hints, document
 * highlights) ainsi que le StyleReceiver de l'analyzer incrémental.
 *
 * <p>Extrait d'EditorView : {@code setLanguage()} reste un relais public
 * sur la vue ; chaque adaptateur enveloppe le provider SPI pour que le
 * code de dessin / hit-test existant fonctionne sans changement. Si un
 * provider retourne {@code null} (non supporté), le résolveur
 * correspondant est mis à {@code null} — la fonctionnalité est
 * simplement désactivée.</p>
 */
final class EditorLanguageBridge {

    private final EditorView view;

    // Implémentation de StyleReceiver — ponte l'Analyzer incrémental du
    // SPI Language vers les styledLines d'EditorSession. L'analyzer
    // appelle onStylesUpdated sur un thread worker ; on poste vers le
    // thread UI pour invalider les lignes affectées + redessiner.
    private final jo.codeeditor.lang.StyleReceiver styleReceiver =
            new jo.codeeditor.lang.StyleReceiver() {
                @Override
                public void onStylesUpdated(int startLine, int endLine) {
                    if (view.getHandler() != null) {
                        view.getHandler().post(() -> {
                            // LineRenderCache n'a pas d'invalidateRange direct :
                            // on invalide à partir de startLine (toutes les
                            // lignes suivantes seront re-tokénisées par le
                            // prochain appel à analyzer.styledLine(i)).
                            view.renderCache.invalidateFrom(startLine);
                            view.invalidate();
                        });
                    } else {
                        view.renderCache.invalidateFrom(startLine);
                        view.invalidate();
                    }
                }
                @Override
                public void onBlocksUpdated() {
                    if (view.getHandler() != null) {
                        view.getHandler().post(EditorLanguageBridge.this::invalidateBlocks);
                    } else {
                        invalidateBlocks();
                    }
                }
            };

    private void invalidateBlocks() {
        if (view.session == null || view.language == null) return;
        try {
            java.util.List<jo.codeeditor.lang.model.CodeBlock> blocks =
                    view.language.getAnalyzer().computeBlocks();
            if (!blocks.isEmpty()) {
                java.util.List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> regions =
                        new java.util.ArrayList<>(blocks.size());
                for (jo.codeeditor.lang.model.CodeBlock b : blocks) {
                    regions.add(new jo.codeeditor.shift.DiagnosticShift.FoldRegion(
                            b.start, b.end, b.placeholder, b.kind, b.collapsed));
                }
                view.session.setFoldRegions(regions);
                view.invalidate();
            }
        } catch (Throwable t) {
            // Silencieux — l'analyzer peut ne pas implémenter computeBlocks
            // de façon fiable.
        }
    }

    /**
     * Corps de {@link EditorView#setLanguage(Language)} : détache le
     * langage précédent, branche l'analyzer + tous les providers SPI, et
     * déclenche les rafraîchissements initiaux.
     */
    void setLanguage(Language language) {
        if (view.language != null) {
            view.language.destroy();
        }
        // Si language est null, utiliser EmptyLanguage (pas null) pour que
        // tous les résolveurs soient proprement effacés — cela empêche des
        // résolveurs périmés d'un langage précédent (ex. Java) de se
        // déclencher sur un autre type de fichier (ex. XML).
        if (language == null) {
            language = new jo.codeeditor.lang.EmptyLanguage();
        }
        view.language = language;
        if (view.session != null) {
            // Branche l'analyzer.
            // Attache le StyleReceiver pour que l'analyzer puisse
            // pousser des mises à jour de style incrémentales depuis un
            // worker thread. Le receiver poste sur l'UI thread pour
            // invalider les lignes affectées.
            try {
                language.getAnalyzer().setReceiver(styleReceiver);
            } catch (Throwable t) {
                // Certains analyzers peuvent ne pas supporter setReceiver
                // (legacy).
            }
            language.getAnalyzer().reset(view.session.getText());

            // ── Ponte les providers SPI → chemins de rendu UI ────────
            // Chaque adaptateur enveloppe le provider SPI pour que le code
            // de dessin / hit-test existant d'EditorView fonctionne sans
            // changement.

            // Complétion
            if (language.getCompletionProvider() != null) {
                final jo.codeeditor.lang.provider.CompletionProvider spiComp =
                    language.getCompletionProvider();
                view.setCompletionProvider((text, caret, tokenStart, prefix) -> {
                    java.util.List<jo.codeeditor.completion.CompletionSession.Item> items =
                        new java.util.ArrayList<>();
                    jo.codeeditor.lang.provider.CompletionPublisher pub = new CompletionPublisherAdapter(items);
                    spiComp.complete(text, caret, pub);
                    return items;
                });
            } else {
                view.setCompletionProvider(null);
            }

            // Aide de signature
            if (language.getSignatureHelpProvider() != null) {
                final jo.codeeditor.lang.provider.SignatureHelpProvider spiSig =
                    language.getSignatureHelpProvider();
                view.setSignatureHelpResolver((text, caret) -> {
                    jo.codeeditor.lang.model.SignatureHelp help = spiSig.signatureHelp(text, caret);
                    if (help == null) return null;
                    // Convertit SPI SignatureHelp → SignatureHelpController.SignatureHelp
                    java.util.List<jo.codeeditor.completion.SignatureHelpController.Signature> sigs =
                        new java.util.ArrayList<>();
                    for (jo.codeeditor.lang.model.Signature s : help.signatures) {
                        java.util.List<jo.codeeditor.completion.SignatureHelpController.Parameter> params =
                            new java.util.ArrayList<>();
                        for (jo.codeeditor.lang.model.Parameter p : s.parameters) {
                            params.add(new jo.codeeditor.completion.SignatureHelpController.Parameter(
                                p.label, p.documentation));
                        }
                        sigs.add(new jo.codeeditor.completion.SignatureHelpController.Signature(
                            s.label, s.documentation, params, s.activeParameter));
                    }
                    return new jo.codeeditor.completion.SignatureHelpController.SignatureHelp(
                        sigs, help.activeSignature, help.activeParameter);
                });
            } else {
                view.setSignatureHelpResolver(null);
            }

            // Hover / quick doc
            if (language.getHoverProvider() != null) {
                final jo.codeeditor.lang.provider.HoverProvider spiHover =
                    language.getHoverProvider();
                view.setQuickDocResolver((text, offset) -> {
                    jo.codeeditor.lang.model.HoverContent content = spiHover.hover(text, offset);
                    if (content == null || content.isEmpty()) return null;
                    // Retourne comme une chaîne façon Javadoc pour
                    // QuickDoc.parseQuickDoc.
                    String doc = content.markdown;
                    if (doc != null && !doc.isEmpty()) {
                        return "/**\n * " + doc.replace("\n", "\n * ") + "\n */";
                    }
                    return content.signature;
                });
            } else {
                view.setQuickDocResolver(null);
            }

            // Code actions
            if (language.getCodeActionsProvider() != null) {
                final jo.codeeditor.lang.provider.CodeActionsProvider spiActions =
                    language.getCodeActionsProvider();
                view.setCodeActionsResolver((text, line) -> {
                    java.util.List<EditorView.CodeAction> out = new java.util.ArrayList<>();
                    for (jo.codeeditor.lang.model.CodeAction a : spiActions.codeActions(text, line)) {
                        out.add(new EditorView.CodeAction(a.title, a.kind, a.apply));
                    }
                    return out;
                });
            } else {
                view.setCodeActionsResolver(null);
            }

            // Go-to-symbol
            if (language.getSymbolProvider() != null) {
                final jo.codeeditor.lang.provider.SymbolProvider spiSym =
                    language.getSymbolProvider();
                view.setSymbolResolver((text) -> {
                    java.util.List<NavigationMenu.Symbol> out = new java.util.ArrayList<>();
                    for (jo.codeeditor.lang.model.Symbol s : spiSym.symbols(text)) {
                        out.add(new NavigationMenu.Symbol(s.name, s.offset, s.kind, s.container));
                    }
                    return out;
                });
            } else {
                view.setSymbolResolver(null);
            }

            // Diagnostics — poussés vers la session si disponible.
            // Le provider + la tâche débouncée vivent dans
            // EditorDiagnosticsPusher : sans eux, les diagnostics seraient
            // calculés UNE SEULE FOIS 500ms après setLanguage et jamais
            // rafraîchis — taper une erreur de syntaxe n'afficherait pas
            // de souligné.
            if (language.getDiagnosticsProvider() != null) {
                view.diagnosticsPusher.attach(language.getDiagnosticsProvider());
            } else {
                view.diagnosticsPusher.detach();
            }

            // ── Branche les providers SPI restants ─────────────────────
            // Ils pontent les providers adossés au LSP vers les chemins
            // UI / dessin de l'éditeur. Chacun est conditionné par le
            // provider SPI non-null — si un serveur ne supporte pas une
            // fonctionnalité, le résolveur est mis à null et l'élément UI
            // correspondant est simplement désactivé.

            // Definition (go-to-definition)
            if (language.getDefinitionProvider() != null) {
                final jo.codeeditor.lang.provider.DefinitionProvider spiDef =
                    language.getDefinitionProvider();
                view.setDefinitionResolver((text, offset) -> spiDef.definitions(text, offset));
            } else {
                view.setDefinitionResolver(null);
            }

            // Type-definition (go-to-type-declaration) — la section GO TO
            // du menu contextuel unifié (port NavMenu).
            if (language.getTypeDefinitionProvider() != null) {
                final jo.codeeditor.lang.provider.TypeDefinitionProvider spiTypeDef =
                    language.getTypeDefinitionProvider();
                view.setTypeDefinitionResolver((text, offset) ->
                    spiTypeDef.typeDefinitions(text, offset));
            } else {
                view.setTypeDefinitionResolver(null);
            }

            // Implementations (go-to-implementations) et super
            // (go-to-super) — complètent la section GO TO du menu
            // contextuel unifié aux QUATRE options.
            if (language.getImplementationsProvider() != null) {
                final jo.codeeditor.lang.provider.ImplementationsProvider spiImpl =
                    language.getImplementationsProvider();
                view.setImplementationsResolver((text, offset) ->
                    spiImpl.implementations(text, offset));
            } else {
                view.setImplementationsResolver(null);
            }

            if (language.getSuperDefinitionProvider() != null) {
                final jo.codeeditor.lang.provider.SuperDefinitionProvider spiSuper =
                    language.getSuperDefinitionProvider();
                view.setSuperResolver((text, offset) ->
                    spiSuper.superTargets(text, offset));
            } else {
                view.setSuperResolver(null);
            }

            // References (find-references)
            if (language.getReferencesProvider() != null) {
                final jo.codeeditor.lang.provider.ReferencesProvider spiRef =
                    language.getReferencesProvider();
                view.setReferencesResolver((text, offset) -> spiRef.references(text, offset));
            } else {
                view.setReferencesResolver(null);
            }

            // Document highlights (occurrences)
            if (language.getDocumentHighlightProvider() != null) {
                final jo.codeeditor.lang.provider.DocumentHighlightProvider spiHl =
                    language.getDocumentHighlightProvider();
                view.setDocumentHighlightResolver((text, offset) -> {
                    java.util.List<jo.codeeditor.lang.model.DocumentHighlight> hls =
                        spiHl.highlights(text, offset);
                    java.util.List<int[]> out = new java.util.ArrayList<>();
                    if (hls != null) {
                        for (jo.codeeditor.lang.model.DocumentHighlight h : hls) {
                            out.add(new int[]{h.start, h.end});
                        }
                    }
                    return out;
                });
            } else {
                view.setDocumentHighlightResolver(null);
            }

            // Rename — remplace le repli par correspondance de
            // sous-chaîne quand le serveur de langage supporte le rename
            // LSP.
            if (language.getRenameProvider() != null) {
                final jo.codeeditor.lang.provider.RenameProvider spiRename =
                    language.getRenameProvider();
                view.setRenameResolver((text, offset, newName) -> {
                    jo.codeeditor.lang.model.RenameResult result = spiRename.rename(text, offset, newName);
                    if (result == null) return null;
                    // Applique les édits pour produire le nouveau texte
                    // complet.
                    StringBuilder sb = new StringBuilder(text);
                    // Copie les édits dans une liste mutable, puis trie par
                    // ordre décroissant pour que les offsets antérieurs
                    // restent valides pendant les remplacements.
                    java.util.List<jo.codeeditor.lang.model.TextEdit> edits =
                        new java.util.ArrayList<>(result.edits);
                    edits.sort((a, b) -> Integer.compare(b.start, a.start));
                    for (jo.codeeditor.lang.model.TextEdit e : edits) {
                        if (e.start >= 0 && e.end <= sb.length() && e.start <= e.end) {
                            sb.replace(e.start, e.end, e.newText);
                        }
                    }
                    return sb.toString();
                });
            } else {
                view.setRenameResolver(null);
            }

            // Formatter
            if (language.getFormatter() != null) {
                final jo.codeeditor.lang.provider.Formatter spiFmt = language.getFormatter();
                view.setFormatterResolver((text) -> spiFmt.format(text, 0, text.length()).toString());
            } else {
                view.setFormatterResolver(null);
            }

            // Inlay hints — poussés vers session.setInlayHints() en
            // débouncé après chaque édition. Le drawCachedInlays() du
            // renderer les lit par ligne.
            if (language.getInlayHintProvider() != null) {
                inlayHintProviderSpi = language.getInlayHintProvider();
                inlayHintTask = () -> {
                    if (view.session == null || inlayHintProviderSpi == null) return;
                    EditorDocument doc = view.session.getDocument();
                    // Demande le document ENTIER : le démon du moteur
                    // demande {@code hintsAt(path, text, 0, text.length)}
                    // pour le buffer complet, pour que des hints soient
                    // disponibles sur chaque ligne où l'utilisateur scrolle.
                    // Une requête limitée au viewport (lignes visibles ±4,
                    // calculée depuis vOffset/getHeight()) serait cassée
                    // deux fois : au démarrage getHeight()==0 donc ~5 lignes
                    // seulement auraient des hints, et rien ne re-requête au
                    // scroll — les hints n'apparaîtraient jamais sous le
                    // premier écran. Le serveur interne calcule les hints du
                    // fichier entier quelle que soit la plage demandée, donc
                    // la requête pleine plage coûte pareil.
                    int first = 0;
                    int last = Math.max(0, doc.lineCount() - 1);
                    // La requête inlayHint LSP (5 s) quitte le
                    // thread UI ; livraison latest-wins par génération.
                    final int gen = ++inlayGeneration;
                    final CharSequence text = view.session.getText();
                    final int fFirst = first;
                    final int fLast = last;
                    EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
                        java.util.List<jo.codeeditor.lang.model.InlayHint> spiHints = null;
                        try {
                            spiHints = inlayHintProviderSpi.inlayHints(text, fFirst, fLast);
                        } catch (Exception ignored) {
                        }
                        java.util.List<DiagnosticShift.InlayHint> legacy =
                                new java.util.ArrayList<>();
                        if (spiHints != null) {
                            for (jo.codeeditor.lang.model.InlayHint hint : spiHints) {
                                legacy.add(new DiagnosticShift.InlayHint(hint.offset, hint.text, true));
                            }
                        }
                        final java.util.List<DiagnosticShift.InlayHint> computed = legacy;
                        Runnable apply = () -> {
                            if (gen != inlayGeneration || view.session == null) return;
                            view.session.setInlayHints(computed);
                            view.invalidate();
                        };
                        android.os.Handler h2 = view.getHandler();
                        if (h2 != null) h2.post(apply); else apply.run();
                    });
                };
                // Run initial (débouncé).
                scheduleInlayHints();
            } else {
                inlayHintProviderSpi = null;
                inlayHintTask = null;
                view.session.setInlayHints(new java.util.ArrayList<>());
            }

            // Déclenche un rafraîchissement initial des document highlights
            // au caret courant.
            scheduleDocumentHighlights();
            // État initial d'appariement de parenthèses pour le document
            // chargé.
            view.updateBracketPair();
        }
        view.invalidate();
    }

    // ── Inlay hints (débouncés) ─────────────────────────────────────

    /** Provider des inlay hints. Poussé vers session.setInlayHints() en débouncé. */
    private jo.codeeditor.lang.provider.InlayHintProvider inlayHintProviderSpi;
    private Runnable inlayHintTask;
    private static final int INLAY_HINT_DEBOUNCE_MS = 800;

    /** Planifie un rafraîchissement débouncé des inlay hints. */
    void scheduleInlayHints() {
        if (inlayHintProviderSpi == null || inlayHintTask == null) return;
        if (view.getHandler() != null) {
            view.getHandler().removeCallbacks(inlayHintTask);
            view.getHandler().postDelayed(inlayHintTask, INLAY_HINT_DEBOUNCE_MS);
        }
    }

    /** Génération des inlay hints (annulation). */
    private volatile int inlayGeneration = 0;

    // ── Document highlights (débouncés) ─────────────────────────────

    /** Planifie un rafraîchissement débouncé des document highlights. */
    void scheduleDocumentHighlights() {
        if (view.documentHighlightResolver == null) return;
        if (view.getHandler() != null) {
            view.getHandler().removeCallbacks(documentHighlightTask);
            view.getHandler().postDelayed(documentHighlightTask, 400);
        }
    }

    /** Génération du documentHighlight (annulation). */
    private volatile int docHighlightGeneration = 0;

    private final Runnable documentHighlightTask;

    EditorLanguageBridge(EditorView view) {
        this.view = view;
        this.documentHighlightTask = () -> {
            if (view.session == null || view.documentHighlightResolver == null) return;
            jo.codeeditor.document.Selection sel = view.session.getSelection();
            if (!sel.isCursor()) {
                docHighlightGeneration++;
                view.documentHighlights.clear();
                view.invalidate();
                return;
            }
            // La requête LSP (timeout 10 s, appelée toutes les 400 ms !)
            // quitte le thread UI ; livraison latest-wins.
            final int gen = ++docHighlightGeneration;
            final String text = view.session.getText().toString();
            final int caret = sel.start;
            EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
                List<int[]> result = null;
                try {
                    result = view.documentHighlightResolver.resolve(text, caret);
                } catch (Exception ignored) {
                }
                final List<int[]> fetched = result;
                Runnable apply = () -> {
                    if (gen != docHighlightGeneration || view.session == null) return;
                    view.documentHighlights.clear();
                    if (fetched != null) view.documentHighlights.addAll(fetched);
                    view.invalidate();
                };
                android.os.Handler h = view.getHandler();
                if (h != null) h.post(apply); else apply.run();
            });
        };
    }

    // ── Code actions (débouncées) ───────────────────────────────────

    // Rafraîchissement débouncé des code actions — un rafraîchissement à
    // chaque frame bloquerait le thread UI.
    private Runnable codeActionsTask;
    private static final int CODE_ACTIONS_DEBOUNCE_MS = 800;

    void scheduleCodeActionsRefresh() {
        if (view.codeActionsResolver == null) return;
        if (codeActionsTask == null) {
            codeActionsTask = () -> {
                if (view.session == null) return;
                int first = Math.max(0, (int) (view.vOffset / view.metrics.getLineHeight()) - 1);
                int last = Math.min(view.session.getDocument().lineCount() - 1,
                    (int) ((view.vOffset + view.getHeight()) / view.metrics.getLineHeight()) + 1);
                view.popupManager.refreshCodeActions(first, last);
                view.invalidate();
            };
        }
        if (view.getHandler() != null) {
            view.getHandler().removeCallbacks(codeActionsTask);
            view.getHandler().postDelayed(codeActionsTask, CODE_ACTIONS_DEBOUNCE_MS);
        }
    }

    /** Retire la tâche de code actions en attente (détachement de la vue). */
    void cancelPending() {
        if (view.getHandler() != null && codeActionsTask != null) {
            view.getHandler().removeCallbacks(codeActionsTask);
        }
    }

    /**
     * Adaptateur qui collecte les CompletionItems d'un
     * {@link jo.codeeditor.lang.provider.CompletionPublisher} dans une liste
     * pour le callback {@code setCompletionProvider}.
     */
    private static class CompletionPublisherAdapter implements jo.codeeditor.lang.provider.CompletionPublisher {
        private final java.util.List<jo.codeeditor.completion.CompletionSession.Item> items;
        private boolean cancelled = false;

        CompletionPublisherAdapter(java.util.List<jo.codeeditor.completion.CompletionSession.Item> items) {
            this.items = items;
        }

        @Override
        public void addItem(jo.codeeditor.lang.model.CompletionItem item) {
            if (cancelled) return;
            // Mapping + badge de type : le kind LSP numérique (kindCode,
            // avec secours dérivé de l'icône string historique) doit
            // arriver jusqu'au renderer pour le badge — pas
            // `item.sortPriority`, qui n'a rien à voir avec le champ kind
            // (int). isKeyword n'est vrai QUE pour un vrai mot-clé (kind
            // LSP Keyword), pas pour tout candidat non-snippet, sinon le
            // reRanked est détraqué.
            int kindCode = item.kindCode != 0
                    ? item.kindCode : legacyIconToKind(item.kind);
            items.add(new jo.codeeditor.completion.CompletionSession.Item(
                item.label, item.detail, item.insertText, item.kind,
                kindCode,
                item.sortPriority,
                kindCode == 14,   // LSP Keyword
                item.isSnippet,
                item.kindTag,
                item.postApplyAction));
        }

        /** Kind LSP dérivé de l'icône string historique
         *  (providers sans kindCode). */
        private static int legacyIconToKind(String icon) {
            if (icon == null) return 0;
            switch (icon) {
                case "k": return 14; // Keyword
                case "m": return 2;  // Method
                case "f": return 5;  // Field
                case "c": return 7;  // Class
                case "i": return 8;  // Interface
                case "e": return 13; // Enum
                case "p": return 9;  // Module (package)
                default: return 0;
            }
        }

        @Override
        public void addItems(java.util.List<jo.codeeditor.lang.model.CompletionItem> items) {
            for (jo.codeeditor.lang.model.CompletionItem item : items) addItem(item);
        }

        @Override
        public void flush() {}

        @Override
        public void cancel() { cancelled = true; }

        @Override
        public boolean isCancelled() { return cancelled; }
    }
}

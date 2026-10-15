package jo.codeeditor.view;

import jo.codeeditor.view.popup.EditorPopupManager;

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
        } catch (Exception t) {
            // L'analyzer peut ne pas implémenter computeBlocks de façon
            // fiable — journalisé, les plis existants restent.
            jo.codeeditor.util.EditorLog.warn("EditorLanguage",
                "calcul des blocs de code échoué", t);
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
            } catch (Exception t) {
                // Certains analyzers peuvent ne pas supporter setReceiver
                // (legacy) — journalisé.
                jo.codeeditor.util.EditorLog.warn("EditorLanguage",
                    "setReceiver non supporté par l'analyzer", t);
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
                // Classe anonyme (et non lambda) : l'interface interne garde
                // sa méthode abstraite 4 paramètres (compatibilité des
                // fournisseurs existants) ; on surcharge la variante par
                // défaut 5 paramètres pour transporter le caractère
                // déclencheur jusqu'au serveur (CompletionContext
                // triggerKind) — null = invocation explicite (Invoked).
                view.setCompletionProvider(new EditorView.CompletionProvider() {
                    @Override
                    public java.util.List<jo.codeeditor.completion.CompletionSession.Item> provide(
                            String text, int caret, int tokenStart, String prefix) {
                        return provide(text, caret, tokenStart, prefix, null);
                    }

                    @Override
                    public java.util.List<jo.codeeditor.completion.CompletionSession.Item> provide(
                            String text, int caret, int tokenStart, String prefix,
                            Character triggerCharacter) {
                        java.util.List<jo.codeeditor.completion.CompletionSession.Item> items =
                            new java.util.ArrayList<>();
                        jo.codeeditor.lang.provider.CompletionPublisher pub =
                            new CompletionPublisherAdapter(items);
                        spiComp.complete(text, caret,
                            triggerCharacter != null ? triggerCharacter.toString() : null, pub);
                        return items;
                    }
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

            // Code actions — résolution À LA DEMANDE au point
            // d'invocation (le pont SPI expose la variante au caret :
            // plage sélection/mot/ligne, une requête par demande).
            if (language.getCodeActionsProvider() != null) {
                final jo.codeeditor.lang.provider.CodeActionsProvider spiActions =
                    language.getCodeActionsProvider();
                view.setCodeActionsResolver(new EditorView.CodeActionsResolver() {
                    @Override
                    public List<EditorView.CodeAction> resolve(String text, int line) {
                        List<EditorView.CodeAction> out = new java.util.ArrayList<>();
                        for (jo.codeeditor.lang.model.CodeAction a : spiActions.codeActions(text, line)) {
                            out.add(new EditorView.CodeAction(a.title, a.kind, a.disabledReason, a.apply));
                        }
                        return out;
                    }
                    @Override
                    public List<EditorView.CodeAction> resolveAtCaret(String text,
                            int caretOffset, int selectionStart, int selectionEnd) {
                        List<EditorView.CodeAction> out = new java.util.ArrayList<>();
                        for (jo.codeeditor.lang.model.CodeAction a
                                : spiActions.codeActions(text, caretOffset,
                                        selectionStart, selectionEnd, null)) {
                            out.add(new EditorView.CodeAction(a.title, a.kind, a.disabledReason, a.apply));
                        }
                        return out;
                    }
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
            // LSP ; la pré-validation (placeholder / refus propre à
            // l'ouverture) est branchée sur le même provider.
            if (language.getRenameProvider() != null) {
                final jo.codeeditor.lang.provider.RenameProvider spiRename =
                    language.getRenameProvider();
                view.renamePrepareResolver = spiRename::prepareRename;
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
                view.renamePrepareResolver = null;
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
                    // Défaut : document ENTIER (le démon du moteur demande
                    // {@code hintsAt(path, text, 0, text.length)} pour le
                    // buffer complet — la pleine plage ne lui coûte pas
                    // plus, et les hints restent disponibles sur chaque
                    // ligne où l'utilisateur scrolle sans re-requête).
                    // I5 (J6) : un provider exigeant
                    // ({@code wantsViewportRange()}) reçoit la fenêtre
                    // VISIBLE ± 4 lignes, recalculée à chaque requête, avec
                    // re-requête au scroll idle (scheduleInlayViewportRefresh).
                    // getHeight()==0 (avant le premier layout) retombe sur
                    // la pleine plage : la fenêtre serait fausse et
                    // jamais corrigée sans scroll.
                    int first = 0;
                    int last = Math.max(0, doc.lineCount() - 1);
                    if (inlayHintProviderSpi.wantsViewportRange()
                            && view.getHeight() > 0
                            && view.metrics != null
                            && view.metrics.getLineHeight() > 0) {
                        float lineH = view.metrics.getLineHeight();
                        int visibleFirst = (int) Math.floor(
                                view.scroll.vOffset / lineH);
                        int visibleLast = (int) Math.floor(
                                (view.scroll.vOffset + view.getHeight()) / lineH);
                        first = Math.max(0, visibleFirst - INLAY_VIEWPORT_MARGIN);
                        last = Math.min(doc.lineCount() - 1,
                                visibleLast + INLAY_VIEWPORT_MARGIN);
                    }
                    // La requête inlayHint LSP (5 s) quitte le
                    // thread UI ; livraison latest-wins par génération.
                    final int gen = ++inlayGeneration;
                    final CharSequence text = view.session.getText();
                    final int fFirst = first;
                    final int fLast = last;
                    jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
                        java.util.List<jo.codeeditor.lang.model.InlayHint> spiHints = null;
                        try {
                            spiHints = inlayHintProviderSpi.inlayHints(text, fFirst, fLast);
                        } catch (Exception e) {
                            // Provider d'indications en ligne défaillant :
                            // journalisé, pas d'indications cette fenêtre.
                            jo.codeeditor.util.EditorLog.warn("EditorInlay",
                                "calcul des indications en ligne échoué", e);
                        }
                        java.util.List<DiagnosticShift.InlayHint> legacy =
                                new java.util.ArrayList<>();
                        if (spiHints != null) {
                            for (jo.codeeditor.lang.model.InlayHint hint : spiHints) {
                                // I1 (J3) : le kind est recopié — le
                                // rendu distingue paramètre (italique, plus
                                // effacé) de type (droit). I2 (J3) : le
                                // tooltip direct traverse aussi (la
                                // résolution paresseuse ne concerne que
                                // les hints sans tooltip). I3 (J6) : les
                                // parts du libellé traversent aussi —
                                // l'appui long consulte le tooltip de la
                                // part sous le doigt.
                                java.util.List<DiagnosticShift.InlayHintPart> parts = null;
                                if (hint.parts != null) {
                                    parts = new java.util.ArrayList<>(
                                            hint.parts.size());
                                    for (jo.codeeditor.lang.model.InlayHint.Part part
                                            : hint.parts) {
                                        parts.add(new DiagnosticShift.InlayHintPart(
                                                part.text, part.tooltip));
                                    }
                                }
                                legacy.add(new DiagnosticShift.InlayHint(
                                        hint.offset, hint.text, true, hint.kind,
                                        hint.tooltip, parts, hint.command));
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

            // I2 (lot 2, J3) : résolution paresseuse du tooltip des hints
            // (au premier appui long) — le SPI expose resolveHint ; le
            // resolver de vue le déporte sur la voie « features ». Sans
            // provider, le resolver est retiré (l'appui long sur un hint
            // retombe sur la sélection du mot).
            view.setInlayTooltipResolver(inlayHintProviderSpi != null
                    ? (docText, offset, hintText) -> {
                        jo.codeeditor.lang.model.InlayHint resolved =
                                inlayHintProviderSpi.resolveHint(docText,
                                        new jo.codeeditor.lang.model.InlayHint(
                                                offset, hintText, "type"));
                        return resolved != null ? resolved.tooltip : null;
                    }
                    : null);

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

    /** Handler de repli (main looper) — la vue peut être configurée AVANT
     *  son attachement ({@code getHandler() == null}) : le rafraîchissement
     *  initial des inlay hints partirait alors jamais. Pattern du tapHandler
     *  d'EditorInputHandler. */
    private android.os.Handler mainHandler;

    private android.os.Handler handler() {
        if (view.getHandler() != null) return view.getHandler();
        if (mainHandler == null) {
            mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return mainHandler;
    }

    /** Planifie un rafraîchissement débouncé des inlay hints. */
    void scheduleInlayHints() {
        if (inlayHintProviderSpi == null || inlayHintTask == null) return;
        android.os.Handler h = handler();
        h.removeCallbacks(inlayHintTask);
        h.postDelayed(inlayHintTask, INLAY_HINT_DEBOUNCE_MS);
    }

    /** Génération des inlay hints (annulation). */
    private volatile int inlayGeneration = 0;

    // ── I5 (lot 2, J6) : re-requête au scroll idle ─────────────────
    // Marge de lignes au-dessus/en-dessous du viewport demandée au
    // provider exigeant (fenêtre de requête).
    private static final int INLAY_VIEWPORT_MARGIN = 4;
    /** Calme de scroll avant la re-requête (ms). */
    private static final int INLAY_SCROLL_IDLE_MS = 200;
    /** Tâche de re-requête au repos du scroll (ré-armée à chaque scroll). */
    private Runnable inlayScrollIdleTask;

    /**
     * I5 — (ré-)arme la re-requête des inlay hints au repos du scroll :
     * appelé à CHAQUE notification de scroll (pattern du préfetch du
     * viewport — un removeCallbacks + postDelayed, bon marché en plein
     * fling). Ne s'arme que pour un provider à plage viewport ; au feu,
     * la tâche relance {@code inlayHintTask} qui recalcule la fenêtre
     * COURANTE (latest-wins par génération).
     */
    void scheduleInlayViewportRefresh() {
        if (inlayHintProviderSpi == null || inlayHintTask == null) return;
        if (!inlayHintProviderSpi.wantsViewportRange()) return;
        if (inlayScrollIdleTask == null) {
            inlayScrollIdleTask = () -> {
                if (inlayHintProviderSpi == null || inlayHintTask == null) return;
                inlayHintTask.run();
            };
        }
        android.os.Handler h = handler();
        h.removeCallbacks(inlayScrollIdleTask);
        h.postDelayed(inlayScrollIdleTask, INLAY_SCROLL_IDLE_MS);
    }

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
            jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
                List<int[]> result = null;
                try {
                    result = view.documentHighlightResolver.resolve(text, caret);
                } catch (Exception e) {
                    // Résolveur d'hôte défaillant : journalisé, pas de
                    // surlignage de document cette occurrence.
                    jo.codeeditor.util.EditorLog.warn("EditorHighlight",
                        "résolution des occurrences du symbole échouée", e);
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

    // ── Code actions (résolues à la demande) ───────────────────────

    /**
     * Nettoyage des tâches différées du pont langage (détachement de la
     * vue). Le balayage débouncé des lignes visibles a disparu — les
     * actions sont résolues à l'invocation ; les rafraîchissements
     * restants (inlay hints, document highlights) possèdent leur propre
     * garde de fraîcheur à l'application.
     */
    void cancelPending() {
        // I5 : la re-requête scroll-idle en attente meurt au détachement
        // (pattern du préfetch viewport). Les deux handlers sont nettoyés :
        // le repli main-looper peut porter la tâche d'une vue détachée.
        if (inlayScrollIdleTask != null) {
            if (view.getHandler() != null) {
                view.getHandler().removeCallbacks(inlayScrollIdleTask);
            }
            if (mainHandler != null) {
                mainHandler.removeCallbacks(inlayScrollIdleTask);
            }
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
                // L1/D-01 : champs étendus transportés jusqu'au client.
                item.filterText, item.sortText,
                item.replaceStart, item.replaceEnd,
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

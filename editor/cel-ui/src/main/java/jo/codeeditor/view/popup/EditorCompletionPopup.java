package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.state.CompletionState;

import androidx.annotation.RestrictTo;

import jo.codeeditor.document.EditorDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Popup de complétion : état visible, pipeline asynchrone (génération +
 * debounce + lane « interactive » dédiée), filtrage local/fuzzy,
 * mots-clés intégrés et géométrie d'ancrage. Corps déplacés
 * d'EditorPopupManager à l'identique (adaptation des accès délégués).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorCompletionPopup {

    private final EditorView view;
    /** Référence au gestionnaire pour la fermeture croisée des popups. */
    private final EditorPopupManager popups;

    EditorCompletionPopup(EditorView view, EditorPopupManager popups) {
        this.view = view;
        this.popups = popups;
    }

    // ════════════════════════════════════════════════════════════════
    // Completion popup
    // ════════════════════════════════════════════════════════════════

    void setCompletionItems(List<jo.codeeditor.completion.CompletionSession.Item> items,
                            int tokenStart, String prefix) {
        view.completion.reset(items, tokenStart, prefix);
        view.invalidate();
    }

    boolean isCompletionVisible() { return view.completion.visible; }

    void dismissCompletion() {
        // Annule TOUTE livraison en vol / débouncée — sans ça une
        // réponse périmée pouvait rouvrir le popup juste après un Échap.
        completionGeneration.incrementAndGet();
        cancelPendingFetch();
        if (view.completion.visible) {
            // B7 : photographie le popup AVANT de nettoyer son état — le
            // fantôme s'efface en 110 ms par-dessus le contenu.
            view.popupFade.snuff(jo.codeeditor.view.popup.EditorPopupFade.Kind.COMPLETION);
            view.completion.clear();
            view.invalidate();
        }
    }

    // ── État du pipeline async complétion ────────────────────
    /** Compteur de génération — bump à chaque nouvelle frappe/dismiss :
     * toute réponse dont la génération ≠ courante est jetée. */
    private final java.util.concurrent.atomic.AtomicInteger completionGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Débounce avant requête moteur.
     *
     * <p>★ Nécessaire pour éviter de spammer le serveur pendant la
     * frappe rapide (10+ touches/sec) ; les caractères déclencheurs
     * (ex : {@code .}) court-circuitent entièrement ce debounce via le
     * paramètre {@code immediate=true} de {@link #scheduleAsyncFetch}.</p>
     *
     * <p>La valeur par défaut est historique (80 ms) ; le réglage courant
     * vit dans {@link #completionDebounceMs} (exposé par
     * {@code EditorView.setCompletionDebounceMs(int)}).</p>
     */
    private static final int COMPLETION_DEBOUNCE_MS = 80;
    /** Débounce courant avant requête moteur (ms), réglable. */
    private int completionDebounceMs = COMPLETION_DEBOUNCE_MS;
    /**
     * Honorons-nous le drapeau « liste tronquée » des fournisseurs ?
     * (défaut : oui). À vrai, une base tronquée re-requête à chaque
     * édition du préfixe (coalescée) ; à faux, filtrage local pur
     * historique — une seule requête par token.
     */
    private boolean honorIncompleteCompletion = true;
    private Runnable completionDebounceTask;
    /**
     * Une requête est-elle en vol (lancée, pas encore livrée) ? Confine
     * au thread UI en production ; volatile car les bancs sans handler
     * exécutent la livraison sur la voie dédiée.
     */
    private volatile boolean fetchInFlight = false;
    /** Token de la requête en vol (garant d'identité pour la coalescence). */
    private int inflightTokenStart = -1;
    /** Génération de la requête en vol — une requête périmée (dismiss,
     * frappe plus récente) n'est plus coalescée : sa livraison sera
     * jetée par la garde de génération, il faut repartir d'une requête
     * neuve. */
    private int inflightGen = -1;

    /** Réglage : honorer le drapeau « liste tronquée » (défaut : vrai). */
    void setHonorIncompleteCompletion(boolean honor) {
        this.honorIncompleteCompletion = honor;
    }

    boolean isHonorIncompleteCompletion() {
        return honorIncompleteCompletion;
    }

    /**
     * Réglage du débounce (ms) avant requête moteur — les demandes
     * immédiates (caractère déclencheur, Ctrl+Espace) le court-circuitent
     * toujours. Les valeurs négatives sont ramenées à 0.
     */
    void setCompletionDebounceMs(int debounceMs) {
        this.completionDebounceMs = Math.max(0, debounceMs);
    }

    int getCompletionDebounceMs() {
        return completionDebounceMs;
    }

    private void cancelPendingFetch() {
        android.os.Handler h = view.getHandler();
        if (completionDebounceTask != null && h != null) {
            h.removeCallbacks(completionDebounceTask);
        }
        completionDebounceTask = null;
    }

    /** Offsets des chars déclencheurs annoncés par le fournisseur LSP. */
    private java.util.List<String> triggerCharacters() {
        try {
            if (view.language != null && view.language.getCompletionProvider() != null) {
                return view.language.getCompletionProvider().getTriggerCharacters();
            }
        } catch (Exception e) {
            // Provider de complétion défaillant : repli sur « aucun
            // caractère déclencheur », journalisé.
            jo.codeeditor.util.EditorLog.warn("EditorCompletion",
                "lecture des caractères déclencheurs échouée", e);
        }
        return java.util.Collections.emptyList();
    }

    private static int tokenStartAt(CharSequence text, int caret) {
        int tokenStart = caret;
        while (tokenStart > 0) {
            char c = text.charAt(tokenStart - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                tokenStart--;
            } else {
                break;
            }
        }
        return tokenStart;
    }

    /** Refiltrage local : délègue à l'état (préservation du libellé
     *  sélectionné + fenêtre de scroll solidaire — invariants possédés
     *  par CompletionState). */
    private void applyFilteredItems(java.util.List<jo.codeeditor.completion.CompletionSession.Item> filtered,
                                    int tokenStart, String prefix) {
        view.completion.refilter(filtered, tokenStart, prefix, completionRowsVisible());
        // La complétion qui s'ouvre referme le hover
        // (exclusion mutuelle — le tooltip est refermé quand la complétion
        // s'ouvre, et le hover est refusé tant qu'elle est ouverte).
        if (view.quickDocVisible) popups.dismissQuickDoc();
        view.invalidate();
    }

    boolean completionSelectUp() {
        if (!view.completion.visible) return false;
        if (view.completion.select(-1, completionRowsVisible())) {
            view.invalidate();
        }
        return true;
    }

    boolean completionSelectDown() {
        if (!view.completion.visible) return false;
        if (view.completion.select(1, completionRowsVisible())) {
            view.invalidate();
        }
        return true;
    }

    boolean completionAccept() {
        if (!view.completion.visible || view.completion.selected < 0
            || view.completion.selected >= view.completion.items.size()
            || view.completion.tokenStart < 0) {
            return false;
        }
        jo.codeeditor.completion.CompletionSession.Item item = view.completion.items.get(view.completion.selected);
        int caret = view.session.getSelection().start;
        // ★ B21b : garde de cohérence — si le caret a RECULÉ avant le début
        // du token (flèches, clic pendant le popup), l'insertion
        // [tokenStart, caret) écraserait une plage inversée AVANT le mot :
        // rejeter l'acceptation, le popup se referme sans muter le texte.
        if (caret < view.completion.tokenStart) {
            dismissCompletion();
            return false;
        }
        if (view.session.isComposing()) {
            view.session.imeFinishComposing();
            if (view.imeBridge != null && !view.imeBridge.listener.isSyncingExtractedText()) {
                view.imeBridge.listener.onRestartInput();
            }
        }
        // L1/D-01 : plage de remplacement — le candidat peut transporter
        // une édition (textEdit, sémantique remplacement) qui couvre tout
        // le texte à écraser, y compris AVANT le token courant. Elle est
        // appliquée EXACTEMENT (jamais le token ET la plage — c'est la
        // double application qui produit « console.console.log »).
        // Sans édition : sémantique historique du token.
        int replaceStart = item.replaceStart >= 0
                ? item.replaceStart : view.completion.tokenStart;
        int replaceEnd = item.replaceEnd >= 0
                ? item.replaceEnd : caret;
        if (item.isSnippet) {
            // Format snippet : expansion par la session de snippet —
            // insertion du texte apuré (aucun marqueur $ résiduel),
            // sélection du premier champ, navigation Tab/Shift+Tab,
            // miroirs et listener de champ.
            view.session.snippets.apply(replaceStart, replaceEnd, item.insertText);
        } else {
            view.session.replaceRange(replaceStart, replaceEnd, item.insertText);
        }
        view.onTextChanged();
        dismissCompletion();
        // Variables de choix (4.5.0) : un premier champ « ${1|a,b,c|} »
        // ouvre immédiatement sa carte d'options au-dessus de la ligne
        // de frappe (sans effet sur les champs libres).
        if (view.session.snippets.isActive()) {
            popups.showSnippetChoicesIfNeeded();
        }
        // Apprentissage du classement (4.5.0) : l'acceptation nourrit
        // l'historique par (langage, identifiant de tête) — les
        // prochaines listes placeront ce candidat devant à palier et
        // groupe égaux (fréquence puis fraîcheur). Sans effet quand la
        // bascule est fermée ; l'identifiant de tête, pas le libellé
        // entier, pour ré-apparier les signatures « foo() ».
        view.completionHistory.record(view.session.getLanguage(),
                jo.codeeditor.completion.CompletionMatcher.leadingIdentifier(item.label));
        // Action post-insertion transportée par le backend —
        // ex: application des additionalTextEdits LSP (AUTO-IMPORT d'un
        // type non importé). Exécutée APRÈS l'insertion de l'identifiant :
        // l'édition d'import pointe toujours AVANT le curseur donc les
        // offsets restent valides, et le didChange partira après celui de
        // l'identifiant (les listeners de session sont déjà réarmés).
        if (item.attachment instanceof Runnable) {
            try {
                ((Runnable) item.attachment).run();
            } catch (Exception e) {
                // Ligne de token illisible (offsets périmés) — ignorée.
                jo.codeeditor.util.EditorLog.warn("EditorCompletion",
                    "analyse du token échouée", e);
            }
        }
        return true;
    }

    /**
     * Rafraîchit le popup de complétion — version ASYNCHRONE.
     *
     * <p>Modèle appliqué :</p>
     * <ol>
     *   <li><b>Chemin rapide idem-token</b> (Étendre) : filtre local du cache
     *       base, zéro aller-retour, inchangé.</li>
     *   <li><b>Nouveau token / trigger</b> (Réouvrir) : classification O(1)
     *   sur le thread UI puis requête moteur ANNULABLE (génération +
     *   debounce) exécutée HORS thread UI ; livraison latest-wins
     *   seulement si le caret est toujours dans le token demandé.</li>
     *   <li><b>Préfixe vide</b> : légitime UNIQUEMENT après un char
     *   déclencheur (ex: {@code foo.} → membres). Sinon dismiss.</li>
     * </ol>
     *
     * <p>Anti-scintillement : pendant qu'une requête vole, l'ancienne liste
     * reste visible (inchangée) ; elle est remplacée sous les yeux quand la
     * réponse utile arrive.</p>
     */
    void refreshCompletion() {
        if (view.session == null) return;
        // Mode gros fichiers actif (4.5.0) : la complétion
        // AUTO-DÉCLENCHÉE est coupée — coloration, frappe et undo
        // continuent ; l'hôte garde la voie explicite
        // (setCompletionItems / refreshCompletionFromHost).
        if (view.isLargeFileModeActive()) {
            dismissCompletionSoft();
            return;
        }
        int caret = view.session.getSelection().start;
        // ★ B19/21 : début de token lu SUR LA ROPE (charAt en O(log n),
        // aucune matérialisation) — getText() recopiait le document ENTIER
        // à chaque frappe juste pour lire le mot courant.
        CharSequence text = view.session.getDocument().charSequence();
        int tokenStart = tokenStartAt(text, caret);
        String prefix = view.session.getDocument().subText(tokenStart, caret);

        // ── Lot 3 (audit v3.41.0), ADR-002 C1 : context gate — pas de
        // complétion dans les chaînes ou les commentaires (politique
        // STANDARD), jamais pour les langages à porte OFF (logs), porte
        // écartée pour les langages à porte ALWAYS (XML). Le popup
        // n'apporte rien dans du texte non-code et clignote
        // intempestivement quand l'utilisateur tape dans une chaîne.
        // La politique est une donnée du profil (CompletionGate) —
        // défauts par famille, surcharge par langage.
        if (isInCodeContext(text, caret)) {
            // OK, on continue.
        } else {
            dismissCompletion();
            return;
        }

        // ── Étendre : même token, base en cache. ──
        if (!prefix.isEmpty()
                && view.completion.baseTokenStart == tokenStart
                && !view.completion.baseItems.isEmpty()) {
            // Base signalée TRONQUÉE par le fournisseur : le filtrage
            // local donne le retour visuel immédiat, mais une base
            // amputée ne peut pas faire réapparaître un candidat écarté
            // par le plafond du backend (monotonie des correspondances :
            // filtrer davantage ne recrée pas ce qui a été retiré avant
            // le filtre). On relance donc une requête débouncée à CHAQUE
            // édition qui modifie le préfixe — prolongation comme
            // raccourcissement (un retour arrière élargit le champ des
            // candidats attendus : c'est précisément le cas où la base
            // tronquée est la plus fausse).
            if (view.completion.baseIncomplete && honorIncompleteCompletion) {
                applyLocalFilter(tokenStart, prefix);
                // Coalescence : une requête COURANTE pour ce token vole
                // déjà — on ne l'empile pas ; sa livraison refiltrera
                // contre le préfixe courant et une re-requête partira si
                // le préfixe a encore évolué pendant le vol.
                if (!fetchInFlight || inflightGen != completionGeneration.get()) {
                    scheduleAsyncFetch(tokenStart, false, null);
                }
                return;
            }
            // Base complète : filtrage local pur, réactif — la réponse en
            // vol pour ce token est jetée (le filtrage local la remplace).
            completionGeneration.incrementAndGet(); // jette toute réponse en vol pour ce token
            cancelPendingFetch();
            applyLocalFilter(tokenStart, prefix);
            return;
        }

        // ── Préfixe vide : ouverture légitime après un char déclencheur ? ──
        // Le caractère reconnu est capturé pour le contexte LSP
        // (triggerKind = TriggerCharacter) — le serveur affine alors ses
        // propositions (membres d'une instance après « . »).
        boolean triggeredByTriggerChar = false;
        char triggerChar = 0;
        if (prefix.isEmpty()) {
            if (caret > 0) {
                char prev = text.charAt(caret - 1);
                for (String t : triggerCharacters()) {
                    if (t != null && !t.isEmpty() && prev == t.charAt(0)) {
                        triggeredByTriggerChar = true;
                        triggerChar = prev;
                        break;
                    }
                }
            }
            if (!triggeredByTriggerChar) {
                dismissCompletion();
                return;
            }
        }
        // ── Coalescence : une requête COURANTE pour CE token vole déjà —
        // la frappe ne s'empile pas. La livraison refiltrera la réponse
        // contre le préfixe courant (curPrefix) ; si la base livrée est
        // encore tronquée et que le préfixe a évolué pendant le vol, une
        // re-requête débouncée partira à la livraison. Ne s'applique ni
        // aux demandes immédiates (caractère déclencheur ci-dessus,
        // Ctrl+Espace qui appelle scheduleAsyncFetch directement), ni
        // aux changements de token (le garde d'identité du token), ni
        // quand l'honneur du drapeau est coupé (comportement historique).
        if (!triggeredByTriggerChar
                && honorIncompleteCompletion
                && fetchInFlight
                && inflightGen == completionGeneration.get()
                && inflightTokenStart == tokenStart) {
            return;
        }
        // ★ Après un char déclencheur (ex : "."), on lance la
        // requête moteur IMMÉDIATEMENT (immediate=true) au lieu d'attendre
        // COMPLETION_DEBOUNCE_MS. Le contexte change radicalement après un
        // "." (membres de l'instance / sous-paquets d'un import) : le
        // debounce n'apporte rien car l'utilisateur marque une pause
        // explicite en tapant le déclencheur, et le flush synchrone garantit
        // que le serveur voit déjà le texte à jour (flushPendingChange
        // SYNCHRONE).
        // Gain perçu : ~80 ms de latence supprimés sur chaque frappe de ".".
        scheduleAsyncFetch(tokenStart, triggeredByTriggerChar,
                triggeredByTriggerChar ? triggerChar : null);
    }

    /**
     * Lot 3 (audit v3.41.0) — détermine si l'ouverture du popup de
     * complétion est légitime à la position du caret. Renvoie
     * {@code false} dans les contextes non-code :
     * <ul>
     *   <li>Le caret est à l'intérieur d'une chaîne ou d'un commentaire
     *       (ligne ou bloc) — délégué à
     *       {@code CodeContext.isInStringOrComment}.</li>
     *   <li>Le langage courant est « log » (famille LOG) — un fichier
     *       de log n'est pas du code, la complétion n'a aucun sens.</li>
     * </ul>
     * Le test est O(caret) sur le thread UI (comme findCallOpen et
     * isInString), acceptable car la frappe est debouncée. Si le
     * caret est hors d'un token valide (au début d'une ligne ou
     * après un séparateur), la complétion reste légitime (le popup
     * s'ouvrira après le prochain caractère).
     */
    private boolean isInCodeContext(CharSequence text, int caret) {
        if (view.session == null) return false;
        // ADR-002 (chantier C1) : la politique de porte est une DONNÉE du
        // profil (CompletionGate), plus un switch famille dans l'UI.
        // Historique (Lot 3 + correctif 3.70.1) : LOG → jamais de
        // complétion (texte non-code) ; XML → porte chaîne/commentaire
        // ÉCARTÉE — elle est calibrée pour les littéraux C/Java (une
        // apostrophe dans le TEXTE français « c'est » basculait l'état
        // « dans une chaîne » et tuait la complétion sur tout le reste
        // du fichier ; le curseur entre les guillemets d'une VALEUR
        // d'attribut XML « android:text="|" est une zone de complétion
        // légitime, pas un littéral). Le SERVEUR LSP XML tranche déjà
        // lui-même les zones muettes (texte, fermantes, commentaires
        // <!--, PI <?). Ces défauts descendent dans le profil ; un hôte
        // les surcharge par langage via
        // LanguageProfile.builder(...).completionGate(...).
        String lang = view.session.getLanguage();
        if (lang != null) {
            jo.codeeditor.languages.LanguageProfile profile =
                    jo.codeeditor.languages.LanguageRegistry.forName(lang);
            if (profile != null) {
                switch (profile.completionGate) {
                    case OFF:
                        return false;
                    case ALWAYS:
                        return true;
                    default:
                        break; // STANDARD : porte chaîne/commentaire.
                }
            }
        }
        // STANDARD — caret dans une chaîne ou un commentaire : pas de
        // complétion. CodeContext est package-private dans cel-core — on
        // utilise un wrapper public exposé par EditOps (même prédicat,
        // déjà utilisé par SmartTyping pour l'auto-close : chaînes,
        // commentaires de ligne et de bloc). ADR-002 (C1-é4) : la
        // variante avec langage lit les délimiteurs du profil (un
        // commentaire # Python est désormais vu, plus seulement les
        // délimiteurs C).
        return !jo.codeeditor.edit.EditOps.isInStringOrComment(text, caret, lang);
    }

    /** Point d'entrée hôte (Ctrl+Espace / bouton Cmplt) — SANS debounce,
     *  invocation EXPLICITE (triggerKind Invoked). */
    void refreshCompletionFromHost() {
        if (view.session == null) return;
        int caret = view.session.getSelection().start;
        int tokenStart = tokenStartAt(view.session.getDocument().charSequence(), caret); // ★ B19
        scheduleAsyncFetch(tokenStart, true, null);
    }

    /** Planifie la requête moteur avec debounce sauf demande explicite.
     *  {@code triggerCharacter} : caractère déclencheur capté à la frappe
     *  (transporté jusqu'au CompletionContext LSP), ou null. */
    private void scheduleAsyncFetch(int tokenStart, boolean immediate, Character triggerCharacter) {
        final int gen = completionGeneration.incrementAndGet();
        cancelPendingFetch();
        Runnable launch = () -> launchFetch(gen, tokenStart, triggerCharacter);
        android.os.Handler h = view.getHandler();
        if (!immediate && h != null) {
            completionDebounceTask = launch;
            h.postDelayed(launch, completionDebounceMs);
        } else {
            launchFetch(gen, tokenStart, triggerCharacter);
        }
    }

    /** Requête moteur sur la lane dédiée — le thread UI n'attend JAMAIS.
     *  {@code triggerCharacter} : caractère déclencheur (ou null) transporté
     *  jusqu'au fournisseur — il devient le CompletionContext LSP. */
    private void launchFetch(final int gen, final int tokenStart, final Character triggerCharacter) {
        if (gen != completionGeneration.get()) return; // superseded pendant le debounce
        if (view.session == null) return;
        fetchInFlight = true;
        // Identité du vol — la coalescence de refreshCompletion ne
        // fusionne que les frappes du MÊME token dont la génération est
        // encore courante.
        inflightGen = gen;
        // Lot 1+3 (audit v3.41.0) : on évite la copie complète du
        // document (O(n) sur le thread UI) tant qu'on n'en a pas
        // besoin. Le prefix est extrait via subText (O(log n +
        // taille du prefix), beaucoup plus rapide). La snapshot
        // complète String n'est prise que si un provider externe
        // est branché (son API prend String en paramètre).
        final int snapCaret = view.session.getSelection().start;
        final int safeTokenStart = Math.min(tokenStart, snapCaret);
        inflightTokenStart = safeTokenStart;
        final jo.codeeditor.view.EditorView.CompletionProvider provider = view.completionProvider;
        final String langName = view.session.getLanguage();
        final String prefix = view.session.getDocument().subText(safeTokenStart, snapCaret);
        final String snapText = (provider != null) ? view.session.getText() : null;
        // Gabarits (4.5.0) : ceux du profil (« libellé|corps ») et les
        // gabarits utilisateur du provider — lus sur le fil UI (cache
        // hôte), fusionnés AUX DEUX chemins (fournisseur externe comme
        // complétion intégrée) avant le classement commun.
        final List<jo.codeeditor.completion.CompletionSession.Item> templateItems =
                templateSnippetItems(langName, prefix);

        jo.codeeditor.view.EditorTaskLanes.submitCompletion(() -> {
            java.util.List<jo.codeeditor.completion.CompletionSession.Item> result = null;
            // Drapeau « liste tronquée » du fournisseur : la base livrée
            // est partielle, le filtrage local ne pourra pas faire
            // réapparaître un candidat écarté par le plafond du backend.
            boolean resultIncomplete = false;
            try {
                if (provider != null) {
                    jo.codeeditor.view.CompletionResult completionResult =
                            provider.provideResult(snapText, snapCaret, safeTokenStart,
                                    prefix, triggerCharacter);
                    result = completionResult != null ? completionResult.items : null;
                    resultIncomplete = completionResult != null && completionResult.isIncomplete;
                } else {
                    result = builtinKeywordCompletions(langName, prefix);
                }
            } catch (Exception e) {
                jo.codeeditor.util.EditorLog.warn("EditorCompletion",
                    "récupération des complétions échouée", e);
                result = null;
            }
            final java.util.List<jo.codeeditor.completion.CompletionSession.Item> fetched0 =
                    result != null ? result : java.util.Collections.emptyList();
            final java.util.List<jo.codeeditor.completion.CompletionSession.Item> fetched =
                    templateItems.isEmpty() ? fetched0 : concat(fetched0, templateItems);
            final boolean baseIncomplete = resultIncomplete;

            Runnable deliver = () -> {
                fetchInFlight = false;
                if (view.session == null) return;
                if (gen != completionGeneration.get()) return;          // superseded
                // Le caret doit toujours être dans le token demandé.
                // ★ B19 : lecture sur la rope — pas de copie complète.
                CharSequence cur = view.session.getDocument().charSequence();
                int curCaret = view.session.getSelection().start;
                if (curCaret < safeTokenStart
                        || Math.min(cur.length(), curCaret) < safeTokenStart
                        || tokenStartAt(cur, curCaret) != safeTokenStart) {
                    return; // une frappe plus récente couvrira ce cas
                }
                String curPrefix = view.session.getDocument().subText(safeTokenStart,
                        Math.min(cur.length(), curCaret));
                if (fetched.isEmpty()) {
                    view.completion.baseItems.clear();
                    view.completion.baseTokenStart = safeTokenStart;
                    view.completion.baseIncomplete = baseIncomplete;
                    if (view.completion.visible) dismissCompletionSoft();
                    return;
                }
                // Anti-blink + remplacement « sous les yeux » : la base
                // fraîche devient la référence du token courant.
                view.completion.baseItems = new ArrayList<>(fetched);
                view.completion.baseTokenStart = safeTokenStart;
                view.completion.baseIncomplete = baseIncomplete;
                applyFilteredItems(filterCompletionItems(fetched, curPrefix),
                        safeTokenStart, curPrefix);
                // Base encore tronquée + préfixe ayant évolué pendant le
                // vol : la réponse servie a été calculée pour un préfixe
                // plus ancien, elle est déjà périmée pour le préfixe
                // courant — une re-requête débouncée part maintenant
                // (l'honneur du drapeau seul la conditionne ; la
                // coalescence empêche l'empilement si une frappe
                // arrive entre-temps). Une base COMPLETE n'en déclenche
                // jamais : le filtrage local reprend ses droits.
                if (view.completion.baseIncomplete && honorIncompleteCompletion
                        && !curPrefix.equals(prefix)) {
                    scheduleAsyncFetch(safeTokenStart, false, null);
                }
            };
            android.os.Handler hh = view.getHandler();
            if (hh != null) {
                hh.post(deliver);
            } else {
                deliver.run();
            }
        });
    }

    /** Masque sans casser le cache base (utilisé par la livraison vide). */
    private void dismissCompletionSoft() {
        view.completion.softHide();
        view.invalidate();
    }

    /** Filtrage local synchrone du cache base (chemin rapide idem-token). */
    private void applyLocalFilter(int tokenStart, String prefix) {
        List<jo.codeeditor.completion.CompletionSession.Item> filtered =
            filterCompletionItems(view.completion.baseItems, prefix);
        if (filtered.isEmpty()) {
            view.completion.softHide();
            view.invalidate();
            return;
        }
        applyFilteredItems(filtered, tokenStart, prefix);
    }

    /**
     * La bande verticale écran de la ligne du TOKEN de complétion
     * (haut et bas, en coordonnées écran), fold-aware ET wrap-aware via
     * {@link EditorView#docLineToY(int)} + {@link EditorView#rowsForDocLine(int)}.
     * Le popup ne doit JAMAIS recouvrir cette bande — c'est la ligne où le
     * caret tape.
     */
    private float[] completionCaretBand() {
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(view.completion.tokenStart),
                0, doc.lineCount() - 1);
        float top = view.docLineToY(line) - view.scroll.vOffset;
        float bottom = top + view.rowsForDocLine(line) * view.metrics.getLineHeight();
        return new float[]{top, bottom};
    }

    /**
     * Nombre de rangées réellement affichables : min(cap, items)
     * RÉDUIT à ce qui tient au-dessus OU en dessous de la ligne du caret
     * (sans la recouvrir), minimum 1. Dessiner les rangées quoi qu'il
     * arrive posait problème : avec le clavier logiciel ouvert
     * (adjustResize), le popup était clamppé à Y=0 et recouvrait la ligne
     * de frappe.
     *
     * <p>4.4.0 : le panneau de documentation est DÉDUIT de la place
     * disponible — le popup complet (liste + panneau) doit tenir sans
     * recouvrir la bande du caret.</p>
     */
    int completionRowsVisible() {
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = CompletionState.ROW_HEIGHT_DP * density;
        return completionRowsVisible(completionDocPanelHeight(density), rowH);
    }

    /** Variante paramétrée (l'ancre a déjà calculé la hauteur du panneau). */
    private int completionRowsVisible(float panelH, float rowH) {
        int maxRows = Math.min(CompletionState.MAX_ROWS, view.completion.items.size());
        if (maxRows <= 1 || view.getHeight() <= 0 || view.session == null) {
            return Math.max(1, maxRows);
        }
        float[] band = completionCaretBand();
        // Espaces disponibles SANS recouvrir la ligne du caret :
        float below = view.getHeight() - (band[1] + COMPLETION_ANCHOR_GAP_PX);
        float above = band[0] - COMPLETION_ANCHOR_GAP_PX;
        float best = Math.max(below, above) - panelH;
        int fit = (int) Math.floor(best / rowH);
        return Math.max(1, Math.min(maxRows, fit));
    }

    /** Écart visuel (px) entre le popup et la ligne du caret. */
    private static final int COMPLETION_ANCHOR_GAP_PX = 4;

    /**
     * ★ 4.4.0 — Panneau de documentation de l'item SÉLECTIONNÉ : les
     * lignes plafonnées sont calculées ICI (l'ancre est la source unique
     * de géométrie) et posées dans {@code completion.docPanelLines} pour
     * le peintre ; la hauteur du panneau est retournée. 0 si le panneau
     * est absent : pas de documentation sur l'item sélectionné, vue
     * trop étroite (la documentation se réduit alors au glyphe de
     * rangée) ou place insuffisante pour AU MOINS une rangée + le
     * panneau sans recouvrir la bande du caret.
     *
     * <p>Moteur identique à la bande documentation de l'aide de
     * signature : QuickDoc (description + sections compactées), wrap
     * plafonné, ligne = 0,75 × taille de texte × 1,3.</p>
     */
    float completionDocPanelHeight(float density) {
        view.completion.docPanelLines.clear();
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return 0f;
        if (view.getWidth() < CompletionState.DOC_PANEL_MIN_VIEW_WIDTH_DP * density) {
            return 0f;   // vue trop étroite : glyphe de rangée
        }
        int sel = view.completion.selected;
        if (sel < 0 || sel >= view.completion.items.size()) return 0f;
        String documentation = view.completion.items.get(sel).documentation;
        if (documentation == null || documentation.isEmpty()) return 0f;
        // Le panneau ne doit JAMAIS forcer le popup à recouvrir la bande
        // du caret : au moins une rangée + le panneau doivent tenir d'un
        // côté de la bande.
        float[] band = completionCaretBand();
        float below = view.getHeight() - (band[1] + COMPLETION_ANCHOR_GAP_PX);
        float above = band[0] - COMPLETION_ANCHOR_GAP_PX;
        float best = Math.max(below, above);
        float rowH = CompletionState.ROW_HEIGHT_DP * density;
        // Mesure avec la discipline du paint partagé : taille posée puis
        // RESTAURÉE (jamais d'état résiduel pour les couches suivantes).
        float savedSize = view.textPaint.getTextSize();
        List<String> lines = new ArrayList<>();
        try {
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            float docWidth = CompletionState.WIDTH_DP * density - 16 * density;
            jo.codeeditor.doc.QuickDoc.QuickDocContent parsed =
                    jo.codeeditor.doc.QuickDoc.parseQuickDoc(documentation, "java");
            if (!parsed.description.isEmpty()) {
                lines.addAll(jo.codeeditor.view.render.EditorAssistPopupPainter
                        .wrapText(parsed.description, docWidth, view.textPaint));
            }
            for (jo.codeeditor.doc.QuickDoc.DocSection s : parsed.sections) {
                if (lines.size() >= CompletionState.DOC_PANEL_MAX_LINES) break;
                for (String item : s.items) {
                    if (lines.size() >= CompletionState.DOC_PANEL_MAX_LINES) break;
                    String line = s.title + " " + item;
                    lines.addAll(jo.codeeditor.view.render.EditorAssistPopupPainter
                            .wrapText(line, docWidth, view.textPaint));
                }
            }
        } finally {
            view.textPaint.setTextSize(savedSize);
        }
        if (lines.isEmpty()) return 0f;
        if (lines.size() > CompletionState.DOC_PANEL_MAX_LINES) {
            lines = new ArrayList<>(lines.subList(0, CompletionState.DOC_PANEL_MAX_LINES));
        }
        float docRowH = view.metrics.getTextSize() * 0.75f * 1.3f;
        float panelH = lines.size() * docRowH + 8 * density;
        if (panelH + rowH > best) {
            return 0f;   // pas la place : la documentation se réduit au glyphe
        }
        view.completion.docPanelLines.addAll(lines);
        return panelH;
    }

    float[] completionPopupAnchor() {
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = CompletionState.ROW_HEIGHT_DP * density;
        float width = CompletionState.WIDTH_DP * density;
        // ★ 4.4.0 : le panneau de documentation est calculé AVANT les
        // rangées (source unique) et déduit de la place disponible —
        // le popup complet (liste + panneau) ne recouvre jamais la bande
        // du caret.
        float panelH = completionDocPanelHeight(density);
        int rowsToShow = completionRowsVisible(panelH, rowH);
        float popupH = rowH * rowsToShow + panelH;
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(view.completion.tokenStart), 0, doc.lineCount() - 1);
        int col = view.completion.tokenStart - doc.lineStart(line);
        // Ancrage tenant compte des inlays — le popup suit le X du caret tissé.
        float anchorX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
            + view.visualColFor(line, col) * view.metrics.getCharWidth() - view.scroll.hOffset;
        // Bas/haut de la ligne fold+wrap-aware (l'ancienne formule brute
        // padTop + (line+1)*lineHeight plaçait l'ancre au-dessus de la
        // rangée visuelle réelle dès qu'une ligne précédente était wrappée
        // ou pliée → le popup recouvrait la ligne de frappe).
        float[] band = completionCaretBand();
        float caretBottomY = band[1];
        float caretTopY = band[0];
        // D2 : pattern « popup ancré » factorisé (préfère EN DESSOUS de la
        // ligne de frappe, bascule AU-DESSUS s'il ne tient pas — le popup
        // ne recouvre JAMAIS la ligne du caret), clamp horizontal à la
        // gouttière. Paramétrage identique au calcul historique :
        // gap 4 px, yMargin 0, seuil X 0, inset X 4 px, ancre gauche.
        float[] pos = EditorPopupAnchors.anchorAboveBelow(view,
                anchorX, caretTopY, caretBottomY, width, popupH,
                false, COMPLETION_ANCHOR_GAP_PX, 0f,
                0f, 4f, false, view.metrics.getGutterWidth());
        // 6ᵉ élément (4.4.0) : hauteur du panneau de documentation —
        // peintre et hit-testeur en déduisent la zone de liste.
        return new float[]{pos[0], pos[1], width, popupH, rowH, panelH};
    }

    /**
     * Filtre les candidats par préfixe puis les re-classe : palier
     * (exact / exact insensible / préfixe / préfixe insensible / camel
     * humps / sous-séquence — échelle à 6 niveaux de
     * {@link jo.codeeditor.completion.CompletionMatcher}), puis groupe
     * à palier égal (symbole résolu avant mot-clé avant snippet avant
     * mot du buffer — un mot-clé tapé en entier reste en tête de son
     * palier). L'{@code filterText} posé par le fournisseur sert de
     * repli quand le libellé n'apparie nulle part. Tri STABLE : à
     * l'intérieur d'un couple (palier, groupe), l'ordre du serveur est
     * conservé.
     */
    private List<jo.codeeditor.completion.CompletionSession.Item> filterCompletionItems(
            List<jo.codeeditor.completion.CompletionSession.Item> items, String prefix) {
        if (prefix.isEmpty()) return items;
        List<jo.codeeditor.completion.CompletionSession.Item> out = new ArrayList<>();
        for (jo.codeeditor.completion.CompletionSession.Item item : items) {
            if (jo.codeeditor.completion.CompletionMatcher.tier(
                    item.label, item.filterText, prefix)
                    != jo.codeeditor.completion.CompletionMatcher.TIER_NONE) {
                out.add(item);
            }
        }
        // Classement : palier, puis groupe, puis apprentissage (4.5.0).
        // Le 3ᵉ départage arrive APRÈS (palier, groupe) — un candidat
        // déjà accepté remonte à l'intérieur de son couple (palier,
        // groupe) sans jamais franchir les barrières. Les inconnus ont
        // un boost nul : à clés égales le tri stable conserve l'ordre
        // du serveur (garde des tests 4.3.0).
        java.util.Comparator<jo.codeeditor.completion.CompletionSession.Item> ranking =
                java.util.Comparator
                        .comparingInt((jo.codeeditor.completion.CompletionSession.Item i) ->
                                jo.codeeditor.completion.CompletionMatcher.tier(
                                        i.label, i.filterText, prefix))
                        .thenComparingInt(i -> jo.codeeditor.completion.CompletionMatcher.group(
                                i.isKeyword, i.isSnippet, i.kind));
        if (view.completionHistory.isEnabled()) {
            final String histLang = view.session.getLanguage();
            ranking = ranking.thenComparingLong(i -> -view.completionHistory.boost(histLang,
                    jo.codeeditor.completion.CompletionMatcher.leadingIdentifier(i.label)));
        }
        out.sort(ranking);
        return out;
    }

    /** Concatène deux listes en préservant l'ordre (base puis gabarits). */
    private static List<jo.codeeditor.completion.CompletionSession.Item> concat(
            List<jo.codeeditor.completion.CompletionSession.Item> a,
            List<jo.codeeditor.completion.CompletionSession.Item> b) {
        List<jo.codeeditor.completion.CompletionSession.Item> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * Les items de gabarits (4.5.0) — profil et utilisateur — pour le
     * langage et le préfixe courants. Chaque gabarit remonte par son
     * LIBELLÉ et s'insère par son CORPS expansé (isSnippet=true) :
     * champs, miroirs, variables de choix et macros inclus. Les
     * gabarits sont filtrés par préfixe comme les autres candidats
     * (préfixe vide = tous, voie déclencheur « . »).
     */
    private List<jo.codeeditor.completion.CompletionSession.Item> templateSnippetItems(
            String language, String prefix) {
        List<jo.codeeditor.completion.CompletionSession.Item> out = new ArrayList<>();
        if (language == null) return out;
        // 1. Gabarits déclarés par le profil de langage.
        jo.codeeditor.languages.LanguageProfile profile =
                jo.codeeditor.languages.LanguageRegistry.forName(language);
        if (profile != null) {
            for (String entry : profile.snippetTemplates) {
                int sep = entry.indexOf('|');
                if (sep <= 0) continue;
                String label = entry.substring(0, sep);
                String body = entry.substring(sep + 1);
                addTemplateItem(out, label, body, "gabarit", prefix);
            }
        }
        // 2. Gabarits utilisateur (stockage hôte, cache lu sur le fil UI).
        try {
            if (view.language != null && view.language.getSnippetProvider() != null) {
                for (jo.codeeditor.lang.model.SnippetTemplate t
                        : view.language.getSnippetProvider().snippets(language)) {
                    if (t == null || t.label == null || t.body == null) continue;
                    addTemplateItem(out, t.label, t.body, t.detail, prefix);
                }
            }
        } catch (Exception e) {
            jo.codeeditor.util.EditorLog.warn("EditorCompletion",
                "lecture des gabarits utilisateur échouée", e);
        }
        return out;
    }

    /** Filtre par préfixe puis ajoute l'item de gabarit (expansible). */
    private static void addTemplateItem(
            List<jo.codeeditor.completion.CompletionSession.Item> out,
            String label, String body, String detail, String prefix) {
        if (label.isEmpty() || body.isEmpty()) return;
        if (prefix != null && !prefix.isEmpty()
                && jo.codeeditor.completion.CompletionMatcher.tier(label, null, prefix)
                        == jo.codeeditor.completion.CompletionMatcher.TIER_NONE) {
            return;
        }
        out.add(new jo.codeeditor.completion.CompletionSession.Item(
                label, detail != null ? detail : "gabarit", body, "s", 15, 0,
                false, true, null));
    }

    private static List<jo.codeeditor.completion.CompletionSession.Item> builtinKeywordCompletions(
            String language, String prefix) {
        List<jo.codeeditor.completion.CompletionSession.Item> out = new ArrayList<>();
        // Lot 3 (audit v3.41.0) : la liste est désormais pilotée
        // par le registre des langages. Tous les langages
        // enregistrés (java, kotlin, c, c++, go, rust, python, js,
        // ts, sql, shell, yaml, json, lua, ruby, php, swift, dart,
        // groovy, scala, smali, etc.) bénéficient désormais de la
        // complétion de mots-clés — ils n'étaient que 4 avant
        // (java, kotlin, xml, markdown).
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        // ADR-002 (C1, D5 « source unique ») : les snippets vivent
        // dans le profil (la liste Markdown historique est descendue
        // dans la table intégrée — mêmes entrées, même ordre). Tout
        // langage enregistré peut déclarer les siens, aucun code UI
        // dédié. Même filtre par préfixe, même format d'item.
        jo.codeeditor.languages.LanguageProfile profile =
                jo.codeeditor.languages.LanguageRegistry.forName(language);
        if (profile == null) return out;
        for (String s : profile.snippets) {
            if (s.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)
                    && s.length() > prefix.length()) {
                out.add(new jo.codeeditor.completion.CompletionSession.Item(
                    s, "snippet", s, "s", 15, 0, false, false, null));
            }
        }
        // Mots-clés du registre : tous langages enregistrés.
        if (profile.keywords == null || profile.keywords.isEmpty()) {
            return out;
        }
        for (String kw : profile.keywords) {
            if (kw == null || kw.isEmpty()) continue;
            if (kw.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)
                    && kw.length() > prefix.length()) {
                out.add(new jo.codeeditor.completion.CompletionSession.Item(
                    // ★ Kind 14 (Keyword, numérotation LSP moderne) — le
                    // badge du popup en dépend. isKeyword=true reste
                    // inchangé.
                    kw, "keyword", kw, "k", 14, 0, true, false, null));
            }
        }
        return out;
    }
}

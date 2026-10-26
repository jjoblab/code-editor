package jo.codeeditor.view.chrome;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.find.FindOptions;
import jo.codeeditor.find.FindReplace;
import jo.codeeditor.find.Match;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contrôleur de recherche/remplacement dans le fichier (B10) — l'état et
 * les actions, SANS une ligne d'UI : la vue est {@link EditorSearchBar},
 * qui délègue tout ici.
 *
 * <p>Le moteur de correspondance est celui de cel-core
 * ({@link FindReplace} : insensible à la casse, mot entier, regex) ; le
 * surlignage du viewport est l'API existante
 * {@link EditorView#setFindHighlights(List, int)} (teintes
 * {@code theme.findMatch} / {@code theme.findCurrent}), la navigation
 * passe par {@link EditorView#scrollToOffset(int)}.</p>
 *
 * <p><b>Comportements</b> :
 * <ul>
 *   <li><b>Recherche vivante</b> — les correspondances se recalculent à
 *       chaque changement de requête (via un rafraîchissement différée
 *       posé par la vue) ET à chaque édition du document (accroché au
 *       listener de texte de la session, ré-accroché quand la session
 *       change : {@link #refresh()}) ;</li>
 *   <li><b>Navigation</b> — suivant/précédent bouclent (miroir de
 *       {@link FindReplace#matchIndexFrom}), l'occurrence courante est
 *       teintée « courante » et le viewport l'amène ; le caret suit le
 *       DÉBUT de l'occurrence courante (sans sélection : la pill et les
 *       poignées n'interfèrent pas avec la barre) ;</li>
 *   <li><b>Remplacement</b> — l'occurrence courante (mode plain : splice
 *       ciblé ; mode regex : expansion des groupes {@code $1}) et TOUT
 *       le document en UNE seule passe (une seule entrée d'annulation,
 *       caret restauré) ; après remplacement la recherche se relance et
 *       la courante devient la première occurrence à partir de la fin du
 *       texte inséré ;</li>
 *   <li><b>Document échangé</b> — les offsets de l'ancien document sont
 *       périmés : {@code EditorView.setSession} purge le surlignage, et
 *       {@link #refresh()} ré-accroche la session courante avant de
 *       recalculer.</li>
 * </ul></p>
 *
 * <p>État OBSERVABLE pour la vue : {@link #getMatchCount()},
 * {@link #getCurrentIndex()} (affichage « 3/17 »),
 * {@link #isReplaceMode()}. Méthodes synchrones — testables sans
 * cadence d'animation.</p>
 */
public class EditorSearchController {

    /** Notifié après chaque recalcul (compte + index courants). */
    public interface OnMatchesChangedListener {
        void onMatchesChanged(int count, int currentIndex);
    }

    private final EditorView view;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String query = "";
    private FindOptions options = new FindOptions();
    private final List<Match> matches = new ArrayList<>();
    private int current = -1;
    private boolean replaceMode = false;
    private OnMatchesChangedListener matchesListener;

    /** D-05 : la dernière recherche a dépassé le budget (expression trop coûteuse). */
    private boolean tooCostly = false;

    /** D-05 : la collecte s'est arrêtée au plafond de résultats. */
    private boolean truncated = false;

    /** Session dont le listener de texte est accroché (null = aucune). */
    private EditorSession hookedSession;

    /** Recalcul différé en vol (une seule réunion des rafraîchissements). */
    private final Runnable refreshRunnable = this::refresh;

    public EditorSearchController(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // État
    // ════════════════════════════════════════════════════════════════

    public String getQuery() { return query; }

    public FindOptions getOptions() { return options; }

    public boolean isReplaceMode() { return replaceMode; }

    public int getMatchCount() { return matches.size(); }

    /** Index de l'occurrence courante (−1 si aucune). */
    public int getCurrentIndex() { return current; }

    /**
     * D-05 — la dernière recherche a dépassé le budget temps : le motif
     * est trop coûteux pour ce document (l'UI affiche l'avertissement,
     * aucune correspondance partielle n'est fiable).
     */
    public boolean isTooCostly() { return tooCostly; }

    /**
     * D-05 — la collecte s'est arrêtée au plafond de résultats
     * ({@code FindReplace.MAX_MATCHES}) : le compte affiché est un
     * plancher (« N+ »).
     */
    public boolean isTruncated() { return truncated; }

    public void setMatchesListener(OnMatchesChangedListener listener) {
        this.matchesListener = listener;
    }

    /**
     * Requête de recherche. Le recalcul est DIFFÉRÉ (cadence de frappe) —
     * passer par {@link #refresh()} pour un recalcul immédiat (tests,
     * actions).
     */
    public void setQuery(String q) {
        this.query = q != null ? q : "";
        scheduleRefresh();
    }

    /** Bascule « respecter la casse » — recalcul immédiat. */
    public void setCaseSensitive(boolean on) {
        options = options.withCaseSensitive(on);
        refresh();
    }

    /** Bascule « mot entier » — recalcul immédiat. */
    public void setWholeWord(boolean on) {
        options = options.withWholeWord(on);
        refresh();
    }

    /** Bascule « expression régulière » — recalcul immédiat. */
    public void setRegex(boolean on) {
        options = options.withRegex(on);
        refresh();
    }

    /** Bascule l'affichage de la rangée de remplacement. */
    public void setReplaceMode(boolean on) {
        this.replaceMode = on;
    }

    // ════════════════════════════════════════════════════════════════
    // Cycle de vie
    // ════════════════════════════════════════════════════════════════

    /**
     * Ouvre la recherche : ré-accroche la session, préremplit la requête
     * depuis la sélection si elle tient sur une ligne (l'habitude des
     * éditeurs : sélectionner un mot puis chercher), calcule les
     * correspondances et amène la première à partir du caret (bouclage
     * inclus).
     *
     * @param prefillFromSelection la sélection courante de la session
     * @return la requête effective après amorçage
     */
    public String open(CharSequence prefillFromSelection) {
        ensureSessionHooked();
        if (query.isEmpty() && prefillFromSelection != null) {
            String s = prefillFromSelection.toString();
            // Une seule ligne, non vide : un mot/identifiant sélectionné
            // est la requête la plus probable (les sélections multi-lignes
            // ne sont presque jamais une requête).
            if (!s.isEmpty() && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
                query = s;
            }
        }
        refresh();
        return query;
    }

    /**
     * Referme la recherche : surlignage vidé, listener de texte
     * détaché. La requête et les options SURVIVENT (réouverture sans
     * re-frapper).
     */
    public void close() {
        handler.removeCallbacks(refreshRunnable);
        detachSessionHook();
        matches.clear();
        current = -1;
        // 4.4.0 : la voie find est rendue — le producteur interne
        // « occurrences du mot » pourra reprendre la main au prochain
        // déplacement du caret ou à la prochaine édition.
        view.find.searchOwned = false;
        view.setFindHighlights(null, -1);
        notifyMatches();
    }

    /**
     * Recalcule TOUT : ré-accroche la session si elle a changé (changement
     * d'onglet/fichier), recalcule les correspondances depuis le texte
     * COURANT, replace la courante sur la première occurrence à partir du
     * caret (bouclage) et pousse le surlignage. À appeler après un
     * changement de session, une bascule d'option, une édition.
     */
    public void refresh() {
        handler.removeCallbacks(refreshRunnable);
        // 4.4.0 : la session de recherche possède la voie find tant
        // qu'elle n'est pas refermée — le producteur interne
        // « occurrences du mot » s'efface devant elle.
        view.find.searchOwned = true;
        ensureSessionHooked();
        EditorSession session = view.getSession();
        matches.clear();
        current = -1;
        tooCostly = false;
        truncated = false;
        if (session != null && !query.isEmpty()) {
            // D-05 : entrée SÛRE — budget temps en mode regex, plafond
            // de résultats dans tous les modes, états exposés à l'UI.
            FindReplace.SearchResult résultat = FindReplace.find(
                    session.getText(), query, options);
            matches.addAll(résultat.matches);
            tooCostly = résultat.tooCostly;
            truncated = résultat.truncated;
            if (!matches.isEmpty()) {
                int caret = session.getSelection().start;
                current = FindReplace.matchIndexFrom(matches, caret);
            }
        }
        view.setFindHighlights(matches, current);
        notifyMatches();
    }

    /** Ré-accroche le listener sur la session courante si elle a changé. */
    private void ensureSessionHooked() {
        EditorSession session = view.getSession();
        if (session == hookedSession) return;
        detachSessionHook();
        if (session != null) {
            session.addOnTextEditListener(textHook);
            hookedSession = session;
        }
    }

    private void detachSessionHook() {
        if (hookedSession != null) {
            hookedSession.removeOnTextEditListener(textHook);
            hookedSession = null;
        }
    }

    /** Édition dans le document → recalcul différé (cadence de frappe). */
    private final EditorSession.OnTextEditListener textHook =
            (start, end, inserted) -> scheduleRefresh();

    /** Programme le recalcul (160 ms) — réuni les frappes rapprochées. */
    public void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 160L);
    }

    private void notifyMatches() {
        if (matchesListener != null) {
            matchesListener.onMatchesChanged(matches.size(), current);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Navigation
    // ════════════════════════════════════════════════════════════════

    /**
     * Occurrence suivante (boucle au début après la dernière). La courante
     * est teintée, le viewport l'amène, le caret va à son début.
     *
     * @return l'index de la nouvelle courante (−1 si aucune)
     */
    public int next() {
        return step(1);
    }

    /** Occurrence précédente (boucle à la fin après la première). */
    public int prev() {
        return step(-1);
    }

    private int step(int delta) {
        refresh();
        if (matches.isEmpty()) {
            current = -1;
            view.setFindHighlights(matches, -1);
            notifyMatches();
            return -1;
        }
        current = (current < 0 ? 0 : (current + delta + matches.size())
                % matches.size());
        applyCurrent();
        view.setFindHighlights(matches, current);
        notifyMatches();
        return current;
    }

    /**
     * Teinte la courante, amène le viewport sur elle et place le caret à
     * son début (sans sélection : les poignées et la pill restent celles
     * de l'utilisateur). La fermeture de la barre laisse donc le caret
     * prêt à éditer à l'endroit exploré.
     */
    private void applyCurrent() {
        EditorSession session = view.getSession();
        if (session == null || current < 0 || current >= matches.size()) return;
        Match m = matches.get(current);
        session.setSelection(m.start);
        view.scrollToOffset(m.start);
    }

    // ════════════════════════════════════════════════════════════════
    // Remplacement
    // ════════════════════════════════════════════════════════════════

    /**
     * Remplace l'occurrence COURANTE par {@code replacement}.
     *
     * <p>Mode plain/mot entier : splice ciblé sur les bornes de
     * l'occurrence. Mode regex : expansion des groupes
     * ({@code $1}, {@code $2}…) du texte REMPLACÉ dans le remplacement
     * — un remplacement mal formé ({@code $} orphelin) retombe sur le
     * littéral. Après le remplacement, la recherche se relance et la
     * courante devient la première occurrence à partir de la fin du texte
     * inséré (enchaîner « remplacer » remplace bien TOUTES les
     * occurrences, une par une).</p>
     *
     * @return true si une occurrence a été remplacée
     */
    public boolean replaceCurrent(String replacement) {
        EditorSession session = view.getSession();
        if (session == null || replacement == null) return false;
        refresh();
        if (current < 0 || current >= matches.size()) return false;
        Match m = matches.get(current);
        String text = session.getText();
        String expanded = options.regex
                ? expandRegexReplacement(text, m, replacement) : replacement;
        session.replaceRangeWithCaret(m.start, m.end, expanded, m.start);
        refreshAfterReplace(m.start + expanded.length());
        return true;
    }

    /**
     * Remplace TOUTES les occurrences par {@code replacement} — UNE seule
     * mutation du document (une seule entrée d'annulation), construite en
     * une passe : segments de texte d'origine + remplacements développés
     * (groupes {@code $1} en mode regex). Le caret est restauré à sa
     * position d'origine (bornée à la nouvelle longueur).
     *
     * @return le nombre de remplacements effectués
     */
    public int replaceAll(String replacement) {
        EditorSession session = view.getSession();
        if (session == null || replacement == null || query.isEmpty()) return 0;
        String text = session.getText();
        int oldCaret = session.getSelection().start;

        StringBuilder sb = new StringBuilder(text.length());
        int count;
        if (options.regex) {
            count = appendRegexReplacements(sb, text, replacement);
        } else {
            count = appendPlainReplacements(sb, text, replacement);
        }
        if (count == 0) return 0;

        String newText = sb.toString();
        int caret = Math.min(oldCaret, newText.length());
        session.replaceRangeWithCaret(0, text.length(), newText, caret);
        refreshAfterReplace(caret);
        return count;
    }

    /**
     * Reconstruit le texte avec les occurrences remplacées, la courante
     * repartant de {@code fromOffset} (fin du dernier remplacement).
     */
    private void refreshAfterReplace(int fromOffset) {
        ensureSessionHooked();
        matches.clear();
        current = -1;
        tooCostly = false;
        truncated = false;
        EditorSession session = view.getSession();
        if (session != null && !query.isEmpty()) {
            FindReplace.SearchResult résultat = FindReplace.find(
                    session.getText(), query, options);
            matches.addAll(résultat.matches);
            tooCostly = résultat.tooCostly;
            truncated = résultat.truncated;
            // Première occurrence à partir de la fin du remplacement ;
            // bouclage si la fin du document est dépassée.
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i).start >= fromOffset) { current = i; break; }
            }
            if (current < 0 && !matches.isEmpty()) current = 0;
        }
        view.setFindHighlights(matches, current);
        notifyMatches();
        if (current >= 0) {
            view.scrollToOffset(matches.get(current).start);
        }
    }

    /**
     * Passe de remplacement en mode plain/mot entier : index des
     * occurrences recalculés sur le texte d'origine, segments copiés
     * entre les correspondances.
     */
    private int appendPlainReplacements(StringBuilder out, String text,
            String replacement) {
        List<Match> found = FindReplace.findMatches(text, query, options);
        int copied = 0;
        for (Match m : found) {
            out.append(text, copied, m.start).append(replacement);
            copied = m.end;
        }
        out.append(text, copied, text.length());
        return found.size();
    }

    /**
     * Passe de remplacement en mode regex : matcher vivant sur le texte
     * d'origine, appendReplacement développe les groupes {@code $1} à
     * chaque occurrence, appendTail copie la queue. Un motif invalide ne
     * remplace rien (parité du moteur de recherche : aucun match).
     *
     * <p>Le tampon intermédiaire est un StringBuffer : la surcharge
     * StringBuilder de appendReplacement n'arrive qu'au niveau d'API 34,
     * la surcharge StringBuffer existe depuis l'API 1.</p>
     */
    private int appendRegexReplacements(StringBuilder out, String text,
            String replacement) {
        try {
            // D-05 : Pattern centralisé dans FindReplace + matcher
            // GARDE-FOU — un motif catastrophique avorte la passe au
            // budget au lieu de figer le thread appelant.
            Pattern pattern = FindReplace.compilePattern(query, options);
            Matcher matcher = FindReplace.guardedMatcher(pattern, text);
            StringBuffer buffer = new StringBuffer(
                    text.length() + 16);
            int count = 0;
            while (matcher.find()) {
                matcher.appendReplacement(buffer, replacement);
                count++;
            }
            matcher.appendTail(buffer);
            out.append(buffer);
            return count;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            // Motif ou remplacement invalide ($ orphelin, groupe absent) :
            // aucun remplacement — la barre affiche le compte inchangé.
            out.setLength(0);
            out.append(text);
            return 0;
        } catch (FindReplace.RegexBudgetExceededException e) {
            // Motif trop coûteux : AUCUN remplacement (le document n'est
            // pas muté à moitié) — l'état tooCostly est posé par le
            // refresh qui suit.
            out.setLength(0);
            out.append(text);
            return 0;
        }
    }

    /**
     * Développe les groupes du remplacement pour UNE occurrence (mode
     * regex) : matcher borné à la région de l'occurrence — le préfixe
     * ajouté par appendReplacement fait exactement la longueur du début
     * de l'occurrence, on le retire. Repli : le littéral (région qui ne
     * matche pas pile, remplacement mal formé).
     */
    private String expandRegexReplacement(String text, Match m, String replacement) {
        try {
            Matcher matcher = FindReplace.guardedMatcher(
                    FindReplace.compilePattern(query, options), text);
            matcher.region(m.start, m.end);
            if (matcher.find() && matcher.start() == m.start) {
                // StringBuffer (pas StringBuilder) : la surcharge
                // StringBuilder d'appendReplacement n'arrive qu'API 34.
                StringBuffer sb = new StringBuffer();
                matcher.appendReplacement(sb, replacement);
                if (sb.length() >= m.start) {
                    return sb.substring(m.start);
                }
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            // Repli littéral ci-dessous.
        } catch (FindReplace.RegexBudgetExceededException e) {
            // Motif trop coûteux : repli littéral ci-dessous (le document
            // n'est pas muté à moitié).
        }
        return replacement;
    }

    // ════════════════════════════════════════════════════════════════
    // Session sans texte : garde partagée
    // ════════════════════════════════════════════════════════════════

    /** Texte courant de la session ("" sans session). */
    String currentText() {
        EditorSession session = view.getSession();
        return session != null ? session.getText() : "";
    }

    /** Document courant (null sans session) — pour les tests de bornes. */
    EditorDocument currentDocument() {
        EditorSession session = view.getSession();
        return session != null ? session.getDocument() : null;
    }
}

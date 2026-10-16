package jo.codeeditor.languages;

/**
 * Politique de la porte de contexte de complétion (ADR-002, chantier C1).
 *
 * <p>Historiquement la porte vivait dans l'UI ({@code EditorCompletionPopup})
 * et commutait sur la famille lexicale : LOG → jamais de complétion, XML →
 * porte écartée. Ces exemptions sont des <b>données par langage</b>, pas des
 * règles de code : elles vivent désormais dans le {@link LanguageProfile}
 * et un hôte peut les surcharger par langage.</p>
 *
 * <ul>
 *   <li>{@link #OFF} — aucune complétion intégrée (fichiers de log : le
 *       texte non-code n'a rien à proposer) ;</li>
 *   <li>{@link #ALWAYS} — la porte chaîne/commentaire est écartée : la
 *       complétion est offerte partout et le fournisseur tranche lui-même
 *       les zones muettes (XML : le serveur LSP sait que texte, balises
 *       fermantes, commentaires {@code <!--} et PI {@code <?} sont muets) ;</li>
 *   <li>{@link #STANDARD} — porte calibrée pour les littéraux C/Java :
 *       pas de complétion quand le caret est dans une chaîne ou un
 *       commentaire (défaut de toutes les autres familles).</li>
 * </ul>
 */
public enum CompletionGate {
    /** Aucune complétion intégrée pour ce langage. */
    OFF,
    /** Porte chaîne/commentaire écartée — complétion offerte partout. */
    ALWAYS,
    /** Porte chaîne/commentaire standard (littéraux C/Java). */
    STANDARD
}

package jo.codeeditor;

import java.util.function.BooleanSupplier;

/**
 * Attente bornée (A-06, audit 3.69.0) — remplace les scrutations à la
 * main des tests (boucle while + Thread.sleep + try/catch répétés à
 * chaque fichier) : une condition est re-testée à pas fixe jusqu'à
 * expiration d'un délai maximal, avec interruption propre.
 *
 * <p>Retourne {@code true} dès que la condition devient vraie,
 * {@code false} à expiration — l'assertion reste à la charge de
 * l'appelant (le helper ne dépend d'aucun framework de test, JUnit 4
 * comme JUnit 5 l'utilisent).</p>
 */
public final class AttenteBornee {

    private AttenteBornee() {}

    /** Scrute la condition à pas de 20 ms jusqu'au délai maximal. */
    public static boolean jusquA(long delaiMaxMs, BooleanSupplier condition) {
        return jusquA(delaiMaxMs, 20L, condition, null);
    }

    /** Scrute à pas personnalisé jusqu'au délai maximal. */
    public static boolean jusquA(long delaiMaxMs, long pasMs, BooleanSupplier condition) {
        return jusquA(delaiMaxMs, pasMs, condition, null);
    }

    /**
     * Scrute à pas personnalisé, en exécutant une action entre deux tests
     * de la condition (p. ex. avancer le looper principal de Robolectric
     * entre deux passes).
     *
     * @param entrePasses action entre passes (null = rien)
     */
    public static boolean jusquA(long delaiMaxMs, long pasMs,
            BooleanSupplier condition, Runnable entrePasses) {
        long fin = System.currentTimeMillis() + delaiMaxMs;
        while (true) {
            if (condition.getAsBoolean()) return true;
            if (System.currentTimeMillis() >= fin) return false;
            if (entrePasses != null) entrePasses.run();
            try {
                Thread.sleep(Math.max(1L, pasMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}

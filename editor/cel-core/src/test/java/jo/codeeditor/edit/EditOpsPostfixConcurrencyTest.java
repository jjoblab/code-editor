package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-04 — complétion postfix SANS état statique partagé : deux threads
 * (deux sessions éditant simultanément) interrogent la complétion
 * postfix 10 000 fois chacun — chaque thread doit TOUJOURS recevoir le
 * texte correspondant à SON propre déclencheur.
 *
 * <p>Rouge avant : {@code EditOps.lastPostfixResult} (champ STATIQUE
 * MUTABLE, seul du package edit) était écrit par
 * {@code tryPostfixCompletion} et lu par {@code getPostfixInsertText} :
 * entrelacés, le thread A lisait le résultat du thread B — deux
 * sessions concurrentes inséraient le mauvais texte de complétion.</p>
 */
class EditOpsPostfixConcurrencyTest {

    private static final int ITÉRATIONS = 10_000;

    @Test
    void deuxThreadsDixMilleItérations_résultatImmuableParAppel()
            throws Exception {
        // Deux documents distincts : l'un déclenche .if, l'autre .var.
        String texteIf = "int value.if";
        String texteVar = "int value.var";
        int pos = texteIf.length();

        AtomicInteger erreurs = new AtomicInteger();
        CountDownLatch prêt = new CountDownLatch(2);
        CountDownLatch fini = new CountDownLatch(2);

        Runnable tâcheIf = () -> {
            prêt.countDown();
            try {
                prêt.await();
                for (int i = 0; i < ITÉRATIONS; i++) {
                    EditOps.PostfixResult r =
                            EditOps.tryPostfix(texteIf, pos, "java");
                    assertNotNull(r, "le déclencheur .if doit toujours se détecter");
                    if (!r.insertText.contains("if")) {
                        erreurs.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                erreurs.incrementAndGet();
            } finally {
                fini.countDown();
            }
        };
        Runnable tâcheVar = () -> {
            prêt.countDown();
            try {
                prêt.await();
                for (int i = 0; i < ITÉRATIONS; i++) {
                    EditOps.PostfixResult r =
                            EditOps.tryPostfix(texteVar, texteVar.length(), "java");
                    assertNotNull(r, "le déclencheur .var doit toujours se détecter");
                    if (r.insertText.contains("if (")) {
                        // .var ne doit JAMAIS produire le texte de .if.
                        erreurs.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                erreurs.incrementAndGet();
            } finally {
                fini.countDown();
            }
        };

        Thread t1 = new Thread(tâcheIf, "postfix-concurrence-A");
        Thread t2 = new Thread(tâcheVar, "postfix-concurrence-B");
        t1.start();
        t2.start();
        fini.await();

        assertEquals(0, erreurs.get(),
                "chaque appel reçoit le résultat immuable de SON document "
                        + "(aucune interférence entre threads)");
    }

    @Test
    void anciensAccesseursDépréciés_inertesSansÉtatPartagé() {
        // La paire dépréciée ne transporte plus d'état : le second
        // accesseur est inerte (null) et aucune exception n'est levée.
        assertNull(EditOps.getPostfixInsertText(),
                "getPostfixInsertText est inerte depuis le retrait de l'état partagé");
        int[] pc = EditOps.tryPostfixCompletion("int value.if", 12, "java");
        assertNotNull(pc, "la variante dépréciée retourne toujours la plage");
        assertEquals(3, pc.length);
        assertNull(EditOps.getPostfixInsertText(),
                "le texte ne transite plus par un état global");
    }

    @Test
    void résultatImmuable_champsCohérents() {
        EditOps.PostfixResult r = EditOps.tryPostfix("int value.if", 12, "java");
        assertNotNull(r);
        assertTrue(r.start <= r.end, "plage bien ordonnée");
        assertTrue(r.caret >= r.start && r.caret <= r.end + r.insertText.length(),
                "caret dans une plage cohérente");
        assertNotNull(r.insertText);
        assertFalse(r.insertText.isEmpty());
    }
}

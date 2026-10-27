package jo.codeeditor.edit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 4.8.0 — le postfix est borné aux langages de code.
 *
 * <p>Le profil « text » est C_LIKE « faute de mieux » (routage du
 * tokenizer) : sans garde, {@code mot.if} déclenchait le postfix
 * sur un simple fichier texte. La porte s'appuie désormais sur le
 * drapeau {@code callSyntax} du profil — le postfix reste actif
 * pour les familles de code et toute table déclarée par un
 * langage hôte.</p>
 */
class PostfixPlainTextGateTest {

    @Test
    void pasDePostfixSurDuTexteBrut() {
        assertNull(PostfixCompletion.tryComplete("mot.if", 6, "text"),
            "le texte brut n'est pas un langage à postfix");
    }

    @Test
    void lePostfixResteActifEnJava() {
        assertNotNull(PostfixCompletion.tryComplete("list.isEmpty().if", 17, "java"),
            "java garde son postfix");
    }
}

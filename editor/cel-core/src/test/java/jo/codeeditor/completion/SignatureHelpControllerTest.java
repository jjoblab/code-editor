package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests JVM pur pour les assistants statiques de {@link SignatureHelpController}
 * (détection d'appel + comptage du paramètre actif). Ils alimentent le
 * verrou « sommes-nous dans un appel de fonction ? » du popup sans avoir
 * besoin d'un serveur de langage.
 */
class SignatureHelpControllerTest {

    @Test
    void caretInsideCall_simple() {
        assertTrue(SignatureHelpController.caretInsideCall("foo(", 4));
        assertTrue(SignatureHelpController.caretInsideCall("foo(bar", 7));
        assertTrue(SignatureHelpController.caretInsideCall("foo(a, b", 8));
    }

    @Test
    void caretInsideCall_nestedCalls() {
        // L'appel externe est encore ouvert
        assertTrue(SignatureHelpController.caretInsideCall("foo(bar(x), ", 12));
        // À l'intérieur de l'appel interne, mais l'externe est aussi ouvert
        assertTrue(SignatureHelpController.caretInsideCall("foo(bar(x", 9));
    }

    @Test
    void caretInsideCall_notInsideCall() {
        assertFalse(SignatureHelpController.caretInsideCall("foo", 3));
        assertFalse(SignatureHelpController.caretInsideCall("foo()", 5));
        assertFalse(SignatureHelpController.caretInsideCall("foo(); bar", 10));
        assertFalse(SignatureHelpController.caretInsideCall("", 0));
    }

    @Test
    void caretInsideCall_closedBySameLineStatementBoundary() {
        // Après `foo();`, le `;` interrompt le balayage — un caret placé après
        // le `;` n'est PAS dans l'appel précédent.
        assertFalse(SignatureHelpController.caretInsideCall("foo(); ", 7));
        // Mais `bar(` EST un appel ouvert — le caret en fin de ligne y est.
        assertTrue(SignatureHelpController.caretInsideCall("foo(); bar(", 11));
    }

    @Test
    void caretInsideCall_stringIgnored() {
        // L'implémentation actuelle n'ignore PAS les chaînes — c'est une limite
        // connue documentée dans FIX_NOTES. Une '(' dans une chaîne compte
        // comme ouverture d'appel. Le `;` après la chaîne interrompt le
        // balayage, donc un caret après le `;` n'est PAS dans un appel.
        assertFalse(SignatureHelpController.caretInsideCall("x = \"(\"; ", 9));
    }

    @Test
    void findCallOpen_returnsOpenParenPosition() {
        assertEquals(3, SignatureHelpController.findCallOpen("foo(", 4));
        assertEquals(3, SignatureHelpController.findCallOpen("foo(bar", 7));
    }

    @Test
    void findCallOpen_returnsNegativeOneWhenNotInside() {
        assertEquals(-1, SignatureHelpController.findCallOpen("foo", 3));
        assertEquals(-1, SignatureHelpController.findCallOpen("foo()", 5));
    }

    @Test
    void findCallOpen_skipsNestedParens() {
        // foo(bar()) — un caret après la ')' interne doit quand même trouver la '(' externe
        assertEquals(3, SignatureHelpController.findCallOpen("foo(bar())", 9));
    }

    @Test
    void activeParameterIndex_zeroArgs() {
        assertEquals(0, SignatureHelpController.activeParameterIndex("foo(", 3, 4));
    }

    @Test
    void activeParameterIndex_firstParam() {
        assertEquals(0, SignatureHelpController.activeParameterIndex("foo(", 3, 7));
        assertEquals(0, SignatureHelpController.activeParameterIndex("foo(hello", 3, 8));
    }

    @Test
    void activeParameterIndex_secondParam() {
        assertEquals(1, SignatureHelpController.activeParameterIndex("foo(a, ", 3, 7));
        assertEquals(1, SignatureHelpController.activeParameterIndex("foo(a, b", 3, 8));
    }

    @Test
    void activeParameterIndex_thirdParam() {
        assertEquals(2, SignatureHelpController.activeParameterIndex("foo(a, b, ", 3, 10));
    }

    @Test
    void activeParameterIndex_nestedCommasDontCount() {
        // foo(bar(a, b), — la virgule interne à bar() n'incrémente pas l'index de foo
        assertEquals(1, SignatureHelpController.activeParameterIndex("foo(bar(a, b), ", 3, 15));
    }

    // ── Lot 3 (audit v3.41.0) — ignore les chaînes et les commentaires ──

    /**
     * Un {@code (} dans une chaîne ne doit pas être traité comme
     * ouvrant d'appel. Avant le Lot 3, {@code findCallOpen} faisait
     * un scan naïf sans ignorer les chaînes — un {@code (} dans
     * {@code "abc(def"} était confondu avec un ouvrant d'appel,
     * déclenchant l'aide de signature dans une chaîne.
     */
    @Test
    void findCallOpen_parenInString_doesNotTrigger() {
        // "abc(def" + foo(ar|g) — le ( dans la chaîne ne compte pas.
        // Le vrai ouvrant est à foo(. Caret à l'intérieur de foo(arg).
        String text = "String s = \"abc(def\";\nfoo(arg)";
        int fooOpen = text.indexOf("foo(") + 3; // 25
        // Caret à l'intérieur de l'appel (entre 'r' et 'g' de arg).
        int caret = fooOpen + 4; // 25 + 4 = 29, dans "arg"
        int callOpen = SignatureHelpController.findCallOpen(text, caret);
        assertEquals(fooOpen, callOpen,
                "Le ( dans une chaîne ne doit pas ouvrir d'appel");
    }

    @Test
    void findCallOpen_semicolonInString_doesNotBreak() {
        // Un ; dans une chaîne ne doit pas arrêter le scan.
        String text = "String s = \"abc;def\";\nfoo(arg";
        int fooOpen = text.indexOf("foo(") + 3;
        // Caret à la fin (appel non fermé).
        int callOpen = SignatureHelpController.findCallOpen(text, text.length());
        assertEquals(fooOpen, callOpen,
                "Le ; dans une chaîne ne doit pas arrêter le scan");
    }

    @Test
    void findCallOpen_parenInLineComment_doesNotTrigger() {
        // Un ( dans un commentaire de ligne ne compte pas.
        String text = "// un commentaire (avec parenthèses)\nfoo(arg";
        int callOpen = SignatureHelpController.findCallOpen(text, text.length());
        int fooOpen = text.indexOf("foo(") + 3;
        assertEquals(fooOpen, callOpen,
                "Le ( dans un commentaire de ligne ne doit pas ouvrir d'appel");
    }

    @Test
    void findCallOpen_parenInBlockComment_doesNotTrigger() {
        // Un ( dans un commentaire de bloc ne compte pas.
        String text = "/* un (commentaire) de bloc */\nfoo(arg";
        int callOpen = SignatureHelpController.findCallOpen(text, text.length());
        int fooOpen = text.indexOf("foo(") + 3;
        assertEquals(fooOpen, callOpen,
                "Le ( dans un commentaire de bloc ne doit pas ouvrir d'appel");
    }

    @Test
    void findCallOpen_inStringItself_returnsNegativeOne() {
        // Le caret lui-même est dans une chaîne — pas d'appel.
        String text = "String s = \"hello, world";
        // Caret à la fin, dans la chaîne non fermée.
        int callOpen = SignatureHelpController.findCallOpen(text, text.length());
        assertEquals(-1, callOpen,
                "Si le caret est dans une chaîne, pas d'aide de signature");
    }

    /**
     * Une virgule dans une chaîne ne doit pas incrémenter l'index
     * du paramètre actif. Avant le Lot 3, {@code activeParameterIndex}
     * faisait un scan naïf — une virgule dans {@code "hello, world"}
     * était comptée à tort.
     */
    @Test
    void activeParameterIndex_commaInString_doesNotCount() {
        // foo("hello, world", — la virgule dans la chaîne ne compte pas.
        String text = "foo(\"hello, world\", ";
        // callOpen à foo( = position 3.
        // caret à la fin = position text.length() = 19.
        int commas = SignatureHelpController.activeParameterIndex(text, 3, text.length());
        assertEquals(1, commas,
                "La virgule dans une chaîne ne doit pas incrémenter l'index");
    }

    @Test
    void activeParameterIndex_commaInLineComment_doesNotCount() {
        // foo(a, // comment, with comma
        //     b  — caret à la fin
        // La virgule dans // ... ne doit pas compter.
        String text = "foo(a, // comment, with comma\n     b";
        int callOpen = 3;
        int caret = text.length();
        int commas = SignatureHelpController.activeParameterIndex(text, callOpen, caret);
        assertEquals(1, commas,
                "La virgule dans un commentaire de ligne ne doit pas compter");
    }

    @Test
    void activeParameterIndex_commaInBlockComment_doesNotCount() {
        // foo(a, /* comment, with comma */ b  — caret à la fin
        String text = "foo(a, /* comment, with comma */ b";
        int callOpen = 3;
        int caret = text.length();
        int commas = SignatureHelpController.activeParameterIndex(text, callOpen, caret);
        assertEquals(1, commas,
                "La virgule dans un commentaire de bloc ne doit pas compter");
    }

    // ── Comportement du résolveur / déclencheur ───────────────────────────────

    @Test
    void resolver_returnsNullWhenNotInsideCall() {
        var controller = new SignatureHelpController((offset, callOpen) -> null);
        controller.resolve("foo", 3);
        assertNull(controller.getHelp());
    }

    @Test
    void resolver_invokedWhenInsideCall() {
        var sigHelp = new SignatureHelpController.SignatureHelp(
            java.util.Collections.singletonList(
                new SignatureHelpController.Signature(
                    "foo(int x)",
                    "does foo",
                    java.util.Collections.singletonList(
                        new SignatureHelpController.Parameter("int x", "the x")),
                    0)),
            0, 0);
        var controller = new SignatureHelpController((offset, callOpen) -> sigHelp);
        controller.resolve("foo(", 4);
        assertNotNull(controller.getHelp());
        assertEquals(1, controller.getHelp().signatures.size());
        assertEquals("foo(int x)", controller.getHelp().getActiveSignature().label);
    }

    @Test
    void triggerExplicit_clearsDismissedAndResolves() {
        var controller = new SignatureHelpController((offset, callOpen) ->
            new SignatureHelpController.SignatureHelp(
                java.util.Collections.emptyList(), 0, 0));
        controller.dismiss();
        assertTrue(controller.isDismissed());
        controller.triggerExplicit("foo(", 4);
        assertFalse(controller.isDismissed());
        assertNotNull(controller.getHelp());
    }

    @Test
    void dismiss_hidesHelpUntilCaretMovesToDifferentCall() {
        boolean[] resolveCalled = {false};
        var controller = new SignatureHelpController((offset, callOpen) -> {
            resolveCalled[0] = true;
            return new SignatureHelpController.SignatureHelp(
                java.util.Collections.emptyList(), 0, 0);
        });
        // Utilise un document unique réaliste pour que callOpen diffère entre les appels.
        String text = "foo(a); bar(b, ";
        // Caret dans foo( — callOpen=3.
        controller.resolve(text, 5);
        assertTrue(resolveCalled[0]);
        resolveCalled[0] = false;
        controller.dismiss();
        // Même appel (foo), caret différent — l'état rejeté doit persister.
        controller.resolve(text, 6);
        assertFalse(resolveCalled[0]);
        // Appel différent (bar — callOpen=10) — le rejet est réinitialisé, le résolveur se déclenche.
        controller.resolve(text, 15);
        assertTrue(resolveCalled[0]);
    }

    @Test
    void reset_clearsAllState() {
        var controller = new SignatureHelpController((offset, callOpen) ->
            new SignatureHelpController.SignatureHelp(
                java.util.Collections.emptyList(), 0, 0));
        controller.resolve("foo(", 4);
        assertNotNull(controller.getHelp());
        controller.reset();
        assertNull(controller.getHelp());
        assertFalse(controller.isDismissed());
    }

    // ── Navigation clavier Haut/Bas entre surcharges ─────

    /**
     * Assistant : construit une SignatureHelp avec N signatures triviales.
     * activeSignature vaut 0 par défaut (choix du serveur).
     */
    private static SignatureHelpController.SignatureHelp buildOverloads(int n) {
        var sigs = new java.util.ArrayList<SignatureHelpController.Signature>();
        for (int i = 0; i < n; i++) {
            sigs.add(new SignatureHelpController.Signature(
                "overload" + i + "()",
                "doc" + i,
                java.util.Collections.emptyList(),
                0));
        }
        return new SignatureHelpController.SignatureHelp(sigs, 0, 0);
    }

    @Test
    void cycleActiveSignature_downAdvancesAndWraps() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        // Pas de surcharge utilisateur au départ → effectif = 0 (choix du serveur).
        assertEquals(0, controller.getEffectiveActiveSignature());
        // Bas → 1.
        assertEquals(1, controller.cycleActiveSignature(1));
        assertEquals(1, controller.getEffectiveActiveSignature());
        // Bas → 2.
        assertEquals(2, controller.cycleActiveSignature(1));
        assertEquals(2, controller.getEffectiveActiveSignature());
        // Bas en fin de liste → bouclage vers 0.
        assertEquals(0, controller.cycleActiveSignature(1));
        assertEquals(0, controller.getEffectiveActiveSignature());
    }

    @Test
    void cycleActiveSignature_upGoesBackwardsAndWraps() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        assertEquals(0, controller.getEffectiveActiveSignature());
        // Haut sur 0 → bouclage vers 2.
        assertEquals(2, controller.cycleActiveSignature(-1));
        assertEquals(2, controller.getEffectiveActiveSignature());
        // Haut → 1.
        assertEquals(1, controller.cycleActiveSignature(-1));
        assertEquals(1, controller.getEffectiveActiveSignature());
    }

    @Test
    void cycleActiveSignature_singleOverload_isNoOp() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(1));
        controller.resolve("foo(", 4);
        // Une seule signature → cycle renvoie 0 (aucun changement).
        assertEquals(0, controller.cycleActiveSignature(1));
        assertEquals(0, controller.getEffectiveActiveSignature());
        // La surcharge utilisateur n'est PAS posée dans le cas d'une signature unique
        // (cycle renvoie 0 mais n'écrit pas la surcharge — c'est un no-op).
        assertEquals(-1, controller.getUserOverrideActiveSignature());
    }

    @Test
    void cycleActiveSignature_overridePersistsAcrossResolveWithinSameCall() {
        // Le serveur renvoie 3 surcharges et dit toujours activeSignature=0
        // (sa « meilleure estimation » — mais l'utilisateur a cyclé jusqu'à 1).
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        // L'utilisateur appuie une fois sur Bas → surcharge = 1.
        controller.cycleActiveSignature(1);
        assertEquals(1, controller.getEffectiveActiveSignature());
        // L'utilisateur saisit un autre caractère → resolve se redéclenche dans
        // le même appel (callOpen reste identique).
        controller.resolve("foo(a", 5);
        // La surcharge persiste — le popup reste sur la surcharge 1.
        assertEquals(1, controller.getEffectiveActiveSignature());
        assertEquals(1, controller.getUserOverrideActiveSignature());
    }

    @Test
    void cycleActiveSignature_overrideClearsWhenMovingToDifferentCall() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        // Premier appel à callOpen=3.
        controller.resolve("foo(", 4);
        controller.cycleActiveSignature(1);
        assertEquals(1, controller.getEffectiveActiveSignature());
        // Passe à un appel différent (bar — callOpen=8).
        controller.resolve("foo(); bar(", 12);
        // La surcharge est effacée — le 0 du serveur reprend la main.
        assertEquals(-1, controller.getUserOverrideActiveSignature());
        assertEquals(0, controller.getEffectiveActiveSignature());
    }

    @Test
    void cycleActiveSignature_overrideClearsWhenLeavingCall() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        controller.cycleActiveSignature(2); // saute à la dernière surcharge
        assertEquals(2, controller.getEffectiveActiveSignature());
        // Déplace le caret hors de tout appel.
        controller.resolve("foo(); ", 7);
        // Plus d'aide → l'effectif renvoie -1, surcharge effacée.
        assertNull(controller.getHelp());
        assertEquals(-1, controller.getEffectiveActiveSignature());
        assertEquals(-1, controller.getUserOverrideActiveSignature());
    }

    @Test
    void setUserActiveSignature_clampsAndClears() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        controller.setUserActiveSignature(2);
        assertEquals(2, controller.getEffectiveActiveSignature());
        // -1 efface la surcharge.
        controller.setUserActiveSignature(-1);
        assertEquals(0, controller.getEffectiveActiveSignature());
        // Les valeurs négatives sont ramenées à -1.
        controller.setUserActiveSignature(-5);
        assertEquals(-1, controller.getUserOverrideActiveSignature());
    }
}

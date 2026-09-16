package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-JVM tests for {@link SignatureHelpController}'s static helpers
 * (call detection + active-parameter counting). These power the popup's
 * "are we inside a function call?" gate without needing a language server.
 *
 * <p>v1.0.7 — Gap 2.
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
        // Outer call still open
        assertTrue(SignatureHelpController.caretInsideCall("foo(bar(x), ", 12));
        // Inside inner call but outer is also open
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
        // After `foo();`, the `;` breaks the scan — caret after the `;` is
        // NOT inside the previous call.
        assertFalse(SignatureHelpController.caretInsideCall("foo(); ", 7));
        // But `bar(` IS an open call — the caret at the end is inside it.
        assertTrue(SignatureHelpController.caretInsideCall("foo(); bar(", 11));
    }

    @Test
    void caretInsideCall_stringIgnored() {
        // The current implementation does NOT skip strings — that's a known
        // limitation documented in FIX_NOTES. A '(' inside a string DOES
        // count as a call open. The `;` after the string breaks the scan,
        // so caret after the `;` is NOT inside a call.
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
        // foo(bar()) — caret after the inner ')' should still find the outer '('
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
        // foo(bar(a, b), — the comma inside bar() doesn't increment foo's index
        assertEquals(1, SignatureHelpController.activeParameterIndex("foo(bar(a, b), ", 3, 15));
    }

    // ── Resolver / trigger behavior ───────────────────────────────

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
        // Use a realistic single document so callOpen differs between calls.
        String text = "foo(a); bar(b, ";
        // Caret inside foo( — callOpen=3.
        controller.resolve(text, 5);
        assertTrue(resolveCalled[0]);
        resolveCalled[0] = false;
        controller.dismiss();
        // Same call (foo), different caret — dismissed should stick.
        controller.resolve(text, 6);
        assertFalse(resolveCalled[0]);
        // Different call (bar — callOpen=10) — dismissed resets, resolver fires.
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

    // ── v2.39: Up/Down keyboard navigation between overloads ─────

    /**
     * Helper: builds a SignatureHelp with N trivial signatures.
     * activeSignature defaults to 0 (server's choice).
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
        // No override initially → effective = server's 0.
        assertEquals(0, controller.getEffectiveActiveSignature());
        // Down → 1.
        assertEquals(1, controller.cycleActiveSignature(1));
        assertEquals(1, controller.getEffectiveActiveSignature());
        // Down → 2.
        assertEquals(2, controller.cycleActiveSignature(1));
        assertEquals(2, controller.getEffectiveActiveSignature());
        // Down at the end → wrap to 0.
        assertEquals(0, controller.cycleActiveSignature(1));
        assertEquals(0, controller.getEffectiveActiveSignature());
    }

    @Test
    void cycleActiveSignature_upGoesBackwardsAndWraps() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        assertEquals(0, controller.getEffectiveActiveSignature());
        // Up at 0 → wrap to 2.
        assertEquals(2, controller.cycleActiveSignature(-1));
        assertEquals(2, controller.getEffectiveActiveSignature());
        // Up → 1.
        assertEquals(1, controller.cycleActiveSignature(-1));
        assertEquals(1, controller.getEffectiveActiveSignature());
    }

    @Test
    void cycleActiveSignature_singleOverload_isNoOp() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(1));
        controller.resolve("foo(", 4);
        // Only one signature → cycle returns 0 (no change).
        assertEquals(0, controller.cycleActiveSignature(1));
        assertEquals(0, controller.getEffectiveActiveSignature());
        // Override is NOT set for single-sig case (cycle returns 0 but
        // doesn't bother writing the override — it's a no-op).
        assertEquals(-1, controller.getUserOverrideActiveSignature());
    }

    @Test
    void cycleActiveSignature_overridePersistsAcrossResolveWithinSameCall() {
        // Server returns 3 overloads and always says activeSignature=0
        // (its own "best guess" — but the user has cycled to 1).
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        // User presses Down once → override = 1.
        controller.cycleActiveSignature(1);
        assertEquals(1, controller.getEffectiveActiveSignature());
        // User types another character → resolve fires again within
        // the same call (callOpen stays the same).
        controller.resolve("foo(a", 5);
        // Override persists — the popup stays on overload 1.
        assertEquals(1, controller.getEffectiveActiveSignature());
        assertEquals(1, controller.getUserOverrideActiveSignature());
    }

    @Test
    void cycleActiveSignature_overrideClearsWhenMovingToDifferentCall() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        // First call at callOpen=3.
        controller.resolve("foo(", 4);
        controller.cycleActiveSignature(1);
        assertEquals(1, controller.getEffectiveActiveSignature());
        // Move to a different call (bar — callOpen=8).
        controller.resolve("foo(); bar(", 12);
        // Override is cleared — server's 0 takes over again.
        assertEquals(-1, controller.getUserOverrideActiveSignature());
        assertEquals(0, controller.getEffectiveActiveSignature());
    }

    @Test
    void cycleActiveSignature_overrideClearsWhenLeavingCall() {
        var controller = new SignatureHelpController((o, c) -> buildOverloads(3));
        controller.resolve("foo(", 4);
        controller.cycleActiveSignature(2); // jump to last overload
        assertEquals(2, controller.getEffectiveActiveSignature());
        // Move caret out of any call.
        controller.resolve("foo(); ", 7);
        // No help → effective returns -1, override cleared.
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
        // -1 clears the override.
        controller.setUserActiveSignature(-1);
        assertEquals(0, controller.getEffectiveActiveSignature());
        // Negative values clamp to -1.
        controller.setUserActiveSignature(-5);
        assertEquals(-1, controller.getUserOverrideActiveSignature());
    }
}

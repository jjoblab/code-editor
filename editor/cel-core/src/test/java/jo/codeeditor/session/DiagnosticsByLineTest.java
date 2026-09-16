package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3.36.0 — Tests des diagnostics groupés par ligne de début (roadmap
 * item 5, portage {@code diagnosticsByStartLine()} de CodeAssist v3.20).
 *
 * <p>Vérifie : le groupement par ligne de début (offset → ligne), le tri
 * sévérité-décroissante puis offset-croissant (le premier élément est la
 * diagnostique « primaire » affichée par la chip), la mémoïsation par
 * référence de liste (même instance tant que la liste source ne change
 * pas, rebuild après setDiagnostics — même pattern que les buckets
 * inlays/sem v3.34.0), et le comportement sur lignes sans diagnostics.</p>
 *
 * @since v3.36.0
 */
public class DiagnosticsByLineTest {

    private static final String DOC =
        "package demo;\n"          // 0
        + "class Main {\n"          // 1
        + "    void run() {\n"      // 2
        + "        greet(name);\n" // 3
        + "    }\n"                 // 4
        + "}\n";                    // 5

    private static int off(String needle) {
        return DOC.indexOf(needle);
    }

    private static List<DiagnosticShift.Diagnostic> diags(DiagnosticShift.Diagnostic... ds) {
        List<DiagnosticShift.Diagnostic> out = new ArrayList<>();
        for (DiagnosticShift.Diagnostic d : ds) out.add(d);
        return out;
    }

    @Test
    public void groupByStartLine_bucketsPerLine() {
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        DiagnosticShift.Diagnostic e1 = new DiagnosticShift.Diagnostic(
                off("greet"), off("greet") + 5, 3, "cannot resolve greet");
        DiagnosticShift.Diagnostic w1 = new DiagnosticShift.Diagnostic(
                off("name"), off("name") + 4, 2, "unused argument warning");
        DiagnosticShift.Diagnostic info0 = new DiagnosticShift.Diagnostic(
                off("package"), off("package") + 7, 1, "info-level note");
        session.setDiagnostics(diags(e1, w1, info0));

        EditorDocument doc = session.getDocument();
        assertEquals(0, session.getDiagnosticsForLine(1).size()); // untouched line
        // line 3 carries both the error and the warning
        List<DiagnosticShift.Diagnostic> line3 = session.getDiagnosticsForLine(3);
        assertEquals(2, line3.size());
        assertSame(e1, line3.get(0), "most severe first (error before warning)");
        assertSame(w1, line3.get(1));
        // info lives on line 0 alone
        List<DiagnosticShift.Diagnostic> line0 = session.getDiagnosticsForLine(0);
        assertEquals(1, line0.size());
        assertSame(info0, line0.get(0));
        // untouched lines → empty (never null)
        assertNotNull(session.getDiagnosticsForLine(4));
        assertTrue(session.getDiagnosticsForLine(4).isEmpty());
        assertTrue(session.getDiagnosticsForLine(99).isEmpty());
        assertTrue(session.getDiagnosticsForLine(-3).isEmpty());
    }

    @Test
    public void bucket_sortedSeverityDescThenOffsetAsc() {
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        int g = off("greet"), n = off("name"), run = off("run");
        DiagnosticShift.Diagnostic warnEarly = new DiagnosticShift.Diagnostic(
                g, g + 5, 2, "w-early");
        DiagnosticShift.Diagnostic errLate = new DiagnosticShift.Diagnostic(
                n, n + 4, 3, "e-late");
        DiagnosticShift.Diagnostic warnOnLine2 = new DiagnosticShift.Diagnostic(
                run, run + 3, 2, "w-line2");
        DiagnosticShift.Diagnostic info = new DiagnosticShift.Diagnostic(
                g + 1, g + 2, 1, "info");
        // Register in a scrambled order — the bucket must re-sort.
        session.setDiagnostics(diags(info, warnOnLine2, errLate, warnEarly));

        // Line 3 carries greet (error@name is the most severe but ALSO on 3):
        // [errLate(3), warnEarly(2, offset g), info(1)]
        List<DiagnosticShift.Diagnostic> line3 = session.getDiagnosticsForLine(3);
        assertEquals(3, line3.size());
        assertSame(errLate, line3.get(0), "error first");
        assertSame(warnEarly, line3.get(1), "warning after error");
        assertSame(info, line3.get(2), "info last");
        // "run" sits on line 2 — its warning buckets there.
        List<DiagnosticShift.Diagnostic> line2 = session.getDiagnosticsForLine(2);
        assertEquals(1, line2.size());
        assertSame(warnOnLine2, line2.get(0));
    }

    @Test
    public void memoization_sameListRefIsFree_rebuildOnSet() {
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        DiagnosticShift.Diagnostic e = new DiagnosticShift.Diagnostic(
                off("greet"), off("greet") + 5, 3, "err");
        List<DiagnosticShift.Diagnostic> first = diags(e);
        session.setDiagnostics(first);

        List<DiagnosticShift.Diagnostic> b1 = session.getDiagnosticsForLine(3);
        List<DiagnosticShift.Diagnostic> b2 = session.getDiagnosticsForLine(3);
        assertSame(b1, b2, "same source list → memoized bucket instance");

        // Pushing a NEW list (what setDiagnostics/edits do) rebuilds.
        DiagnosticShift.Diagnostic w = new DiagnosticShift.Diagnostic(
                off("name"), off("name") + 4, 2, "warn");
        session.setDiagnostics(diags(e, w));
        List<DiagnosticShift.Diagnostic> b3 = session.getDiagnosticsForLine(3);
        assertNotSame(b1, b3, "new source list → rebuilt bucket");
        assertEquals(2, b3.size());

        // Empty diagnostics → empty buckets, no crash.
        session.setDiagnostics(new ArrayList<>());
        assertTrue(session.getDiagnosticsForLine(3).isEmpty());
        List<DiagnosticShift.Diagnostic> b4 = session.getDiagnosticsForLine(3);
        List<DiagnosticShift.Diagnostic> b5 = session.getDiagnosticsForLine(3);
        assertSame(b4, b5, "empty-map memoization also stable");
    }

    @Test
    public void clampedOffsets_pathologicalDiagnosticsDoNotThrow() {
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        session.setDiagnostics(diags(
                new DiagnosticShift.Diagnostic(-50, -10, 3, "negative"),
                new DiagnosticShift.Diagnostic(99999, 100000, 2, "beyond eof")));
        EditorDocument doc = session.getDocument();
        // Clamped to doc bounds — negative maps to line 0, beyond-eof to the
        // LAST line (offset == length resolves to the final line's start);
        // never throws.
        assertEquals(1, session.getDiagnosticsForLine(0).size());
        assertEquals(1, session.getDiagnosticsForLine(doc.lineCount() - 1).size());
        // No line in between got anything.
        for (int line = 1; line < doc.lineCount() - 1; line++) {
            assertEquals(0, session.getDiagnosticsForLine(line).size());
        }
    }
}

package jo.codeeditor.session;

import org.junit.jupiter.api.Test;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.EditSpan;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B21f — les mutateurs directs de sélection (selectAll, flèches,
 * début/fin de ligne, saut de diagnostic…) NOTIFIENT l'IME. Historiquement
 * ils écrivaient le champ sans passer par setSelection : updateSelection
 * n'était jamais poussé et le miroir de l'IME (contexte
 * d'autocorrection, sélection extraite) désynchronisait.
 */
public class SelectionMutatorsNotifyImeTest {

    /** Session avec un écouteur IME comptant les notifications de sélection. */
    private EditorSession newSessionWithListener(AtomicInteger selCount) {
        EditorSession s = new EditorSession(EditorDocument.of(
                "ligne un\nligne deux\nligne trois\n"));
        s.setImeListener(new EditorSession.ImeListener() {
            @Override public void onTextChanged(EditSpan span) { }
            @Override public void onSelectionChanged(int selStart, int selEnd,
                                                     int composingStart, int composingEnd) {
                selCount.incrementAndGet();
            }
            @Override public void onRestartInput() { }
            @Override public boolean isSyncingExtractedText() { return false; }
        });
        return s;
    }

    @Test
    public void selectAll_notifiesIme() {
        AtomicInteger n = new AtomicInteger();
        EditorSession s = newSessionWithListener(n);
        s.selectAll();
        assertTrue(n.get() > 0, "selectAll doit notifier l'IME");
        assertEquals(s.getDocument().length(), s.getSelection().end);
    }

    @Test
    public void cursorMoves_notifyIme() {
        AtomicInteger n = new AtomicInteger();
        EditorSession s = newSessionWithListener(n);
        s.moveHorizontal(3, false);
        s.moveVertical(1, false);
        s.moveLineEnd(false);
        s.moveLineStart(false);
        s.moveDocBoundary(true, false);
        assertTrue(n.get() >= 5, "les déplacements du curseur doivent notifier l'IME"
                + " (notifications=" + n.get() + ")");
    }

    @Test
    public void shiftMoves_notifyIme() {
        AtomicInteger n = new AtomicInteger();
        EditorSession s = newSessionWithListener(n);
        s.moveHorizontal(2, true);
        assertTrue(n.get() > 0, "le déplacement étendant la sélection doit notifier l'IME");
        assertTrue(s.getSelection().end > s.getSelection().start,
                "la sélection doit s'être étendue");
    }

    @Test
    public void selectWordAndLine_notifyIme() {
        AtomicInteger n = new AtomicInteger();
        EditorSession s = newSessionWithListener(n);
        s.selectWordAt(2);
        s.selectLineAt(12);
        assertTrue(n.get() >= 2, "selectWordAt/selectLineAt doivent notifier l'IME"
                + " (notifications=" + n.get() + ")");
    }
}

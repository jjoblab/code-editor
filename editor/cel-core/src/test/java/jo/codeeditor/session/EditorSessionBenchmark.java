package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmarks de performance pour {@link EditorSession} — mesure le coût des
 * hot paths : commitText, undo/redo, splice de styles, lookups de lignes.
 *
 * <p>Pas une suite JMH complète — des assertions de timing légères qui
 * affichent sur stdout et vérifient que le moteur reste réactif sur des
 * tailles de documents typiques.
 */
class EditorSessionBenchmark {

    private static final int SMALL_DOC_LINES = 100;
    private static final int MEDIUM_DOC_LINES = 1000;
    private static final int LARGE_DOC_LINES = 5000;

    @Test
    void benchmark_commitText_singleChar_mediumDoc() {
        EditorSession s = buildSession(MEDIUM_DOC_LINES);
        // Placer le caret au milieu.
        int midOffset = s.getDocument().length() / 2;
        s.setSelection(midOffset);
        // Échauffement.
        for (int i = 0; i < 100; i++) {
            s.typeChar('x');
            s.backspace();
        }
        // Benchmark : 1000 insertions d'un caractère.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            s.typeChar('x');
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 1000.0;
        System.out.printf("[benchmark] typeChar (1000-line doc): %.1f ns/op (%.2f ms total)%n",
            avgNs, elapsedNs / 1e6);
        // Une insertion d'un caractère sur un doc de 1000 lignes doit
        // rester sous 1 ms.
        assertTrue(avgNs < 1_000_000,
            "typeChar too slow: " + avgNs + " ns/op (expected < 1000000 ns/op)");
    }

    @Test
    void benchmark_commitText_multiChar_hugeDoc_prefixe() {
        // P-01 (audit 3.69.0) : protocole du lot L0 — document ~1 M de
        // caractères (25 000 lignes), caret en FIN, commitText de
        // 2 caractères × 200. Mesure de base consignée en L0 :
        // 6,38 ms/op — la simulation postfix matérialise le PRÉFIXE
        // ENTIER du document à chaque frappe multi-caractères
        // (subSequence(0, pos).toString() + texte). La complétion
        // postfix ne lit que l'arrière-garde (postfix ≤ 8 caractères,
        // expression courante, retrait de ligne) : après correctif,
        // la vue paresseuse ne copie plus rien et l'op tombe au coût
        // du splice seul.
        final int LINES = 25_000;
        EditorSession s = buildSession(LINES);
        int end = s.getDocument().length();
        s.setSelection(end);
        // Échauffement (cache JIT + memoïsation getText).
        for (int i = 0; i < 20; i++) {
            s.commitText("ab");
        }
        end = s.getDocument().length();
        s.setSelection(end);
        // Benchmark : 200 insertions de 2 caractères en fin de document.
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            s.commitText("xy");
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 200.0;
        System.out.printf("[benchmark] commitText 2 caractères (25k lignes, caret en fin): "
                + "%.1f ns/op (%.2f ms/op)%n", avgNs, avgNs / 1e6);
        // Budget de frappe perceptible : une frappe multi-caractères sur
        // un document d'un million de caractères doit rester sous 2,5 ms
        // (la base L0 mesurait 6,38 ms/op — le test est ROUGE avant le
        // correctif P-01, VERT après).
        assertTrue(avgNs < 2_500_000,
            "commitText multi-caractères trop lent: " + (avgNs / 1e6)
                + " ms/op (attendu < 2.5 ms/op — P-01)");
    }

    @Test
    void benchmark_p02_cheminÉcouteParFrappe_25kLignes() {
        // P-02 (audit 3.69.0) : coût du PONT D'ÉDITION par frappe sur un
        // document ~1 M caractères (25 000 lignes), caret vers la fin.
        // ANCIEN chemin (avant correctif) : le pont LSP matérialisait le
        // document ENTIER à chaque frappe — listener → getText() (le cache
        // est invalidé par chaque édition) ; et au moment de l'ENVOI :
        // diff préfixe/suffixe sur les textes entiers (2 passes O(n)) +
        // reconstruction de vérification (3 copies O(n) + comparaison) +
        // 2 conversions de position (2 passes O(n)) + toString.
        // NOUVEAU chemin : 2 recherches binaires (positions LSP) + capture
        // O(1) de la rope par frappe ; à l'envoi, les événements enregistrés
        // partent tels quels (O(événements)).
        final int LINES = 25_000;
        EditorDocument doc = buildDocument(LINES);
        int pos = doc.length() - 10;
        // Échauffement des deux chemins.
        for (int i = 0; i < 50; i++) {
            doc = doc.replace(pos, pos, "x");
            doc.getText();
            doc.lineForOffset(pos);
            doc.lineStart(doc.lineForOffset(pos));
            doc.charSequence();
        }
        // -- Mesure en 3 tours, MINIMUM par chemin : sur un runner CI
        // partagé (JIT, GC, contention CPU) le bruit ne fait qu'AJOUTER
        // du temps — le minimum est la statistique robuste pour comparer
        // deux hot paths. Un passage unique a déjà inversé l'ordre sur un
        // commit sans aucun changement de code (CI 05620a6).
        final int TOURS = 3;
        long ancienMinNs = Long.MAX_VALUE;
        long nouveauMinNs = Long.MAX_VALUE;
        for (int tour = 0; tour < TOURS; tour++) {
            // -- ANCIEN chemin par frappe : édition + getText() (cache invalidé).
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                doc = doc.replace(pos, pos, "x");
                String ignored = doc.getText();
                if (ignored.isEmpty()) fail();
            }
            ancienMinNs = Math.min(ancienMinNs, System.nanoTime() - t0);
            // -- NOUVEAU chemin par frappe : édition + positions + capture rope.
            long t1 = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                doc = doc.replace(pos, pos, "x");
                int line = doc.lineForOffset(pos);
                int col = pos - doc.lineStart(line);
                CharSequence vue = doc.charSequence();
                if (col < 0 || vue.length() < 0) fail();
            }
            nouveauMinNs = Math.min(nouveauMinNs, System.nanoTime() - t1);
        }
        // -- ANCIEN envoi (une salve de 200 événements sur 1 M) : diff + VÉRIFICATION.
        String base = doc.getText();
        String texte = base.substring(0, pos) + "xyz" + base.substring(pos);
        long t2 = System.nanoTime();
        jo.codeeditor.shift.EditSpan edit =
                jo.codeeditor.shift.DiagnosticShift.diffEdit(base, texte);
        String insertion = texte.substring(edit.start,
                Math.min(edit.start + edit.added, texte.length()));
        String reconstruit = base.substring(0, edit.start) + insertion
                + base.substring(edit.end());
        if (!reconstruit.equals(texte)) fail();
        long ancienEnvoiNs = System.nanoTime() - t2;
        System.out.printf("[benchmark] P-02 pont d'édition (25k lignes, 200 frappes, min de 3 tours) : "
                + "ancien (getText par frappe) %d ns/op, nouveau (positions+rope) %d ns/op, "
                + "gain x%.1f ; ancien envoi (diff+vérif, 1 M ch) %d ns (%.2f ms)%n",
            ancienMinNs / 200, nouveauMinNs / 200, (double) ancienMinNs / Math.max(1, nouveauMinNs),
            ancienEnvoiNs, ancienEnvoiNs / 1e6);
        // Le nouveau chemin par frappe doit rester NETTEMENT plus rapide
        // que l'ancien (au moins 2×) : l'ancien matérialise tout le
        // document à chaque frappe — un écart structurel, pas un à-coup
        // de bruit ; le minimum de 3 tours écarte déjà le bruit.
        assertTrue(nouveauMinNs * 2 <= ancienMinNs,
            "le chemin d'écoute P-02 doit rester nettement plus rapide que getText() par frappe : "
                + nouveauMinNs + " vs " + ancienMinNs + " ns (min de 3 tours)");
    }

    @Test
    void benchmark_undoRedo_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Appliquer 100 éditions à des positions différentes.
        for (int i = 0; i < 100; i++) {
            int offset = (i * 100) % s.getDocument().length();
            s.setSelection(offset);
            s.typeChar('y');
        }
        // -- Mesure en 3 tours, MINIMUM par phase : sur un runner CI
        // partagé (JIT, GC, vol de CPU) le bruit ne fait qu'AJOUTER du
        // temps — le minimum est la statistique robuste. Un passage
        // unique a déjà fait tomber le CI SANS aucun changement de code
        // (run 701726e : undo 2184 ms contre un budget de 2000 ms, redo
        // 949 ms dans la MÊME exécution). Chaque tour repart du même
        // état (100 éditions appliquées) : undo × 100 puis redo × 100
        // restitue exactement cet état.
        final int TOURS = 3;
        long undoMinNs = Long.MAX_VALUE;
        long redoMinNs = Long.MAX_VALUE;
        for (int tour = 0; tour < TOURS; tour++) {
            // -- Benchmark undo.
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                s.undo();
            }
            undoMinNs = Math.min(undoMinNs, System.nanoTime() - start);
            // -- Benchmark redo.
            start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                s.redo();
            }
            redoMinNs = Math.min(redoMinNs, System.nanoTime() - start);
        }
        System.out.printf("[benchmark] undo (5000-line doc, min de 3 tours): %.1f ns/op, redo: %.1f ns/op%n",
            undoMinNs / 100.0, redoMinNs / 100.0);
        // Undo/redo sur un doc de 5000 lignes fait un restyleAll complet —
        // doit rester sous 2 s chacun (marge CI).
        assertTrue(undoMinNs < 2_000_000_000L,
            "undo too slow: " + (undoMinNs / 1e6) + " ms (min de 3 tours, expected < 2000 ms)");
        assertTrue(redoMinNs < 2_000_000_000L,
            "redo too slow: " + (redoMinNs / 1e6) + " ms (min de 3 tours, expected < 2000 ms)");
    }

    @Test
    void benchmark_lineForOffset_largeDoc() {
        EditorDocument doc = buildDocument(LARGE_DOC_LINES);
        // Échauffement.
        for (int i = 0; i < 1000; i++) {
            doc.lineForOffset(i * 10);
        }
        // Benchmark.
        long start = System.nanoTime();
        int dummy = 0;
        for (int i = 0; i < 10_000; i++) {
            dummy += doc.lineForOffset(i % (doc.length() + 1));
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 10_000.0;
        System.out.printf("[benchmark] lineForOffset (5000-line doc): %.1f ns/op (dummy=%d)%n",
            avgNs, dummy);
        // Recherche binaire sur lineStarts — doit être O(log L), sous 100 µs.
        assertTrue(avgNs < 100_000,
            "lineForOffset too slow: " + avgNs + " ns/op (expected < 100000 ns/op)");
    }

    @Test
    void benchmark_lineStart_lineEnd_largeDoc() {
        EditorDocument doc = buildDocument(LARGE_DOC_LINES);
        // Échauffement.
        for (int i = 0; i < 1000; i++) {
            doc.lineStart(i % doc.lineCount());
            doc.lineEnd(i % doc.lineCount());
        }
        // Benchmark.
        long start = System.nanoTime();
        int dummy = 0;
        for (int i = 0; i < 10_000; i++) {
            int line = i % doc.lineCount();
            dummy += doc.lineStart(line) + doc.lineEnd(line);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 10_000.0;
        System.out.printf("[benchmark] lineStart+lineEnd (5000-line doc): %.1f ns/op (dummy=%d)%n",
            avgNs, dummy);
        // Accès par index de tableau — doit rester sous 10 µs.
        assertTrue(avgNs < 10_000,
            "lineStart+lineEnd too slow: " + avgNs + " ns/op (expected < 10000 ns/op)");
    }

    @Test
    void benchmark_restyleAll_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Échauffement.
        s.setLanguage("java");
        // Benchmark.
        long start = System.nanoTime();
        s.setLanguage("kotlin");
        s.setLanguage("java");
        long elapsedNs = System.nanoTime() - start;
        System.out.printf("[benchmark] restyleAll (5000-line doc, 2 calls): %.2f ms%n",
            elapsedNs / 1e6);
        // restyleAll est O(lignes) — 5000 lignes doivent se faire en
        // moins de 2 s (marge CI).
        assertTrue(elapsedNs < 2_000_000_000L,
            "restyleAll too slow: " + (elapsedNs / 1e6) + " ms (expected < 2000 ms)");
    }

    @Test
    void benchmark_styledLinesAccess_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Échauffement.
        for (int i = 0; i < 100; i++) {
            s.getStyledLines().size();
        }
        // Benchmark.
        long start = System.nanoTime();
        int dummy = 0;
        for (int i = 0; i < 10_000; i++) {
            dummy += s.getStyledLines().size();
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 10_000.0;
        System.out.printf("[benchmark] getStyledLines (5000-line doc): %.1f ns/op (dummy=%d)%n",
            avgNs, dummy);
        // getStyledLines renvoie un wrapper unmodifiableList — doit rester
        // sous 10 µs.
        assertTrue(avgNs < 10_000,
            "getStyledLines too slow: " + avgNs + " ns/op (expected < 10000 ns/op)");
    }

    @Test
    void benchmark_insertNewline_splitsStylesCorrectly() {
        // Insérer 100 sauts de ligne au milieu d'un doc de 1000 lignes et
        // vérifier que le nombre de styledLines reste cohérent avec
        // lineCount.
        EditorSession s = buildSession(MEDIUM_DOC_LINES);
        int initialLines = s.getDocument().lineCount();
        for (int i = 0; i < 100; i++) {
            int offset = s.getDocument().length() / 2;
            s.setSelection(offset);
            s.commitText("\n");
            assertEquals(initialLines + i + 1, s.getDocument().lineCount(),
                "line count must match after newline insert " + i);
            assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
                "styledLines must match lineCount after newline insert " + i);
        }
    }

    @Test
    void benchmark_lineText_cheminDeFrappe_50kLignes() {
        // ★ B19/Lot 4 : mesure l'ancien chemin (getText().substring —
        // matérialise TOUT le document à chaque appel après édition)
        // contre le nouveau (lineText → subText, O(log n + tranche)).
        // Chiffres consignés dans docs/RAPPORT_VERIFICATION.md.
        final int LINES = 50_000;
        EditorDocument doc = buildDocument(LINES);
        // Échauffement des deux chemins.
        for (int i = 0; i < 200; i++) {
            doc.lineText(i % LINES);
            int st = doc.lineStart(i % LINES);
            doc.getText().substring(st, Math.min(st + 30, doc.length()));
        }
        // -- Mesure en 3 tours, MINIMUM par chemin (même raison que P-02 :
        // le bruit d'un runner partagé ne fait qu'ajouter du temps, le
        // minimum l'écarte — ce test a échoué sur CI sans changement de
        // code, commit 05620a6, tandis que le workflow de publication
        // passait les mêmes tests sur le même commit).
        final int TOURS = 3;
        long ancienMinNs = Long.MAX_VALUE;
        long nouveauMinNs = Long.MAX_VALUE;
        for (int tour = 0; tour < TOURS; tour++) {
            // -- ANCIEN chemin : getText() (cache invalidé par l'édition) +
            // substring.
            doc = doc.replace(100, 100, "x"); // une frappe → cache invalidé
            long t0 = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                int st = doc.lineStart((i * 37) % LINES);
                String l = doc.getText().substring(st, Math.min(st + 30, doc.length()));
                if (l.isEmpty()) fail();
            }
            ancienMinNs = Math.min(ancienMinNs, System.nanoTime() - t0);
            // -- NOUVEAU chemin : lineText (subText, pas de matérialisation).
            doc = doc.replace(100, 100, "y"); // cache invalidé à nouveau
            long t1 = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                String l = doc.lineText((i * 37) % LINES);
                if (l.isEmpty()) fail();
            }
            nouveauMinNs = Math.min(nouveauMinNs, System.nanoTime() - t1);
        }
        System.out.printf("[benchmark] lineText 50k lignes (1000 lectures après édition, min de 3 tours) : "
                + "ancien (getText+substring) %d ns/op, nouveau (subText) %d ns/op, "
                + "gain x%.1f%n",
            ancienMinNs / 1000, nouveauMinNs / 1000,
            (double) ancienMinNs / Math.max(1, nouveauMinNs));
        // Le nouveau chemin ne doit pas être significativement plus lent
        // que l'ancien (marge de 25 % : les deux chemins sont ici
        // structurellement PROCHES — 1 matérialisation + 999 lectures
        // cachées contre 1000 subText — l'assertion protège contre une
        // régression STRUCTURALE de lineText, pas contre quelques
        // pourcents de bruit résiduel).
        assertTrue(nouveauMinNs * 4 <= ancienMinNs * 5,
            "lineText (subText) ne doit pas être nettement plus lent que getText().substring : "
                + nouveauMinNs + " vs " + ancienMinNs + " ns (min de 3 tours)");
    }

    @Test
    void benchmark_replace_spliceIndexLignes_50kLignes() {
        // Mesure pour l'étape 2 (index de lignes sous-linéaire) : le
        // splice est O(lignes) par édition. Chiffres consignés dans le
        // compte rendu — la décision d'implémenter une structure
        // sous-linéaire (comptage par nœud / Fenwick) se prend sur ces
        // nombres.
        final int LINES = 50_000;
        EditorDocument doc = buildDocument(LINES);
        // Échauffement.
        for (int i = 0; i < 50; i++) {
            doc = doc.replace(100, 100, "x");
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            doc = doc.replace(100, 100, "x");
        }
        long parEdition = (System.nanoTime() - t0) / 200;
        System.out.printf("[benchmark] replace + splice index (50k lignes) : "
                + "%d ns/édition (%.2f ms)%n", parEdition, parEdition / 1e6);
        // Budget de frappe : une édition sur 50k lignes doit rester sous
        // le seuil perceptible (~2 ms).
        assertTrue(parEdition < 2_000_000,
            "replace trop lent sur 50k lignes : " + parEdition + " ns");
    }

    /** Construit une session avec le nombre de lignes donné, chacune ~40 caractères. */
    private static EditorSession buildSession(int lineCount) {
        StringBuilder sb = new StringBuilder(lineCount * 50);
        for (int i = 0; i < lineCount; i++) {
            sb.append("int var").append(i).append(" = ").append(i * 2)
              .append("; // comment line ").append(i).append('\n');
        }
        return new EditorSession(EditorDocument.of(sb.toString()));
    }

    /** Construit un document avec le nombre de lignes donné. */
    private static EditorDocument buildDocument(int lineCount) {
        return buildSession(lineCount).getDocument();
    }
}

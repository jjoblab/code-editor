package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Les INDICES LAMBDA du mini Java (4.13.0) — le type fantôme des
 * paramètres de lambda, inféré de l'interface fonctionnelle attendue
 * par l'appel : câblage des fonctionnelles connues, substitution des
 * génériques par la DÉCLARATION du récepteur, et le contrat ZÉRO
 * DEVINETTE — un type inconnu ne sert AUCUN indice.
 */
public class MiniJavaLambdasTest {

    /** Les indices d'un texte entier (la plage couvre tout). */
    private static List<MiniJavaLambdas.Indice> indices(String texte) {
        return MiniJavaLambdas.indices(texte, 0, texte.length());
    }

    /** L'indice à la position i, ou fail. */
    private static MiniJavaLambdas.Indice un(List<MiniJavaLambdas.Indice> l, int i) {
        assertTrue("indice " + i + " absent (" + l.size() + " servis)",
                i < l.size());
        return l.get(i);
    }

    // ── Consumer : un paramètre ────────────────────────────────

    @Test
    public void forEach_surListeTypée_sertLElément() {
        String code = "class A { void t() {"
                + " List<String> noms = null;"
                + " noms.forEach(n -> System.out.println(n)); } }";
        List<MiniJavaLambdas.Indice> l = indices(code);
        assertEquals(1, l.size());
        assertEquals("String", un(l, 0).type);
        assertTrue(un(l, 0).info.contains("n : String"));
        // L'offset pointe le NOM du paramètre, pas la flèche.
        assertEquals("n", code.substring(un(l, 0).offset,
                un(l, 0).offset + 1));
    }

    @Test
    public void removeIf_sertLElément() {
        String code = "class A { void t() {"
                + " List<String> noms = null;"
                + " noms.removeIf(n -> n.isEmpty()); } }";
        List<MiniJavaLambdas.Indice> l = indices(code);
        assertEquals(1, l.size());
        assertEquals("String", un(l, 0).type);
    }

    @Test
    public void classpath_setTypé_sertLElémentParRéflexion() {
        // Set n'est pas dans la table embarquée : la signature vient
        // du CLASSPATH (Consumer<? super E>) — et la substitution
        // de E reste celle de la déclaration.
        String code = "class A { void t() {"
                + " Set<String> noms = null;"
                + " noms.forEach(n -> System.out.println(n)); } }";
        List<MiniJavaLambdas.Indice> l = indices(code);
        assertEquals(1, l.size());
        assertEquals("String", un(l, 0).type);
    }

    // ── Deux paramètres : BiConsumer et Comparator ─────────────

    @Test
    public void mapForEach_sertCléEtValeur() {
        String code = "class A { void t() {"
                + " Map<String, Integer> stocks = null;"
                + " stocks.forEach((k, v) -> System.out.println(k)); } }";
        List<MiniJavaLambdas.Indice> l = indices(code);
        assertEquals(2, l.size());
        assertEquals("String", un(l, 0).type);
        assertEquals("Integer", un(l, 1).type);
        assertEquals("k", code.substring(un(l, 0).offset,
                un(l, 0).offset + 1));
        assertEquals("v", code.substring(un(l, 1).offset,
                un(l, 1).offset + 1));
    }

    @Test
    public void sort_deuxParamètresDuMêmeArgument() {
        // Comparator<T> câble DEUX paramètres lambda sur le MÊME
        // argument générique 0 — l'un et l'autre valent l'élément.
        String code = "class A { void t() {"
                + " List<String> noms = null;"
                + " noms.sort((a, b) -> a.compareTo(b)); } }";
        List<MiniJavaLambdas.Indice> l = indices(code);
        assertEquals(2, l.size());
        assertEquals("String", un(l, 0).type);
        assertEquals("String", un(l, 1).type);
    }

    // ── La méthode déclarée dans le fichier ────────────────────

    @Test
    public void méthodeDéclarée_sesParamètresFonctionnelsServent() {
        String code = "class A {"
                + " void appliquer(java.util.function.Consumer<String> f) { }"
                + " void t() { appliquer(x -> System.out.println(x)); } }";
        List<MiniJavaLambdas.Indice> l = indices(code);
        assertEquals(1, l.size());
        assertEquals("String", un(l, 0).type);
        // La source dite dans le tooltip : le paramètre de l'appel.
        assertTrue(un(l, 0).info.contains("Consumer<String>"));
    }

    // ── Zéro devinette ─────────────────────────────────────────

    @Test
    public void listeBrute_sansGénériques_neSertRien() {
        String code = "class A { void t() {"
                + " List noms = null;"
                + " noms.forEach(n -> System.out.println(n)); } }";
        assertEquals(0, indices(code).size());
    }

    @Test
    public void lambdaSansParamètre_neSertRien() {
        String code = "class A { void t() {"
                + " Runnable r = () -> System.out.println(); } }";
        assertEquals(0, indices(code).size());
    }

    @Test
    public void lambdaTypé_neSertRien() {
        // Le type est déjà écrit : l'indice n'apporterait rien.
        String code = "class A { void t() {"
                + " List<String> noms = null;"
                + " noms.forEach((String s) -> System.out.println(s)); } }";
        assertEquals(0, indices(code).size());
    }

    @Test
    public void flècheDeSwitch_neSertRien() {
        String code = "class A { void t(int c) {"
                + " int x = switch (c) { case 1 -> 10; default -> 0; }; } }";
        assertEquals(0, indices(code).size());
    }

    @Test
    public void flècheDansUneChaîne_neSertRien() {
        String code = "class A { void t() { String s = \"a -> b\"; } }";
        assertEquals(0, indices(code).size());
    }

    @Test
    public void fonctionnelInconnu_neSertRien() {
        // Un paramètre non fonctionnel à la position du lambda :
        // rien à câbler, rien à afficher.
        String code = "class A { void t() {"
                + " String s = découpe(n -> 1); }"
                + " String découpe(Object o) { return \"\"; } }";
        assertEquals(0, indices(code).size());
    }

    // ── La plage borne les indices ─────────────────────────────

    @Test
    public void plageHorsFlèche_neSertRien() {
        String code = "class A { void t() {"
                + " List<String> noms = null;"
                + " noms.forEach(n -> System.out.println(n)); } }";
        int flèche = code.indexOf("->");
        // Une plage STRICTEMENT AVANT la flèche : rien.
        assertEquals(0, MiniJavaLambdas.indices(code, 0, flèche).size());
        // La flèche incluse dans la plage : l'indice arrive.
        assertEquals(1, MiniJavaLambdas.indices(code, 0, flèche + 2).size());
    }

    @Test
    public void deuxLambdas_lesDeuxServis() {
        String code = "class A { void t() {"
                + " List<String> noms = null;"
                + " noms.forEach(a -> System.out.println(a));"
                + " noms.removeIf(b -> b.isEmpty()); } }";
        List<MiniJavaLambdas.Indice> l = indices(code);
        assertEquals(2, l.size());
        assertEquals("a", code.substring(un(l, 0).offset, un(l, 0).offset + 1));
        assertEquals("b", code.substring(un(l, 1).offset, un(l, 1).offset + 1));
    }

    @Test
    public void texteNul_neSertRien() {
        assertEquals(0, MiniJavaLambdas.indices(null, 0, 10).size());
        assertEquals(0, MiniJavaLambdas.indices("a -> 1", 5, 2).size());
    }
}

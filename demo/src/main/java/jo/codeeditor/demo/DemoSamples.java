package jo.codeeditor.demo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Échantillons de code pour chaque langage intégré à code-editor.
 *
 * <p>Chaque échantillon est volontairement court mais représentatif :
 * commentaires, chaînes, nombres, mots-clés variés, structures pliables et
 * au moins un marqueur {@code TODO}/{@code FIXME} pour alimenter les
 * diagnostics synthétiques de l'analyseur factice.</p>
 */
final class DemoSamples {

    /** Libellé affiché → identifiant de langage (l'ordre d'insertion est celui du sélecteur). */
    static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("Java", "java");
        LANGUAGES.put("Kotlin", "kotlin");
        LANGUAGES.put("Scala", "scala");
        LANGUAGES.put("Groovy", "groovy");
        LANGUAGES.put("Python", "python");
        LANGUAGES.put("JavaScript", "javascript");
        LANGUAGES.put("TypeScript", "typescript");
        LANGUAGES.put("PHP", "php");
        LANGUAGES.put("Ruby", "ruby");
        LANGUAGES.put("Lua", "lua");
        LANGUAGES.put("Shell", "shell");
        LANGUAGES.put("C", "c");
        LANGUAGES.put("C++", "cpp");
        LANGUAGES.put("Go", "go");
        LANGUAGES.put("Rust", "rust");
        LANGUAGES.put("Swift", "swift");
        LANGUAGES.put("Dart", "dart");
        LANGUAGES.put("XML / HTML", "xml");
        LANGUAGES.put("JSON", "json");
        LANGUAGES.put("YAML", "yaml");
        LANGUAGES.put("TOML", "toml");
        LANGUAGES.put("Properties", "properties");
        LANGUAGES.put("CSS", "css");
        LANGUAGES.put("Markdown", "markdown");
        LANGUAGES.put("SQL", "sql");
        LANGUAGES.put("Smali", "smali");
        LANGUAGES.put("Log", "log");
    }

    private static final String JAVA = """
            package demo;

            import java.util.List;

            /**
             * Calculatrice de démonstration — coloration, plis et complétion.
             */
            public final class Calculator {

                // TODO: ajouter la gestion des nombres négatifs
                private final List<String> history = new java.util.ArrayList<>();
                private static final double TAX_RATE = 0.20;

                public int add(int a, int b) {
                    history.add(a + " + " + b + " = " + (a + b));
                    return a + b;
                }

                /** Applique la taxe et arrondit au centime. */
                public double withTax(double amount) {
                    return Math.round(amount * (1 + TAX_RATE) * 100) / 100.0;
                }

                public record Operation(String label, int result) {
                    public String pretty() {
                        return label + " -> " + result;
                    }
                }

                @Override
                public String toString() {
                    return String.join("; ", history); // FIXME: tronquer l'historique trop long
                }
            }
            """;

    private static final String KOTLIN = """
            package demo

            import kotlinx.coroutines.launch

            /**
             * Gestionnaire de notes de la démo.
             */
            class NoteRepository(private val pageSize: Int = 20) {

                // TODO: mettre en cache les résultats paginés
                private val notes = mutableListOf<Pair<String, Long>>()

                fun add(title: String, timestamp: Long = System.currentTimeMillis()) {
                    require(title.isNotBlank()) { "Le titre ne peut pas être vide" }
                    notes += title to timestamp
                }

                fun latest(count: Int = pageSize): List<Pair<String, Long>> =
                    notes.sortedByDescending { it.second }.take(count)

                suspend fun refresh(onDone: () -> Unit) = launch {
                    when {
                        notes.isEmpty() -> println("Aucune note")
                        else -> onDone()
                    }
                }
            }
            """;

    private static final String SCALA = """
            package demo

            /** File persistante de la démo —Scala. */
            object TaskQueue {

                // TODO: rendre la file thread-safe
                case class Task(id: Int, label: String, done: Boolean = false)

                private var tasks: List[Task] = Nil

                def push(label: String): Unit =
                    tasks = Task(tasks.map(_.id).sum, label) :: tasks

                def pending: List[Task] = tasks.filterNot(_.done)

                def summary: String = pending match {
                    case Nil   => "Rien à faire"
                    case rest  => s"${rest.size} tâches en attente"
                }
            }
            """;

    private static final String GROOVY = """
            // Gestionnaire de configuration de la démo — Groovy.
            class ConfigLoader {

                // TODO: valider les clés inconnues
                private final Map<String, Object> values = [:]

                def put(String key, Object value) {
                    values[key] = value
                }

                def get(String key, Object fallback = null) {
                    values.getOrDefault(key, fallback)
                }

                def each(Closure body) {
                    values.each { k, v -> body(k, v) }
                }

                static void main(String[] args) {
                    def config = new ConfigLoader()
                    config.put('name', 'code-editor')
                    println "Chargé: ${config.get('name')}"
                }
            }
            """;

    private static final String PYTHON = """
            # Générateur de rapports de la démo — Python.
            from dataclasses import dataclass, field
            from typing import List


            @dataclass
            class Report:
                titre: str
                lignes: List[str] = field(default_factory=list)

                def ajouter(self, texte: str) -> None:
                    self.lignes.append(texte)

                def rendre(self, largeur: int = 72) -> str:
                    # TODO: couper les lignes trop longues
                    bord = '=' * largeur
                    return f"{bord}\\n{self.titre}\\n{bord}\\n" + "\\n".join(self.lignes)


            def principal() -> None:
                rapport = Report("Rapport de démonstration")
                rapport.ajouter("Éditeur : code-editor")
                rapport.ajouter(f"Lines count : {len(rapport.lignes)}")
                print(rapport.rende())


            if __name__ == "__main__":
                principal()
            """;

    private static final String JAVASCRIPT = """
            // Panier de la démo — JavaScript.
            export function creerPanier() {
              // TODO: persister le panier dans localStorage
              const articles = [];

              return {
                ajouter(article, quantite = 1) {
                  const existant = articles.find((a) => a.id === article.id);
                  if (existant) existant.quantite += quantite;
                  else articles.push({ ...article, quantite });
                },
                total() {
                  return articles.reduce((somme, a) => somme + a.prix * a.quantite, 0);
                },
                async commander() {
                  const reponse = await fetch("/api/commande", {
                    method: "POST",
                    body: JSON.stringify({ articles }),
                  });
                  if (!reponse.ok) throw new Error(`Echec HTTP ${reponse.status}`);
                  return reponse.json();
                },
              };
            }
            """;

    private static final String TYPESCRIPT = """
            // Service de suivi de la démo — TypeScript.
            export interface Evenement {
              readonly id: number;
              readonly type: "clic" | "focus" | "saisie";
              charge?: Record<string, unknown>;
              horodatage: Date;
            }

            export enum Granularite { Brut, Minute, Heure }

            export class Suivi {
                // TODO: compact au-delà de 10 000 evenements
                private readonly evenements: Evenement[] = [];

                public enregistrer(e: Evenement): void {
                    this.evenements.push(e);
                }

                public compter(type: Evenement["type"]): number {
                    return this.evenements.filter((x) => x.type === type).length;
                }

                public segmenter<T>(taille: number): T[][] {
                    const sortie: T[][] = [];
                    for (let i = 0; i < taille; i += 1) sortie.push([]);
                    return sortie;
                }
            }
            """;

    private static final String PHP = """
            <?php
            // Catalogue de la démo — PHP.
            declare(strict_types=1);

            final class Catalogue
            {
                /** @var array<string, float> */
                private array $prix = [];

                // TODO: charger les prix depuis la base
                public function ajouter(string $reference, float $prix): void
                {
                    if ($prix < 0.0) {
                        throw new InvalidArgumentException("Prix invalide : {$prix}");
                    }
                    $this->prix[$reference] = $prix;
                }

                public function totalPanier(array $references): float
                {
                    return array_sum(array_map(
                        fn(string $r) => $this->prix[$r] ?? 0.0,
                        $references
                    ));
                }
            }
            """;

    private static final String RUBY = """
            # Carnet d'adresses de la démo — Ruby.
            class Carnet
              # TODO: dedupliquer par courriel
              def initialize
                @contacts = []
              end

              def ajouter(nom:, courriel:, tel: nil)
                @contacts << { nom: nom, courriel: courriel, tel: tel }
              end

              def rechercher(motif)
                @contacts.select { |c| c[:nom] =~ /#{motif}/i }
              end

              def taille
                @contacts.size
              end
            end

            carnet = Carnet.new
            carnet.ajouter(nom: "Ada", courriel: "ada@example.org")
            puts "Contacts : #{carnet.taille}"
            """;

    private static final String LUA = """
            -- Serpent de la démo — Lua.
            local serpent = {}
            serpent.__index = serpent

            -- TODO: gerer les collisions avec les bords
            function serpent.nouveau(x, y, taille)
                local self = setmetatable({}, serpent)
                self.corps = { {x, y} }
                self.taille = taille or 3
                return self
            end

            function serpent:avancer(dx, dy)
                local tete = self.corps[1]
                table.insert(self.corps, 1, { tete[1] + dx, tete[2] + dy })
                if #self.corps > self.taille then
                    table.remove(self.corps)
                end
            end

            function serpent:longueur()
                return #self.corps
            end

            return serpent
            """;

    private static final String SHELL = """
            #!/usr/bin/env bash
            # Script de préparation de la démo — Shell.
            set -euo pipefail

            VERSION="3.38.0"
            DESTINATION="${1:-./dist}"

            # TODO: verifier la présence de zip avant de continuer
            echo "Préparation de code-editor v${VERSION} vers ${DESTINATION}"

            mkdir -p "${DESTINATION}"

            for module in cel-core cel-ui cel-lsp-api cel-lsp; do
                echo "  module : ${module}"
                cp -r "editor/${module}" "${DESTINATION}/"
            done

            if [[ -d "${DESTINATION}" ]]; then
                echo "OK — $(find "${DESTINATION}" -type f | wc -l) fichiers copiés"
            fi
            """;

    private static final String C = """
            /*
             * Pile générique de la démo — C.
             */
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>

            #define TAILLE_MAX 64

            typedef struct {
                int donnees[TAILLE_MAX];
                size_t sommet;
            } Pile;

            void pile_init(Pile *p) {
                p->sommet = 0;
            }

            /* TODO: renvoyer un code d'erreur quand la pile est pleine */
            int pile_pousser(Pile *p, int valeur) {
                if (p->sommet >= TAILLE_MAX) return -1;
                p->donnees[p->sommet++] = valeur;
                return 0;
            }

            int pile_depiler(Pile *p, int *sortie) {
                if (p->sommet == 0) return -1;
                *sortie = p->donnees[--p->sommet];
                return 0;
            }

            int main(void) {
                Pile p;
                pile_init(&p);
                pile_pousser(&p, 42);
                int valeur = 0;
                pile_depiler(&p, &valeur);
                printf("valeur = %d\\n", valeur);
                return EXIT_SUCCESS;
            }
            """;

    private static final String CPP = """
            // Graphe de la démo — C++.
            #include <iostream>
            #include <map>
            #include <string>
            #include <vector>

            namespace demo {

            class Graphe {
            public:
                // TODO: memoiser les plus courts chemins
                void ajouterArete(const std::string& a, const std::string& b, int poids) {
                    aretes_[a].push_back({b, poids});
                    aretes_[b].push_back({a, poids});
                }

                int degre(const std::string& sommet) const {
                    auto it = aretes_.find(sommet);
                    return it == aretes_.end() ? 0 : static_cast<int>(it->second.size());
                }

            private:
                struct Arete { std::string voisin; int poids; };
                std::map<std::string, std::vector<Arete>> aretes_;
            };

            }  // namespace demo

            int main() {
                demo::Graphe g;
                g.ajouterArete("A", "B", 3);
                std::cout << "degre(A) = " << g.degre("A") << std::endl;
                return 0;
            }
            """;

    private static final String GO = """
            // Service de santé de la démo — Go.
            package sante

            import (
                "fmt"
                "time"
            )

            // TODO: ajouter une sonde par dépendance
            type Sonde struct {
                Nom      string
                Sain     bool
                Derniere time.Time
            }

            type Service struct {
                sondes map[string]Sonde
            }

            func Nouveau() *Service {
                return &Service{sondes: make(map[string]Sonde)}
            }

            func (s *Service) Enregistrer(sonde Sonde) {
                sonde.Derniere = time.Now()
                s.sondes[sonde.Nom] = sonde
            }

            func (s *Service) Rapport() string {
                sains := 0
                for _, sonde := range s.sondes {
                    if sonde.Sain {
                        sains++
                    }
                }
                return fmt.Sprintf("%d/%d sondes saines", sains, len(s.sondes))
            }
            """;

    private static final String RUST = """
            // Compteur de mots de la démo — Rust.
            use std::collections::HashMap;

            // TODO: normaliser les accents avant de compter
            pub fn compter_mots(texte: &str) -> HashMap<&str, usize> {
                let mut compteur = HashMap::new();
                for mot in texte.split_whitespace() {
                    *compteur.entry(mot.trim_matches(|c: char| !c.is_alphanumeric())).or_insert(0) += 1;
                }
                compteur
            }

            pub struct Journal {
                lignes: Vec<String>,
            }

            impl Journal {
                pub fn nouveau() -> Self {
                    Journal { lignes: Vec::new() }
                }

                pub fn ajouter(&mut self, ligne: impl Into<String>) -> usize {
                    self.lignes.push(ligne.into());
                    self.lignes.len()
                }
            }

            fn main() {
                let mut journal = Journal::nouveau();
                journal.ajouter("première ligne");
                println!("lignes = {}", journal.lignes.len());
            }
            """;

    private static final String SWIFT = """
            import Foundation

            /// Rappels de la démo — Swift.
            final class Rappels {

                // TODO: programmer une notification locale
                private var elements: [(titre: String, quand: Date)] = []

                func ajouter(_ titre: String, dans heures: Double = 1.0) {
                    let echeance = Date().addingTimeInterval(heures * 3600)
                    elements.append((titre, echeance))
                }

                func aVenir() -> [(titre: String, quand: Date)] {
                    elements
                        .filter { $0.quand > Date() }
                        .sorted { $0.quand < $1.quand }
                }

                var description: String {
                    guard !elements.isEmpty else { return "Aucun rappel" }
                    return elements.map { "\\($0.titre)" }.joined(separator: ", ")
                }
            }
            """;

    private static final String DART = """
            // Chronomètre de la démo — Dart.
            import 'dart:async';

            // TODO: exporter les tours au format CSV
            class Chrono {
                final List<Duration> _tours = [];
                DateTime? _depart;

                void demarrer() {
                    _depart = DateTime.now();
                }

                Duration? tour() {
                    final depart = _depart;
                    if (depart == null) return null;
                    final ecoule = DateTime.now().difference(depart);
                    _tours.add(ecoule);
                    return ecoule;
                }

                Future<String> rapport() async {
                    if (_tours.isEmpty) return 'Aucun tour';
                    final total = _tours.reduce((a, b) => a + b);
                    return '${_tours.length} tours, total ${total.inMilliseconds} ms';
                }
            }
            """;

    private static final String XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- Écran principal de la démo — XML. -->
            <LinearLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:padding="16dp">

                <!-- TODO: remplacer par un champ Material -->
                <EditText
                    android:id="@+id/recherche"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:hint="Rechercher un symbole"
                    android:inputType="text" />

                <jo.codeeditor.view.EditorView
                    android:id="@+id/editeur"
                    android:layout_width="match_parent"
                    android:layout_height="0dp"
                    android:layout_weight="1" />

                <Button
                    android:id="@+id/valider"
                    style="?attr/materialButtonStyle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="end"
                    android:text="Valider" />

            </LinearLayout>
            """;

    private static final String JSON = """
            {
              "nom": "code-editor",
              "version": "3.38.0",
              "licence": "MIT",
              "modules": [
                { "id": "cel-core", "role": "moteur" },
                { "id": "cel-ui", "role": "vue Canvas" },
                { "id": "cel-lsp", "role": "LSP4J" }
              ],
              "langages": 27,
              "tests": { "unitaires": 962, "echecs": 0 },
              "meta": {
                "pieceJointe": true,
                "tags": ["editeur", "android", "canvas"],
                "poidsMo": 3.05,
                "notes": "TODO: versionner les schemas"
              }
            }
            """;

    private static final String YAML = """
            # Intégration continue de la démo — YAML.
            name: ci

            on:
              push:
                branches: [main]
              pull_request:

            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: JDK 17
                    uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: "17"
                  - name: Assemble
                    # TODO: activer le cache Gradle
                    run: ./gradlew assembleDebug test
                  - name: Artifacts
                    if: success()
                    uses: actions/upload-artifact@v4
                    with:
                      name: apk
                      path: demo/build/outputs/apk/debug/*.apk
            """;

    private static final String TOML = """
            # Configuration de la démo — TOML.
            name = "code-editor-demo"
            version = "3.38.0"
            edition = "2026"

            [edition]
            police = { taille = 14, famille = "monospace" }
            theme = "sombre"

            [analyseur]
            factice = true
            severites = ["info", "warning", "erreur"]

            # TODO: activer le serveur LSP en option
            [[modules]]
            id = "cel-core"
            testable = true

            [[modules]]
            id = "cel-ui"
            testable = true
            """;

    private static final String PROPERTIES = """
            # Paramètres de la démo — Properties.
            edition.taille=14
            edition.theme=sombre
            edition.ligatures=false

            # Diagnostics factices
            analyseur.debounceMs=600
            analyseur.todoSeverite=info
            analyseur.ligneLongue=100

            # TODO: externaliser les couleurs du theme
            theme.fond=09090B
            theme.accent=34D399
            theme.erreur=F87171
            theme.avertissement=FBBF24
            """;

    private static final String CSS = """
            /* Feuille de style de la démo — CSS. */
            :root {
                --fond: #09090b;
                --accent: #34d399;
                --texte: #e4e4e7;
            }

            body {
                margin: 0;
                background: var(--fond);
                color: var(--texte);
                font-family: "JetBrains Mono", monospace;
            }

            /* TODO: reduire la taille du code sur mobile */
            .editor {
                max-width: 72ch;
                margin: 0 auto;
                padding: 1.5rem;
                border: 1px solid #27272a;
                border-radius: 0.75rem;
            }

            .editor .keyword {
                color: var(--accent);
                font-weight: 600;
            }

            @media (max-width: 640px) {
                .editor { padding: 0.75rem; font-size: 0.9rem; }
            }
            """;

    private static final String MARKDOWN = """
            # Démo code-editor

            Un **terrain de jeu** pour la bibliothèque code-editor v3.38.0.

            ## Fonctionnalités

            - 27 langages colorés
            - Loupe de sélection et poignées
            - Annuler / rétablir avec coalescence
            - Pliage et retour à la ligne

            ## Exemple de code

            ```java
            EditorSession session = new EditorSession(EditorDocument.of(texte));
            editorView.setSession(session);
            ```

            > TODO: ajouter une section multi-curseurs (à venir)

            | Module    | Rôle         |
            |-----------|--------------|
            | cel-core  | moteur       |
            | cel-ui    | vue Canvas   |

            Voir la [documentation](https://github.com/jjoblab/code-editor).
            """;

    private static final String SQL = """
            -- Base de la démo — SQL.
            CREATE TABLE module (
                id          INTEGER PRIMARY KEY,
                nom         TEXT NOT NULL UNIQUE,
                role        TEXT,
                testable    INTEGER DEFAULT 0
            );

            CREATE INDEX idx_module_role ON module(role);

            -- TODO: ajouter une contrainte sur les doublons de role
            INSERT INTO module (nom, role, testable) VALUES
                ('cel-core', 'moteur', 1),
                ('cel-ui', 'vue', 1),
                ('cel-lsp', 'lsp4j', 0);

            SELECT m.role,
                   COUNT(*)        AS modules,
                   SUM(m.testable) AS testables
              FROM module AS m
             WHERE m.nom LIKE 'cel-%'
             GROUP BY m.role
             ORDER BY modules DESC;
            """;

    private static final String SMALI = """
            .class public Ldemo/Demo;
            .super Ljava/lang/Object;

            # TODO: extraire les chaines dans strings.xml
            .method public static saluer(Ljava/lang/String;)Ljava/lang/String;
                .locals 2

                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

                const-string v1, "Bonjour, "
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

                invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

                move-result-object v0
                return-object v0
            .end method
            """;

    private static final String LOG = """
            2026-09-28 07:46:10.114 [main] INFO  Gradle - Starting: bun install
            2026-09-28 07:46:11.002 [main] INFO  Gradle - Completed: bun install (0.9s)
            2026-09-28 07:46:12.318 [main] INFO  Editor - Session créée (langage=java)
            2026-09-28 07:46:12.640 [restyle] DEBUG Editor - Restyle incrémental lignes 1..24
            2026-09-28 07:46:13.001 [restyle] WARN  Editor - TODO détecté ligne 9 : nombres négatifs
            2026-09-28 07:46:14.520 [ui] ERROR Editor - FIXME ligne 22 : historique trop long
            2026-09-28 07:46:14.521 [ui] INFO  Editor - Diagnostics publiés (2)
            2026-09-28 07:46:15.000 [main] INFO  Tests - 962/962 verts
            2026-09-28 07:46:15.100 [main] INFO  Lint - 0 problème
            """;

    private DemoSamples() {
    }

    /** Retourne l'échantillon du langage donné (ou le contenu Java par défaut). */
    static String sampleFor(String language) {
        switch (language == null ? "" : language) {
            case "kotlin": return KOTLIN;
            case "scala": return SCALA;
            case "groovy": return GROOVY;
            case "python": return PYTHON;
            case "javascript": return JAVASCRIPT;
            case "typescript": return TYPESCRIPT;
            case "php": return PHP;
            case "ruby": return RUBY;
            case "lua": return LUA;
            case "shell": return SHELL;
            case "c": return C;
            case "cpp": return CPP;
            case "go": return GO;
            case "rust": return RUST;
            case "swift": return SWIFT;
            case "dart": return DART;
            case "xml": case "html": return XML;
            case "json": return JSON;
            case "yaml": return YAML;
            case "toml": return TOML;
            case "properties": return PROPERTIES;
            case "css": return CSS;
            case "markdown": return MARKDOWN;
            case "sql": return SQL;
            case "smali": return SMALI;
            case "log": return LOG;
            default: return JAVA;
        }
    }
}

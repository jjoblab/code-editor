# Plan 06 — Minification côté hôte (R8/ProGuard)

> Phase 2 — plan d'implémentation, sans implémentation lourde.

## 1. Constat

- Aucun `consumerProguardFiles` ni `*.pro` dans les 4 modules alors
  que LSP4J (proxys dynamiques, générique, réflexion sur les types de
  messages) et Gson (réflexion sur les champs des classes
  `Params`/`Result` du protocole) sont notoirement sensibles à R8.
- La démo n'est pas construite en `minifyEnabled true` : aucune
  preuve qu'un hôte minifié fonctionne.

## 2. Vérification d'abord (mesure, pas d'implémentation aveugle)

1. Activer TEMPORAIREMENT dans la démo :
   `buildTypes { release { minifyEnabled true; shrinkResources true;
   proguardFiles getDefaultProguardFile('proguard-android-optimize.txt') } }`
   — build, installation, parcours de fumée : ouverture d'un
   fichier, frappe, complétion, plis, renommage, diagnostic.
2. Si tout passe SANS règles : ne rien ajouter (minimalisme), juste
   documenter le parcours de fumée dans ce plan.
3. Si des fonctions cassent (symptômes attendus : réponses LSP
   désérialisées en `null`/champs manquants, proxys
   `ServiceLoader`/launcher absents) : passer à l'étape 3.

## 3. Règles de conservation (si nécessaires)

`cel-lsp/consumer-rules.pro` (embarqué dans l'AAR via
`consumerProguardFiles` — l'hôte n'a rien à faire) :

```proguard
# LSP4J : proxys et classes de protocole par réflexion.
-keep class org.eclipse.lsp4j.** { *; }
# Gson sérialise les classes de messages du protocole par réflexion
# sur les champs — conserver les modèles utilisés par l'API publique.
-keep class jo.codeeditor.lang.model.** { *; }
-keep class jo.codeeditor.lsp.model.** { *; }
# SPI hôte : implémentations branchées par ServiceLoader.
-keepnames class jo.codeeditor.** { *; }
```

(cible fine : ne garder que ce que la mesure de l'étape 2 a cassé ;
chaque règle doit citer le symptôme qui l'a motivée en commentaire).

## 4. Étapes

1. Étape de mesure (§2) — commit `test(demo)` jetable ou branche
   locale, non mergée si rien ne casse.
2. `consumer-rules.pro` minimal + `consumerProguardFiles` dans
   `cel-lsp/build.gradle.kts` (uniquement si l'étape 1 a cassé).
3. CI : la variante `release` de la démo minifiée construite
   (assemblage seul — pas de parcours d'installation en CI) pour
   empêcher une régression des règles.

## 5. Risques

- Les règles trop larges (`-keep class ** { *; }`) annuleraient la
  minification pour l'hôte : chaque règle doit être justifiée.
- `getDefaultFile('optimize')` peut casser `Class.getSimpleName`
  (renommage) : les tests de la démo minifiée couvrent le chemin
  « nom de langue » (`LanguageRegistry`).
- shrinkResources : les ressources `cel_*` (plan 05) doivent être
  référencées par code — R8 le voit, pas de règle spécifique.

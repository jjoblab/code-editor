# Plan 08 — Stabilité de l'API publique

> Phase 2 — plan d'implémentation, sans implémentation lourde.

## 1. Constat (mesuré)

- 0 annotation de nullité dans `cel-core`, `cel-lsp-api`, `cel-lsp`
  (pas de `@Nullable`/`@NonNull`) : les contrats null sont
  implicites (Javadoc parfois).
- 14 annotations `@Deprecated` existantes, sans politique de retrait
  documentée.
- Aucun contrôle de compatibilité binaire en CI : une suppression de
  méthode publique n'est détectée que par les consommateurs.
- Rupture déjà signalée au CHANGELOG courant :
  `EditorPopupManager.FEATURE_EXECUTOR` (Lot 7).

## 2. Cible

1. **Nullité** : annotations `la bibliothèque d'annotations externe`
   (`@Nullable`/`@NotNull`) — dépendance compileOnly, taille nulle
   dans l'AAR (annotations de classe `CLASS` retention), compatible
   API 24, MIT/e licence Apache-2.0 — à valider par le propriétaire
   (ajout de dépendance, règle 8 du protocole agent).
2. **Contrôle de compatibilité binaire en CI** : dump de l'API
   publique (`japi-compliance-checker` ou équivalent en tâche Gradle
   simple : `javap -public` sur les AAR de la version balisée vs la
   courante) — échec si retrait/mutation non signalés au CHANGELOG
   (section *Changed/Removed*).
3. **Politique de dépréciation** : `@Deprecated(forRemoval = true)`
   + note CHANGELOG obligatoire ; retrait au plus tôt 2 versions
   mineures après la dépréciation ; les 14 dépréciations existantes
   inventoriées avec échéance.

## 3. Étapes

1. Inventaire des 14 `@Deprecated` (fichier :
   `docs/plans/08-deprecations.md` — classe, remplacement, version
   d'origine, échéance).
2. Dépendance annotations (compileOnly, tous modules purs) +annoter
   `cel-lsp-api` d'abord (SPI consommé par les hôtes : plus grand
   gain), puis `cel-core`, puis `cel-lsp`.
3. Tâche Gradle `checkApiCompatibility` : compare l'API publique
   (javap) de l'AAR courant avec celle du dernier tag ; produit un
   rapport ; en CI (job build, non bloquant v1, bloquant quand
   stabilisé).
4. Documenter la politique dans CONTRIBUTING (« Déprécier et
   retirer ») et AGENT.md.

## 4. Risques

- Ajout de dépendance : règle 8 (justification écrite) — les IDE professionnels
  annotations : ~20 Ko, compileOnly, licence Apache-2.0/MIT,
  compatibilité API 24 : validé par le propriétaire AVANT (décision
  à inscrire au compte rendu).
- `javap` sur AAR : extraire classes.jar d'abord ; comparer les
  signatures normalisées (ordre des méthodes ignoré).
- Faux positifs de rupture (génériques, ordre) : rapport humain
  avant blocage CI.

## 5. Non-régression

Suite complète verte ; la CI ne change pas d'exit code tant que
l'étape 3 n'est pas en mode bloquant.

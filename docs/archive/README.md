# Archive des documents historiques

> Documents **figés** : rapports de session, analyses et instructions rédigés
> à des moments précis de l'histoire du dépôt. Ils décrivent l'état du code
> **au moment de leur rédaction** et ne sont plus maintenus. Les liens et
> chemins internes peuvent refléter l'organisation des fichiers d'alors.

La documentation vivante vit ailleurs :

- racine : [`README.md`](../../README.md), [`FEATURES.md`](../../FEATURES.md),
  [`USAGE.md`](../../USAGE.md), [`BUILD.md`](../../BUILD.md),
  [`ARCHITECTURE.md`](../../ARCHITECTURE.md), [`CHANGELOG.md`](../../CHANGELOG.md) ;
- décisions d'architecture : [`docs/adr/`](../adr/) ;
- plans de travail en cours : [`docs/plans/`](../plans/) ;
- par module : `editor/<module>/README.md` et `demo/README.md`.

## Contenu

| Document | Nature | Époque |
|---|---|---|
| [`RAPPORT.md`](RAPPORT.md) | Rapport de livraison des lots 0 (gutter) + 1 (édition contextuelle) + 1+3 (performance des popups), avec niveau de preuve par point | v3.6x |
| [`RAPPORT_VERIFICATION.md`](RAPPORT_VERIFICATION.md) | Vérification et durcissement généraux (méthode rouge→vert, jalons de build, garde-fous) | v3.49.x |
| [`RAPPORT-ACTIONS-LSP-CODE-EDITOR.md`](RAPPORT-ACTIONS-LSP-CODE-EDITOR.md) | Plan et état de livraison du lot « quick actions et intelligence LSP » (P0-1 → P2-11) | 3.50.x → 3.51.x |
| [`RAPPORT-TACTILE-CONTEXTE-CODE-EDITOR.md`](RAPPORT-TACTILE-CONTEXTE-CODE-EDITOR.md) | Plan et état de livraison du lot « édition tactile et contexte visuel » (sticky, inlays, barre de sélection, loupe, popup diagnostic) | 3.52.0 → 3.57.0 |
| [`ANALYSE-GAPS-ANDROID-STUDIO.md`](ANALYSE-GAPS-ANDROID-STUDIO.md) | Sixième analyse d'écarts avec l'éditeur d'Android Studio (référentiel de comparaison) | 3.63.0 |
| [`MISE-A-JOUR-CODEIDE.md`](MISE-A-JOUR-CODEIDE.md) | Instructions de montée de version adressées au dépôt consommateur CodeIDE | 3.57.0 → 3.69.0 |
| [`CHANGELOG-archive.md`](CHANGELOG-archive.md) | Sections du CHANGELOG antérieures à la période courante (scission A-08) | ≤ 3.70.x |
| [`audit-3.69.0/`](audit-3.69.0/) | Audit de contre-vérification de la 3.69.0 (vérification, étude comparative, plan d'exécution, suivi d'exécution des lots L0→L10) | 3.69.0 |

## Politique

- Un document entre ici quand il décrit un **chantier terminé** ou une
  **décision depuis remplacée** : sa valeur est historique (traçabilité,
  niveaux de preuve), pas opérationnelle.
- Le contenu est conservé **inchangé** (aucune retouche rétrospective) ;
- seuls les liens de navigation cassés par le déplacement ont été corrigés
  (voir l'historique git de ce dossier) ;
- toute mise à jour de fond se fait dans la documentation vivante, pas ici.

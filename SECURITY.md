# Politique de sécurité

## Versions prises en charge

| Version | Prise en charge |
|---------|-----------------|
| 3.49.x  | oui             |
| < 3.49  | non             |

## Signaler une vulnérabilité

**Ne jamais ouvrir une issue publique pour une faille de sécurité.**

Les signalements de sécurité se font par courriel à l'adresse du
propriétaire du dépôt (voir le profil GitHub `jjoblab`) — objet du
message : `[security] code-editor`.

Merci d'inclure :

- une description du problème et de son impact potentiel ;
- les étapes de reproduction (épuration de code, URI, serveur LSP
  malveillant, séquence d'entrées…) ;
- la version concernée (ou le commit) ;
- si possible, une esquisse de correctif.

## Délais et traitement

- Accusé de réception sous 7 jours.
- Évaluation et plan de correction communiqués sous 30 jours.
- Divulgation coordonnée : le correctif est publié avant la divulgation
  publique du détail ; crédit au rapporteur sauf demande contraire.

## Périmètre

La bibliothèque (`cel-core`, `cel-lsp-api`, `cel-lsp`, `cel-ui`) et
l'application de démonstration. Les failles des dépendances (LSP4J,
Gson) se rapportent à leurs projets respectifs — signalez-les là-bas,
signalez-nous seulement l'absence de mise à jour de notre part.

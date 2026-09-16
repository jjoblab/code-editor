# Multi-Cursor Editing — Plan de conception

## Vue d'ensemble

Le multi-cursor editing permet à l'utilisateur d'avoir plusieurs curseurs actifs
simultanément dans l'éditeur. Chaque édition (frappe, backspace, etc.) est appliquée
à tous les curseurs en parallèle.

## État actuel

- `EditorSession` gère une seule `Selection` (caret)
- `EditorRenderer` dessine un seul caret
- `EditorInputHandler` gère le touch pour un seul curseur
- `EditorImeBridge` gère l'IME pour un seul curseur
- `EditOps` applique les opérations à un seul curseur

## Bénéfices

- **Sora Editor ne l'a pas** — avantage compétitif
- **Sur mobile** : éditer N occurrences en une seule frappe (gain énorme sur clavier virtuel)
- **Sur tablette/DeX** : Cmd+Click pour ajouter un curseur (expérience desktop-like)
- **Refactoring express** : changer `private` → `public` sur 10 champs en une fois

## Architecture proposée

### 1. Selection → List<Selection> dans EditorSession

```java
// Avant
private Selection selection;

// Après
private final List<Selection> selections = new ArrayList<>();
private int primarySelectionIndex = 0; // pour le caret principal
```

### 2. Touch handling dans EditorInputHandler

- **Tap simple** : remplace toutes les sélections par une seule (comportement actuel)
- **Long-press + drag** : ajoute une nouvelle sélection (ne remplace pas les existantes)
- **Double-tap + drag** : sélection multi-lignes

### 3. Rendu dans EditorRenderer

- `drawCaret()` → `drawCarets()` : itère sur toutes les sélections
- Chaque caret a son propre état de blink (décalé pour la lisibilité)
- Le caret principal a une couleur légèrement différente

### 4. EditOps fan-out

```java
// Avant
public void insert(String text) {
    replaceRange(selection.start, selection.end, text);
}

// Après
public void insert(String text) {
    beginBatch();
    // Tri descendant pour ne pas décaler les offsets
    List<Selection> sorted = selections.stream()
        .sorted((a, b) -> Integer.compare(b.start, a.start))
        .collect(Collectors.toList());
    for (Selection sel : sorted) {
        replaceRange(sel.start, sel.end, text);
    }
    endBatch();
}
```

### 5. IME bridge

- `getSelectionStart()` / `getSelectionEnd()` retournent la sélection primaire
- `getSurroundingText()` retourne le texte autour du curseur primaire
- Les autres curseurs sont mis à jour silencieusement (pas de IME pour eux)

### 6. API publique

```java
// EditorView
public void addCursor(int offset);
public void removeCursor(int index);
public void clearCursors(); // garde seulement le curseur primaire
public List<Selection> getSelections();
public int getCursorCount();
```

## Effort estimé

- **EditorSession** : ~3 jours (Selection → List<Selection>, ~50 méthodes impactées)
- **EditorRenderer** : ~2 jours (drawCarets, blink multi-cursor)
- **EditorInputHandler** : ~2 jours (long-press pour ajouter curseur)
- **EditorImeBridge** : ~1 jour (sélection primaire seulement)
- **EditOps** : ~2 jours (fan-out des opérations)
- **Tests** : ~2 jours

**Total : ~2 semaines**

## Prérequis

- Stabiliser le LSP Java d'abord
- Splitter les god classes (EditorView, EditorRenderer) pour faciliter l'intégration

## Priorité

**Moyenne** — feature importante mais pas bloquante pour l'utilisation quotidienne.
À implémenter après la stabilisation du LSP.

## Références

- VS Code multi-cursor : Cmd+Click (desktop), Alt+Click (alternative)
- IntelliJ IDEA : Cmd+G (add selection at next occurrence)
- Sublime Text : Middle-click drag (column selection)
- Sora Editor : **ne supporte pas** le multi-cursor (issue ouverte depuis des années)

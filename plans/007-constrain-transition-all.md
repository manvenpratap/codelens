# 007 — Constrain Unbounded transition: all Across Interactive UI Components

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: MEDIUM
- **Category**: Performance
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~50 lines

## Problem

The blanket rule `transition: all` is used over 150 times throughout `style.css`.
Examples:
- Line 343, 448, 548: `.header-action-btn`, `.btn-icon`
- Line 1915, 1935: `.stat-card`, `.stat-pill`
- Line 2838, 2917: `.knowledge-base-item`, `.accordion-header`
- Line 3181, 3319: `.tab`, `.tab-btn`
- Line 6420, 6819: `.scan-modal-card`, `.scan-stat-card`

Using `transition: all` instructs the rendering engine to listen to and interpolate every CSS property on the element, including layout properties (`width`, `height`, `padding`, `margin`, `box-shadow`, `border-width`), triggering style recalcs and paint thrashing on simple hovers.

## Target

Replace blanket `transition: all` on the highest-traffic interactive components with explicit, GPU-friendly properties:
- Pressable buttons / icons: `transition: transform var(--dur-press) var(--ease-out), background-color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);`
- Cards / tiles: `transition: border-color var(--dur-fast) var(--ease-out), background-color var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out);`
- Tabs / pills: `transition: background-color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);`

## Repo conventions to follow

- Buttons already have a performant base template at `style.css:170-179`:
  ```css
  transition: color var(--dur-fast) var(--ease-out),
              background-color var(--dur-fast) var(--ease-out),
              border-color var(--dur-fast) var(--ease-out),
              transform var(--dur-press) var(--ease-out),
              box-shadow var(--dur-fast) var(--ease-out);
  ```

## Steps

1. In `codelens-web/src/main/resources/web/style.css`, locate and replace `transition: all` on high-traffic elements:
   - Header action buttons and icon buttons: replace with `transition: background-color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);`
   - `.scan-stat-card`, `.stat-pill`: replace with `transition: background-color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out), transform var(--dur-fast) var(--ease-out);`
   - `.tab`, `.tab-btn`: replace with `transition: background-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out);`
   - `.knowledge-base-item`, `.accordion-header`: replace with `transition: background-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);`

## Boundaries

- Do NOT remove any hover pseudo-classes (`:hover`, `:active`, `:focus-visible`).
- Preserve any existing duration/easing tokens (`var(--dur-fast) var(--ease)`).

## Verification

- **Mechanical**: Inspect CSS syntax.
- **Feel check**:
  - Rapidly hover across header buttons, tabs, and list items.
  - Verify smooth 60fps interaction without stutter or dropped frames during active rendering.
- **Done when**: High-traffic components specify exact transition properties instead of `transition: all`.

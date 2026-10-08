# 004 — Correct Popovers & Dropdown Menus Transform Origins and Direction

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: MEDIUM
- **Category**: Physicality & origin
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~25 lines

## Problem

Popovers, dropdowns, and flyout menus throughout CodeLens currently lack an explicit `transform-origin` property, causing them to scale from `center center`. In the physical world, dropdowns and popouts bloom directly out from their triggering button or anchor point:
1. `.methods-archetypes-popover` (line 2171) is anchored at `bottom: calc(100% + 6px)` (popping up from the left panel footer pills). It scales from center instead of `bottom left`.
2. `.scan-summary-popover` (line 1015) is anchored at `top: calc(100% + 8px); left: 0` (dropping down from the header scan badge). It lacks `transform-origin` (defaults to center) AND uses `popoverFadeUp`, which translates upward into the header rather than blooming downward!
3. `.theme-dropdown-menu` (line 2480) is anchored at `top: calc(100% + 6px); right: 0` (dropping down from header theme button). It defaults to `transform-origin: center center` instead of `top right`.
4. `.archetype-multiselect-panel` (lines 3680-3695) has drop-down (`top: auto`) and drop-up variants (`bottom: calc(100% + 8px); right: 0`). It defaults to center origin instead of `top left` or `bottom right`.

```css
/* codelens-web/src/main/resources/web/style.css:1015 — current */
.scan-summary-popover {
  top: calc(100% + 8px);
  left: 0;
  animation: popoverFadeUp 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  /* Missing transform-origin, wrong upward translation */
}
```

## Target

1. Set `transform-origin: bottom left;` on `.methods-archetypes-popover`.
2. Set `transform-origin: top left;` on `.scan-summary-popover`, and switch its entrance animation to `fadeInDown` so it slides downward from the header badge.
3. Set `transform-origin: top right;` on `.theme-dropdown-menu` and `.hero-theme-dropdown-menu`.
4. Set `transform-origin: top right;` on standard `.archetype-multiselect-panel`, and `transform-origin: bottom right;` on `.archetype-multiselect-dropdown.drop-up .archetype-multiselect-panel`.

```css
/* target */
.scan-summary-popover {
  transform-origin: top left;
  animation: fadeInDown var(--dur-dropdown) var(--ease-out);
}

.methods-archetypes-popover {
  transform-origin: bottom left;
  animation: popoverFadeUp var(--dur-dropdown) var(--ease-out);
}

.theme-dropdown-menu {
  transform-origin: top right;
  animation: themeDropdownFadeIn var(--dur-fast) var(--ease-out);
}

.archetype-multiselect-panel {
  transform-origin: top right;
}
.archetype-multiselect-dropdown.drop-up .archetype-multiselect-panel {
  transform-origin: bottom right;
}
```

## Repo conventions to follow

- Duration scale `--dur-dropdown: 180ms;` and `--dur-fast: 140ms;` in `:root`.
- Token `--ease-out: cubic-bezier(0.23, 1, 0.32, 1);` in `:root`.
- Base UI and modern design systems anchor popover origins to trigger coordinates.

## Steps

1. In `codelens-web/src/main/resources/web/style.css`:
   - Line 1030: On `.scan-summary-popover`, add `transform-origin: top left;` and update `animation` to `fadeInDown var(--dur-dropdown) var(--ease-out);`.
   - Line 2171: On `.methods-archetypes-popover`, add `transform-origin: bottom left;`. Update duration/easing to `var(--dur-dropdown) var(--ease-out)`.
   - Line 2480: On `.theme-dropdown-menu`, add `transform-origin: top right;`.
   - Line 3680: On `.archetype-multiselect-panel`, add `transform-origin: top right;`.
   - Line 3690: On `.archetype-multiselect-dropdown.drop-up .archetype-multiselect-panel`, add `transform-origin: bottom right;`.

## Boundaries

- Modals (`.modal-dialog`) are centered in the viewport and are EXEMPT. Do not touch their center origin.
- Do NOT change positioning coordinates (`top`, `bottom`, `left`, `right`).

## Verification

- **Mechanical**: Inspect CSS syntax.
- **Feel check**:
  - In DevTools, set Animation playback to 25%.
  - Click the Theme switcher at top right: confirm the menu expands naturally from its top-right corner where the button is located.
  - Click the Scan summary badge at top left: confirm the card opens downwards and expands from its top-left corner.
  - Click the archetype pills at bottom left: confirm the popovers flower upwards from the pill.
- **Done when**: All popovers and dropdowns scale directly from their physical trigger anchors.

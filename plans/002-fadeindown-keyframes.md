# 002 — Define Missing fadeInDown Keyframe Animation

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: HIGH
- **Category**: Cohesion & tokens
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~15 lines

## Problem

`@keyframes fadeInDown` is referenced in three distinct selectors in `style.css`:
- Line 3687: `.archetype-multiselect-panel` (`animation: fadeInDown var(--dur-fast) var(--ease);`)
- Line 9378: `.archetype-form-default-anchor` (`animation: fadeInDown 0.2s ease-out;`)
- Line 9391: `.archetype-rule-form-wrap.is-inline` (`animation: fadeInDown 0.2s cubic-bezier(0.16, 1, 0.3, 1);`)

However, `@keyframes fadeInDown` was never defined in `style.css`. Because the identifier does not exist, the browser ignores the animation rule completely, causing panels and inline forms to jump into view with zero animation while sibling components animate smoothly.

```css
/* codelens-web/src/main/resources/web/style.css:3687 — current */
.archetype-multiselect-panel {
  ...
  animation: fadeInDown var(--dur-fast) var(--ease);
}
/* @keyframes fadeInDown is MISSING from style.css */
```

## Target

Define `@keyframes fadeInDown` following the physical motion standard (start with subtle scale and directional translate, never pure opacity or `scale(0)`):
- Starts at `opacity: 0; transform: translateY(-6px) scale(0.97);`
- Resolves to `opacity: 1; transform: translateY(0) scale(1);`
Also update lines 9378 and 9391 to consume the canonical token `var(--ease-out)` and `var(--dur-fast)`.

```css
/* target definition to place near @keyframes fadeInUp (around line 3715) */
@keyframes fadeInDown {
  from {
    opacity: 0;
    transform: translateY(-6px) scale(0.97);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
```

## Repo conventions to follow

- Existing sister animation `@keyframes fadeInUp` is defined at `style.css:3703-3712`.
- Physical pop animations in CodeLens start between `scale(0.96)` and `scale(0.97)` to avoid appearing from nowhere.

## Steps

1. In `codelens-web/src/main/resources/web/style.css`, locate `@keyframes fadeInUp` around line 3703.
2. Immediately before or after it, add the `@keyframes fadeInDown` block:
   ```css
   @keyframes fadeInDown {
     from {
       opacity: 0;
       transform: translateY(-6px) scale(0.97);
     }
     to {
       opacity: 1;
       transform: translateY(0) scale(1);
     }
   }
   ```
3. Update `.archetype-rule-form-wrap.is-inline` (around line 9391) from `animation: fadeInDown 0.2s cubic-bezier(0.16, 1, 0.3, 1);` to `animation: fadeInDown var(--dur-std) var(--ease-out);`.

## Boundaries

- Do NOT alter the visual styling, dimensions, or padding of `.archetype-multiselect-panel` or `.archetype-rule-form-wrap`.
- Do NOT touch existing `@keyframes fadeInUp`.

## Verification

- **Mechanical**: Grep for `@keyframes fadeInDown` in `style.css` and verify it resolves cleanly.
- **Feel check**:
  - Open the Graph View or Codebase 3D view and click the "Archetype Filter" dropdown. Confirm the dropdown menu slides gracefully downward from the button.
  - In Settings -> Archetype Rules, click "Add Rule" or "Edit". Confirm the inline form animates smoothly into position rather than snapping into the DOM.
- **Done when**: All elements referencing `fadeInDown` successfully trigger the defined keyframe animation without console warnings or layout jumps.

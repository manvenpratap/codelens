# 003 — Scope Reduced-Motion Reset to Retain Interactive Feedback

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: HIGH
- **Category**: Accessibility
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~20 lines

## Problem

In `style.css:22105-22116`, the `@media (prefers-reduced-motion: reduce)` media query uses the universal wildcard `*, *::before, *::after` to force `transition-duration: 0.01ms !important`. While this eliminates vestibular triggers caused by large spatial movement, it aggressively obliterates all subtle visual feedback:
- Button hover background color transitions vanish.
- Input focus outline transitions snap abrasively.
- Tab background highlight fades snap.
- Toast opacity dissolves become instantaneous jarring flickers.

Per Emil Kowalski / animations.dev standards: "Reduced motion means fewer and gentler animations, NOT zero. Keep transitions that aid comprehension and tactile feedback (color, opacity); remove position, displacement, and scaling changes."

```css
/* codelens-web/src/main/resources/web/style.css:22105 — current */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
  .toast-item, .modal-dialog, .popover, .dropdown-menu, .modal-backdrop, .sonner-toast {
    transition: opacity 160ms ease !important;
    transform: none !important;
    animation: none !important;
  }
}
```

## Target

Refactor the `@media (prefers-reduced-motion: reduce)` block so that:
1. Long and continuous looping animations (`pulse`, `spin`, `radarPulse`, `shimmer`, `conduitFlow`) are paused or zeroed.
2. Transform-based movements (`scale`, `translate`, `rotate`) are neutralized (`transform: none !important;`).
3. Tactile and state transitions (`opacity`, `background-color`, `border-color`, `color`, `box-shadow`) retain gentle, non-disorienting easing (~160ms).

```css
/* target */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    scroll-behavior: auto !important;
  }
  
  /* Preserve non-spatial tactile feedback */
  a, button, .btn, input, textarea, select, .tab, .stat-pill, .cmdk-item {
    transition-property: background-color, border-color, color, box-shadow, opacity !important;
    transition-duration: 140ms !important;
    transform: none !important;
  }

  .modal-dialog, .popover, .dropdown-menu, .methods-archetypes-popover, 
  .scan-summary-popover, .archetype-multiselect-panel, .sonner-toast {
    transition: opacity 160ms ease !important;
    transform: none !important;
    animation: none !important;
  }
}
```

## Repo conventions to follow

- Accessibility standards reside in the "Global Accessibility & Input Capability Gating" section at `style.css:22100`.
- The app uses `--dur-fast: 140ms` and `160ms` for gentle opacity fades under reduced motion.

## Steps

1. In `codelens-web/src/main/resources/web/style.css`, locate `@media (prefers-reduced-motion: reduce)` around line 22105.
2. Remove `transition-duration: 0.01ms !important;` from the universal wildcard selector `*, *::before, *::after`.
3. Add the explicit override preserving color/opacity transitions on pressable/interactive elements while forcing `transform: none !important;`.
4. Ensure popovers, dropdowns, and modals retain a gentle 160ms opacity transition without transform movement.

## Boundaries

- Do NOT alter standard (non-reduced-motion) animation definitions.
- Do NOT alter `@media (hover: hover)` rules on lines 22122-22128.

## Verification

- **Mechanical**: Inspect CSS syntax.
- **Feel check**:
  - Open Chrome DevTools -> Rendering -> Emulate CSS media feature `prefers-reduced-motion: reduce`.
  - Hover over header buttons, tabs, and list items: confirm background color transitions remain smooth (~140ms) rather than jarringly snapping.
  - Open a modal or toast: confirm it fades in cleanly with opacity without sliding or scaling across the viewport.
- **Done when**: Tactile feedback is preserved while all spatial/displacement transforms are neutralized under reduced motion.

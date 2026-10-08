# 008 — Shorten Archetype Card Flash and Make Theme-Safe

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: LOW
- **Category**: Cohesion & theme
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~15 lines

## Problem

In `style.css:9765-9778`:
```css
.archetype-card.card-updated-flash {
  animation: archetypeCardFlash 1.8s ease-out;
}

@keyframes archetypeCardFlash {
  0% {
    background: rgba(16, 185, 129, 0.3) !important;
    box-shadow: 0 0 0 2px #10b981 !important;
  }
  100% {
    background: #080c14;
    box-shadow: none;
  }
}
```
1. **Excessive duration**: 1.8 seconds is 6x the 300ms UI animation budget. Flash animations for updated records should provide quick confirmation (~400ms) without stalling the user's attention.
2. **Hardcoded dark background**: At `100%`, it forces `background: #080c14;`. If the user is on Light or Swiss theme, the card flashes into a pitch-black box and remains black or creates a stark visual glitch.

## Target

1. Reduce duration to `400ms var(--ease-out)`.
2. Do not hardcode `#080c14` at `100%`. Transition back to `var(--bg-surface)` or remove the `100%` background override entirely so the card cleanly reverts to its theme-governed background.

```css
/* target */
.archetype-card.card-updated-flash {
  animation: archetypeCardFlash 400ms var(--ease-out);
}

@keyframes archetypeCardFlash {
  0% {
    background: rgba(16, 185, 129, 0.25) !important;
    box-shadow: 0 0 0 2px var(--emerald, #10b981) !important;
  }
  100% {
    background: var(--bg-surface);
    box-shadow: none;
  }
}
```

## Repo conventions to follow

- `--bg-surface` is the adaptive theme background token defined for dark, light, and Swiss themes.
- `--ease-out` token is defined in `:root`.

## Steps

1. In `codelens-web/src/main/resources/web/style.css` around line 9765:
2. Update `.archetype-card.card-updated-flash` to use `400ms var(--ease-out)`.
3. In `@keyframes archetypeCardFlash`, replace `#080c14` with `var(--bg-surface)`.

## Boundaries

- Do NOT modify the rule editor cards layout or input bindings.

## Verification

- **Mechanical**: Inspect CSS syntax.
- **Feel check**:
  - In Settings -> Archetype Rules, edit or save a rule.
  - In dark mode, Swiss mode, and light mode: verify the card highlights with a subtle green pulse and settles back into its normal card surface color in 400ms without turning pitch black.
- **Done when**: Archetype card flash completes in 400ms and preserves theme backgrounds.

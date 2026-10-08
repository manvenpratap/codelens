# 009 — Consolidate Ad-Hoc Cubic-Beziers to Shared Motion Tokens

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: LOW
- **Category**: Cohesion & tokens
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~20 lines

## Problem

Throughout `style.css`, multiple interactive components use hardcoded bezier strings that duplicate the design token `--ease-out`:
- `cubic-bezier(0.16, 1, 0.3, 1)` is typed by hand over 10 times (e.g. lines 6103, 6268, 6664, 7083, 7173, 9391, 12000, 20921).
- `0.18s` and `0.22s` are typed by hand instead of using duration tokens `--dur-fast: 140ms;`, `--dur-dropdown: 180ms;`, and `--dur-std: 220ms;`.

This fragments the animation language and makes global timing calibrations impossible.

## Target

Replace instances of `cubic-bezier(0.16, 1, 0.3, 1)` with `var(--ease-out)`, and map ad-hoc durations to the standard `--dur-*` tokens.

```css
/* target examples */
.scan-metric-tile.metric-updating {
  animation: metricTilePop var(--dur-dropdown) var(--ease-out);
}

.hub-explorer-overlay {
  animation: hubFadeIn var(--dur-std) var(--ease-out);
}

.critical-path-dock {
  animation: nodeCardEnter var(--dur-std) var(--ease-out);
  transition: all var(--dur-std) var(--ease-out);
}
```

## Repo conventions to follow

- Existing motion tokens in `:root` (`style.css:96-118`):
  ```css
  --ease-out: cubic-bezier(0.23, 1, 0.32, 1);
  --dur-fast: 140ms;
  --dur-dropdown: 180ms;
  --dur-std: 220ms;
  --dur-modal: 260ms;
  ```

## Steps

1. In `codelens-web/src/main/resources/web/style.css`:
   - Line 6103: Replace `0.28s cubic-bezier(0.16, 1, 0.3, 1)` with `var(--dur-modal) var(--ease-out)`.
   - Line 6664: Replace `0.18s cubic-bezier(0.16, 1, 0.3, 1)` with `var(--dur-dropdown) var(--ease-out)`.
   - Line 12000: Replace `0.22s cubic-bezier(0.16, 1, 0.3, 1)` with `var(--dur-std) var(--ease-out)`.
   - Line 20921: Replace `0.2s cubic-bezier(0.16, 1, 0.3, 1)` with `var(--dur-std) var(--ease-out)`.

## Boundaries

- Do NOT touch third-party libraries or WebGL animation loops.
- Do NOT alter keyframe definitions that need custom timing steps (e.g. pulse dots).

## Verification

- **Mechanical**: Inspect CSS syntax.
- **Feel check**:
  - Open the Process Hub, Hub Explorer, and scan modal.
  - Verify consistent acceleration and deceleration curves across all dialogs.
- **Done when**: Hand-typed `cubic-bezier(0.16, 1, 0.3, 1)` strings are replaced with canonical tokens.

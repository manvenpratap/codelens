# 001 — Remove Command Palette Entrance Animation for Zero Latency

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: HIGH
- **Category**: Purpose & frequency
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~5 lines

## Problem

The Command Palette (`#command-palette-modal`), invoked via `⌘K` or `Ctrl+K`, plays a 260ms scale and translate pop animation (`modalPop`) every time it is toggled. Because this is a high-frequency keyboard action triggered dozens or hundreds of times a day, any animation introduces noticeable friction and makes the app feel sluggish and disconnected from keystrokes. Raycast and cmdk have no entrance animation.

```css
/* codelens-web/src/main/resources/web/style.css:22155 — current */
.cmdk-dialog {
  width: 90%;
  max-width: 620px;
  background: #0f141c;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: var(--radius-md, 12px);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.8), 0 0 0 1px rgba(255, 255, 255, 0.08);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  animation: modalPop var(--dur-modal) var(--ease-out);
}
```

## Target

The Command Palette must render instantly upon pressing `⌘K` or `Ctrl+K`. The modal backdrop and dialog appear with zero animation latency (`animation: none;`), matching Raycast's instantaneous standard for 100+/day keyboard navigation.

```css
/* target */
.cmdk-dialog {
  width: 90%;
  max-width: 620px;
  background: #0f141c;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: var(--radius-md, 12px);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.8), 0 0 0 1px rgba(255, 255, 255, 0.08);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  animation: none;
}
```

## Repo conventions to follow

- Duration scale tokens exist in `:root` (`style.css:111-117`), including `--dur-instant: 0ms;` for keyboard shortcuts.
- Modals have their backdrop and dialog styled in `style.css`.
- The command palette backdrop `#command-palette-modal` has `display: none;` and toggles `.open { display: flex; }`.

## Steps

1. In `codelens-web/src/main/resources/web/style.css`, locate `.cmdk-dialog` around line 22155.
2. Change `animation: modalPop var(--dur-modal) var(--ease-out);` to `animation: none;`.

## Boundaries

- Do NOT touch the list items (`.cmdk-item`) or search input (`.cmdk-input`).
- Do NOT remove or modify other modal styles (`.modal-dialog`, `.help-dialog`, etc.).
- Do NOT touch JavaScript logic in `app.js`.

## Verification

- **Mechanical**: Verify CSS syntax: no stray commas or unclosed braces.
- **Feel check**:
  - Open the CodeLens UI in the browser.
  - Press `⌘K` (or `Ctrl+K`) repeatedly. Confirm the command menu appears immediately under the cursor with zero perceived delay or scale animation.
  - Press `Escape` and reopen with `⌘K`. Confirm opening feels like a native tool palette (instantaneous).
- **Done when**: `.cmdk-dialog` has `animation: none` and opens with zero frames of scaling latency.

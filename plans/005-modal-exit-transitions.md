# 005 — Implement Coordinated Exit Transitions on Modals

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: MEDIUM
- **Category**: Interruptibility & timing
- **Estimated scope**: 2 files (`codelens-web/src/main/resources/web/style.css`, `codelens-web/src/main/resources/web/app.js`), ~35 lines

## Problem

When modals (`#help-modal`, `#settings-modal`, `#process-hub-modal`, `#export-modal`, etc.) are closed, callers in `app.js` immediately execute:
```javascript
modal.classList.remove('open');
modal.setAttribute('aria-hidden', 'true');
```
In `style.css:7839-7860`:
```css
.modal-backdrop {
  display: none;
  opacity: 0;
  transition: opacity var(--dur-modal) var(--ease-out);
}
.modal-backdrop.open {
  display: flex;
  opacity: 1;
}
```
Because removing `.open` instantly sets `display: none`, the browser cannot interpolate the transition. The modal vanishes in 0ms (an abrupt cut), completely contrasting with the 260ms `modalPop` entrance animation.

## Target

Introduce an asymmetric, fast exit transition (140ms, `--dur-fast`) when closing modals:
1. Define a `.modal-closing` state in CSS:
   ```css
   .modal-backdrop.modal-closing {
     opacity: 0;
     pointer-events: none;
     transition: opacity var(--dur-fast) var(--ease-out);
   }
   .modal-backdrop.modal-closing .modal-dialog {
     transform: scale(0.97) translateY(4px);
     opacity: 0;
     transition: transform var(--dur-fast) var(--ease-out), opacity var(--dur-fast) var(--ease-out);
   }
   ```
2. In `app.js`, wrap modal closure in a helper `closeModalWithTransition(modal, onClosed)` that adds `.modal-closing`, waits 140ms (`--dur-fast`), then removes `.open` and cleans up classes.

## Repo conventions to follow

- Duration scale `--dur-fast: 140ms;` and `--dur-modal: 260ms;`.
- Asymmetric timing rule: systems animate deliberate entrances at ~260ms, but dismissals must snap quickly (~140ms).
- Existing modals share the `.modal-backdrop` and `.modal-dialog` class structure.

## Steps

1. In `codelens-web/src/main/resources/web/style.css` around line 7860:
   Add `.modal-backdrop.modal-closing` and `.modal-backdrop.modal-closing .modal-dialog` styles.
2. In `codelens-web/src/main/resources/web/app.js`:
   Add a shared helper:
   ```javascript
   function dismissModalAnimated(modalEl) {
     if (!modalEl || !modalEl.classList.contains('open')) return;
     modalEl.classList.add('modal-closing');
     setTimeout(() => {
       modalEl.classList.remove('open', 'modal-closing');
       modalEl.setAttribute('aria-hidden', 'true');
     }, 140);
   }
   ```
3. Update modal close triggers (`closeSettings()`, `closeHelpModal()`, `closeProcessHub()`, `ExportHub.close()`, `closeScopeManager()`) to use `dismissModalAnimated(modal)`.

## Boundaries

- Do NOT alter modal inner layouts, scroll states, or form inputs.
- Do NOT block keyboard Escape handling (ensure pressing Escape immediately triggers the animated dismissal).

## Verification

- **Mechanical**: Verify all modal close buttons and Escape key handlers cleanly invoke dismissal without leaving dangling classes.
- **Feel check**:
  - Open Settings modal (`⌘,` or button). Click Close or press Escape.
  - Observe that the modal scales down slightly (`scale(0.97)`) and fades out cleanly over 140ms instead of disappearing instantaneously.
- **Done when**: All standard modals exit smoothly with 140ms asymmetric dismissal.

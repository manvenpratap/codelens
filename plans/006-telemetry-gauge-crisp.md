# 006 — Fix Rebounding Spring on Quantitative Telemetry Bar

- **Status**: DONE
- **Commit**: e99ce11
- **Severity**: MEDIUM
- **Category**: Performance & easing
- **Estimated scope**: 1 file (`codelens-web/src/main/resources/web/style.css`), ~6 lines

## Problem

In `style.css:18669`:
```css
.hub-jvm-gauge-bar {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #10b981, #f59e0b, #f43f5e);
  transition: width 400ms var(--ease-spring);
}
```
The Process Manager JVM memory gauge animates changes in layout `width` using an oscillating spring (`cubic-bezier(0.175, 0.885, 0.32, 1.275)`) over 400ms. Quantitative telemetry meters represent precise physical values (heap usage, CPU %). Applying a bouncy spring causes the meter to visibly overshoot actual telemetry (e.g., bouncing past 100% or trembling during polling intervals), and animating `width` over 400ms causes continuous layout recalculations during server polling.

## Target

Replace the spring bounce and 400ms duration with a crisp, sub-300ms linear or standard ease-out transition.

```css
/* target */
.hub-jvm-gauge-bar {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #10b981, #f59e0b, #f43f5e);
  transition: width var(--dur-std) var(--ease-out);
}
```

## Repo conventions to follow

- Duration token `--dur-std: 220ms;` in `:root`.
- Token `--ease-out: cubic-bezier(0.23, 1, 0.32, 1);` in `:root`.
- Progress bars and telemetry gauges in CodeLens use linear or `ease-out`.

## Steps

1. In `codelens-web/src/main/resources/web/style.css` at line 18669:
2. Change `transition: width 400ms var(--ease-spring);` to `transition: width var(--dur-std) var(--ease-out);`.

## Boundaries

- Do NOT change the gradient background colors or container sizing.
- Do NOT modify the polling interval in JavaScript.

## Verification

- **Mechanical**: Inspect CSS syntax.
- **Feel check**:
  - Open the Process Manager Hub modal (`⌘P` or top header icon) -> Server & JVM tab.
  - Watch the JVM memory bar update during live refresh.
  - Confirm the bar moves smoothly and settles crisply at the exact percentage without bouncing or overshooting.
- **Done when**: `.hub-jvm-gauge-bar` transitions crisply at 220ms ease-out without physical spring rebound.

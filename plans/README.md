# Animation & Motion Improvement Plans

Audited against Emil Kowalski's design engineering standards ([animations.dev](https://animations.dev/)).

## Plan Roadmap & Status

| Plan | Title | Severity | Category | Status |
| :--- | :--- | :--- | :--- | :--- |
| [**001**](001-cmdk-zero-latency.md) | Remove Command Palette Entrance Animation for Zero Latency | **HIGH** | Purpose & frequency | DONE |
| [**002**](002-fadeindown-keyframes.md) | Define Missing fadeInDown Keyframe Animation | **HIGH** | Cohesion & tokens | DONE |
| [**003**](003-reduced-motion-tactile.md) | Scope Reduced-Motion Reset to Retain Interactive Feedback | **HIGH** | Accessibility | DONE |
| [**004**](004-popover-origin-physicality.md) | Correct Popovers & Dropdown Menus Transform Origins and Direction | **MEDIUM** | Physicality & origin | DONE |
| [**005**](005-modal-exit-transitions.md) | Implement Coordinated Exit Transitions on Modals | **MEDIUM** | Interruptibility & timing | DONE |
| [**006**](006-telemetry-gauge-crisp.md) | Fix Rebounding Spring on Quantitative Telemetry Bar | **MEDIUM** | Performance & easing | DONE |
| [**007**](007-constrain-transition-all.md) | Constrain Unbounded `transition: all` Across Interactive UI Components | **MEDIUM** | Performance | DONE |
| [**008**](008-archetype-card-flash.md) | Shorten Archetype Card Flash and Make Theme-Safe | **LOW** | Cohesion & theme | DONE |
| [**009**](009-consolidate-cubic-beziers.md) | Consolidate Ad-Hoc Cubic-Beziers to Shared Motion Tokens | **LOW** | Cohesion & tokens | DONE |

## Recommended Execution Order

Execute sequentially from highest leverage to lowest:
1. **001**: Instant high-frequency keyboard responsiveness (`⌘K`).
2. **002**: Unbreaks silent animation failures for dropdowns & forms.
3. **003**: Fixes accessibility tactile feedback degradation.
4. **004**: Eliminates floating center popover glitches.
5. **005**: Eliminates abrupt 0ms modal disappearance.
6. **006**: Resolves spring overshoot on telemetry gauges.
7. **007**: Eliminates layout recalculations from `transition: all`.
8. **008**: Fixes card flash duration and dark theme hardcode.
9. **009**: Unifies token design vocabulary.

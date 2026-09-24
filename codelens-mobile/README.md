# CodeLens Mobile Companion (Expo & React Native)

Built according to Emil Kowalski's **animate-expo** motion engineering principles:
- **Two Runtimes Separation**: All gesture dynamics executed on the UI thread worklet runtime via Reanimated 4 (`'worklet'`).
- **No Approximate Values**: Spring curves configured via designer parameters `{ duration: 300, dampingRatio: 0.8, velocity }`.
- **Interruptible Velocity Handoff**: `Gesture.Pan()` passes touch release velocity seamlessly into `withSpring`.
- **Touch Not Hover**: 44pt minimum touch boundaries with immediate `scale: 0.97` press down feedback under 150ms.
- **Acoustic & Tactile Sync**: `Haptics.impactAsync(Light)` fired on the exact causal frame of user engagement.
- **Accessibility & Reduced Motion**: Automatically honors system-wide `useReducedMotion()`.

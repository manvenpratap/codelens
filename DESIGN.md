---
name: CodeLens Design System
description: Precision Industrial Developer Workbench for Java Codebase Intelligence
colors:
  primary: "#2563eb"
  primary-hover: "#1d4ed8"
  primary-active: "#1e40af"
  primary-subtle: "rgba(37, 99, 235, 0.14)"
  primary-glow: "rgba(37, 99, 235, 0.28)"
  primary-border: "rgba(59, 130, 246, 0.40)"
  
  cyan: "#0284c7"
  cyan-bright: "#38bdf8"
  cyan-subtle: "rgba(2, 132, 199, 0.14)"
  cyan-glow: "rgba(56, 189, 248, 0.25)"
  
  emerald: "#10b981"
  emerald-dim: "#059669"
  emerald-subtle: "rgba(16, 185, 129, 0.14)"
  
  amber: "#f59e0b"
  amber-dim: "#d97706"
  amber-subtle: "rgba(245, 158, 11, 0.14)"
  
  red: "#ef4444"
  red-dim: "#dc2626"
  red-subtle: "rgba(239, 68, 68, 0.14)"
  red-light: "#f87171"
  rose: "#fb7185"
  rose-subtle: "rgba(244, 63, 94, 0.15)"
  rose-subtle-2: "rgba(244, 63, 94, 0.12)"
  rose-subtle-3: "rgba(244, 63, 94, 0.22)"
  teal: "#0d9488"
  purple: "#a855f7"
  purple-light: "#c084fc"
  purple-subtle: "rgba(168, 85, 247, 0.2)"
  purple-border: "rgba(168, 85, 247, 0.4)"
  pink: "#ec4899"
  pink-light: "#f472b6"
  pink-subtle: "rgba(236, 72, 153, 0.2)"
  pink-border: "rgba(236, 72, 153, 0.4)"
  blue-light: "#60a5fa"
  amber-light: "#fbbf24"
  emerald-light: "#34d399"
  dark-code: "#1e1e1e"
  overlay-dark: "rgba(14, 19, 32, 0.4)"
  overlay-mid: "rgba(14, 19, 32, 0.6)"
  overlay-deep: "rgba(4, 6, 12, 0.85)"
  
  neutral-bg-dark: "#0d1117"
  neutral-panel-dark: "#161b22"
  neutral-surface-dark: "#1c2128"
  neutral-elevated-dark: "#21262d"
  neutral-text-primary-dark: "#f8fafc"
  neutral-text-secondary-dark: "#94a3b8"
  neutral-text-muted-dark: "#64748b"
  neutral-border-dark: "rgba(255, 255, 255, 0.08)"
  
  neutral-bg-light: "#f8fafc"
  neutral-panel-light: "#ffffff"
  neutral-surface-light: "#f1f5f9"
  neutral-elevated-light: "#e2e8f0"
  neutral-text-primary-light: "#0f172a"
  neutral-text-secondary-light: "#334155"
  neutral-text-muted-light: "#64748b"
  neutral-border-light: "rgba(0, 0, 0, 0.12)"
  slate-light: "#cbd5e1"
  slate-dark: "#1e293b"
  slate-mid: "#475569"
  sky-deep: "#0369a1"

typography:
  display-hero:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "40px"
    fontWeight: 700
    lineHeight: 1.1
  display-headline:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "32px"
    fontWeight: 700
    lineHeight: 1.15
  display-xl:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.2
  title-lg:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1.25
  display-lg:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.2
  title-md:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.3
  title-sm:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.3
  display:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Space Grotesk, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.3
  ui:
    fontFamily: "Plus Jakarta Sans, system-ui, -apple-system, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "-0.01em"
  mono:
    fontFamily: "JetBrains Mono, Menlo, Consolas, monospace"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: "0"
  caption:
    fontFamily: "Plus Jakarta Sans, system-ui, sans-serif"
    fontSize: "11px"
    fontWeight: 500
    lineHeight: 1.4
  caption-mono:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "10.5px"
    fontWeight: 500
    lineHeight: 1.4
  badge:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "10px"
    fontWeight: 600
    lineHeight: 1.2
  micro:
    fontFamily: "Plus Jakarta Sans, system-ui, sans-serif"
    fontSize: "9px"
    fontWeight: 700
    lineHeight: 1.2
  micro-mono:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "9.5px"
    fontWeight: 700
    lineHeight: 1.2

rounded:
  xxs: "2px"
  subtle: "3px"
  xs: "4px"
  sm-5: "5px"
  sm: "6px"
  md: "10px"
  md-12: "12px"
  lg: "16px"
  xl: "20px"
  full: "9999px"

spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"

components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.neutral-text-primary-dark}"
    rounded: "{rounded.sm}"
    padding: "6px 14px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
---

# CodeLens Design System

<!-- impeccable:design-schema 1 -->

## Overview

CodeLens is designed as a **precision industrial developer workbench** tailored for high information density, deep architectural discovery, and distraction-free code exploration.

The interface prioritizes **clarity over decoration**, combining tactile micro-interactions with rich canvas visualizers (Force-directed graphs, DSM grids, Treemaps, Chord diagrams, and Sunburst radial hierarchies).

---

## Colors

The palette uses a crisp **Raycast/Linear-inspired cobalt blue** (`#2563eb`) paired with purposeful semantic accents and a neutral charcoal/graphite substrate.

### Primary Accents
- **Cobalt Blue (`#2563eb`)**: Primary interactive states, active tab highlights, and primary CTA buttons.
- **Cyan (`#38bdf8`)**: Symbol references, method labels, link hover highlights, and Lucene search matches.
- **Amber (`#f59e0b`)**: Code review warnings, blast radius warnings, and medium-severity smells.
- **Emerald (`#10b981`)**: Success states, passing reviews, clean builds, and safe complexity thresholds.
- **Crimson (`#ef4444`)**: Critical findings, cyclic dependencies, high cyclomatic complexity, and error toasts.

### Dark Theme (Default)
- Substrate / Base: `#0d1117` with subtle `32px` engineering grid lines (`rgba(255,255,255,0.015)`).
- Panels & Navbars: `#161b22` with `1px solid rgba(255,255,255,0.08)`.
- Hover Surfaces: `#1c2128`.
- Elevated HUDs / Modals: `#21262d` with `rgba(22, 27, 34, 0.85)` backdrop blur.

### Light Theme
- Substrate / Base: `#f8fafc` with subtle engineering grid lines.
- Panels & Navbars: `#ffffff` with `1px solid rgba(0,0,0,0.12)`.
- Hover Surfaces: `#f1f5f9`.
- High-contrast text: Primary `#0f172a`, Secondary `#334155`, Muted `#64748b`.

---

## Typography

CodeLens uses a tailored three-tier typographic stack:

1. **Brand & Section Headers (`--font-display`)**: `Space Grotesk, sans-serif` (weight `600`/`700`, tight letter spacing `-0.02em`, balance text-wrap).
2. **UI & Data Telemetry (`--font-ui`)**: `Plus Jakarta Sans, system-ui, sans-serif` (weight `400`/`500`/`600`).
3. **Source Code & AST Signatures (`--font-mono`)**: `JetBrains Mono, Menlo, Consolas, monospace` with `font-variant-numeric: tabular-nums` for aligned line numbers, complexity metrics, and token badges.

---

## Layout

- **Three-Panel Grid with Header**:
  - Header: Fixed `56px` top bar with quick project pill, search bar, active view switcher, settings, and export.
  - Left Panel (`310px` default, resizable): Multi-module package explorer tree, quick search filters, and member list.
  - Center Panel (`1fr` flexible): Primary visualization viewport hosting the ForceGraph canvas, DSM matrix, Treemap, Sunburst, or Monaco Source editor.
  - Right Panel (`370px` default, resizable): Detail Inspector, Knowledge Base member list, AST Review findings, and Git blame/history.
  - Footer: `24px` persistent status bar with server port, active DB metrics, and keyboard shortcut helpers.

---

## Elevation & Depth

- **Zero Heavy Shadows**: Depth is communicated via **border luminosity** and **tonal background stepping** rather than blurry drop shadows.
- **Glassmorphic Floating HUDs**: Canvas overlays (Depth pills, Cluster toggles, POJO filters, Minimaps) use `backdrop-filter: blur(12px)` with subtle `rgba(255,255,255,0.08)` borders.
- **Z-Index Scale**:
  - Base: `1`
  - Elevated: `10`
  - Headers & Resizers: `40` / `50`
  - Canvas Floating HUDs: `60`
  - Modals / Drawers: `100`
  - Tooltips: `200`

---

## Shapes

- **Corner Radii**:
  - Controls & Pills: `6px` (`--radius-sm`)
  - Panels & Cards: `10px` (`--radius-md`)
  - Modals & Hero Cards: `16px` (`--radius-lg`)
  - Badges & Chips: `9999px` (`--radius-full`)
- **Visual Rhythm**: Strict `4px`/`8px`/`12px`/`16px` spacing cadence throughout all sidebar trees and inspector rows.

---

## Components

- **Graph HUD Toolbar**: Floating glass bar at top center of canvas with Depth selector (`1`, `2`, `3`, `5`, `Max`), Cluster toggles, POJO filter (`⚡ Hide POJOs`), Physics switch, and Camera controls.
- **Explorer Tree**: Hierarchical folder & package trees with distinct icons for packages (`📦`), classes (`ⓒ`), interfaces (`ⓘ`), enums (`ⓔ`), and records (`®`).
- **Code Review Finding Cards**: Severity-coded badges with line anchors that jump directly to Monaco editor with highlight animations.
- **Inspector Key-Value Rows**: Label on left in muted typography, monospaced tabular values on right with copyable pill chips.

---

## Do's and Don'ts

### Do's
- **Preserve Tabular Numbers**: Always use `font-variant-numeric: tabular-nums` for counts, line numbers, complexities, and percentages.
- **Support Keyboard Navigation**: Ensure shortcuts (`1-5`, `F`, `H`, `Space`, `/`) remain functional for instant developer speed.
- **Maintain High Density**: Keep padding tight and avoid oversized whitespace gaps in data grids and member lists.
- **Filter Noise Intelligently**: Keep POJO getters/setters and boilerplate accessors filtered by default in call graphs to emphasize business flow.

### Don'ts
- **No Heavy Drop Shadows**: Avoid heavy blurry box-shadows; use crisp borders and tonal layer stepping instead.
- **No Generic System Defaults**: Never fallback to default serif/sans browser fonts or unstyled scrollbars.
- **No Disruptive Popups**: Never interrupt user exploration with blocking popups; use discreet HUD status indicators and non-blocking toast banners.

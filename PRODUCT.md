# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primary users are Java software engineers, tech leads, architects, and code reviewers analyzing, refactoring, or onboarding onto large, complex, or legacy Java codebases. They need immediate visibility into call hierarchies, data mutation impact, code quality risks, and architecture topology without sending proprietary source code to third-party cloud services.

## Product Purpose

CodeLens exists to provide instant, offline, deep code intelligence for Java projects. It parses source trees into ASTs, indexes code symbols in local Lucene/H2 stores, and presents an interactive developer workbench for exploring call graphs, field propagation, automated code reviews, Git churn heatmaps, and structural architecture matrices.

## Positioning

A zero-cloud, 100% local-first Java codebase intelligence engine. Unlike cloud SaaS scanners that require code uploads and batch queueing, CodeLens operates entirely on the engineer's machine with sub-second queries, interactive ForceGraph canvas simulations, and live visual exploration tools.

## Operating Context

- **Local Development & Review Sessions**: Running alongside IDEs (IntelliJ IDEA, Eclipse, VS Code) as a specialized architectural exploration companion.
- **Legacy Refactoring & Blast Radius Analysis**: Tracing method callers, callees, and field read/write dependencies before making high-risk refactors.
- **Onboarding & Architectural Discovery**: Exploring multi-module enterprise codebases using high-level module graphs, package constellations, DSM grids, Treemaps, and Sunburst radial diagrams.
- **Audits & Metrics Export**: Generating offline interactive HTML/JSON reports and inventory metrics for technical debt reviews.

## Capabilities and Constraints

- **Multi-Module Java Support**: Java 8 through 21+ syntax including Records, sealed classes, pattern matching, compact constructors, and non-standard/PascalCase packaging.
- **Dual Visual Themes**: Highly calibrated Dark Theme (slate/carbon high-contrast workbench) and Light Theme (crisp paper/clean enterprise view).
- **Interactive Visualizers**:
  - Force-directed Call Hierarchy & Community Constellations (`ForceGraph.js` on HTML5 Canvas).
  - Dependency Structure Matrix (DSM).
  - Hierarchical Treemaps (Complexity & LOC).
  - Inter-class Call Chord Diagrams.
  - Radial Package/Class Sunburst Hierarchies.
  - POJO & Accessor noise-filtering HUD toggles.
- **Embedded Architecture**: Javalin HTTP/WebSocket server, embedded H2 database, Lucene 9 search engine, JavaParser AST scanner, and JGit repository inspector.

## Brand Commitments

- **Tone & Voice**: Precise, technical, robust, developer-first, and distraction-free.
- **Identity**: "CodeLens — Java Codebase Intelligence", dark carbon/slate base with cyan (`#38bdf8`) / amber (`#f59e0b`) accents, semantic status colors, and crisp monospace code typography.

## Evidence on Hand

- Fully working multi-module Java application in [`/Volumes/Study/Projects/codelens`](file:///Volumes/Study/Projects/codelens).
- Pre-built rich sample project in [`/Volumes/Study/Projects/codelens/sample-project`](file:///Volumes/Study/Projects/codelens/sample-project).
- Live local web UI served on [`http://localhost:7878`](http://localhost:7878).

## Product Principles

1. **Local & Private First**: All indexing, parsing, querying, and rendering occurs 100% locally with zero cloud dependencies or telemetry leaks.
2. **High Information Density**: Dense, scannable layouts with instant search, deep filtering, and zero wasted screen real estate.
3. **Signal Over Noise**: Intelligently filter boilerplate (POJOs, accessors) while elevating actual business logic, cyclomatic hotspots, and architectural dependencies.
4. **Instant Interactivity**: Sub-second UI response times, smooth canvas physics, and keyboard shortcuts (`1-5`, `F`, `H`, `Space`).

## Accessibility & Inclusion

- High contrast text and icon ratios across both Dark and Light modes.
- Full keyboard navigation for tabs, explorer trees, search palettes, and graph camera controls.
- Accessible semantic ARIA roles and labels on all HUD overlays, modals, and data tables.

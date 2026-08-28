# CodeLens E2E & Visual Regression Test Suite

An automated End-to-End testing and visual overlap regression suite powered by Playwright and Pytest.

## Overview
- **Visual Overlap Detection**: Uses computational geometry (`getBoundingClientRect()`) to check for intersection collisions between all headers, navigation tabs, floating HUDs, toolbars, and modals across 3 standard viewports (`1920x1080`, `1440x900`, `1280x800`).
- **All 7 Visualizers**: Tests rendering and interaction across all 7 codebase visualizers in both **Classes** and **Methods** modes:
  1. 3D Software City (`city3d`)
  2. 3D Force Galaxy (`galaxy3d`)
  3. 2D Blooming Tree Graph (`graph2d`)
  4. Zoomable Treemap (`treemap`)
  5. Sunburst Radial Hierarchy (`sunburst`)
  6. Dependency Structure Matrix (`dsm`)
  7. Chord Diagram (`chord`)
- **Navigation & Modals**: Tests primary tabs (Graph, Knowledge Base, Codebase Viz, Review, Git, Source) and modal flows (Settings, Help, Export Hub).

## Prerequisites
```bash
pip3 install -r e2e-tests/requirements.txt
playwright install chromium
```

## Running the Suite

### 1. Run Automated Pytest Regression Tests
```bash
pytest e2e-tests/ -v
```

### 2. Run Visual Audit & Capture Full Screenshots
```bash
python3 e2e-tests/run_audit.py
```
Screenshots will be stored in `e2e-tests/screenshots/`.

---
trigger: always_on
description: Mandatory project invariants - Git check-ins, Graphify, Archify, GitHub Wikis, and README maintenance.
---

## Mandatory Project Invariants

This project adheres to the workspace engineering standards for documentation, version control, and token optimization.

### Rules:
1. **Mandatory Git Check-ins**: At the conclusion of every feature, bugfix, refactor, or task, verify and commit all changes to git using a concise conventional commit message (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`). Never leave the repository with an uncommitted or dirty working tree.
2. **Mandatory Graphify Token Optimization**: Before reading entire files or answering architecture questions, consult `graphify-out/GRAPH_REPORT.md` or use `graphify query` / `graphify path` to minimize context window bloat. The `.git/hooks/post-commit` hook automatically rebuilds the AST graph upon commit.
3. **Mandatory Archify Documentation**: Maintain the 5 core Archify diagrams (`architecture`, `workflow`, `sequence`, `dataflow`, `lifecycle`) in `docs/diagrams/` with interactive HTML views and JSON specifications.
4. **Mandatory GitHub Wiki Maintenance**: Keep the authoritative `wiki/` documentation up-to-date with native embedded Mermaid charts (0 dead `.html`/`.json` links) and synchronized with GitHub wikis via `sync_wikis.py`.
5. **Mandatory README Upkeep**: Maintain the project's root `README.md` in sync with the latest architecture, feature inventory, compiled diagram links, and quickstart runbooks.

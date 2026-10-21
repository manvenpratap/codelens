---
trigger: always_on
description: Mandatory Graphify consultation and mandatory git check-in rules.
---

## Mandatory Knowledge Base & Git Check-in Rules

This project has an active graphify knowledge graph at `graphify-out/` and automated git lifecycle hooks.

Rules:
1. **Mandatory Graphify Consultation**: Before answering architecture or codebase questions, read `graphify-out/GRAPH_REPORT.md` for god nodes and community structure. Use `graphify query "<question>"`, `graphify path "<A>" "<B>"`, and `graphify explain "<concept>"` instead of reading entire files into context.
2. **Mandatory Git Check-ins**: At the conclusion of every task, bugfix, feature, or refactor, verify and commit all changes to git using a clear conventional commit message (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`). Never leave the repository with an uncommitted or dirty working tree.
3. **Automated Graph Sync**: All git check-ins trigger the `.git/hooks/post-commit` hook to automatically update `graphify-out/`. If editing outside of git, run `graphify update .` to ensure the AST graph remains in parity.

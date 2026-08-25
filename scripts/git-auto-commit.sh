#!/usr/bin/env bash
set -e

# Resolve repository root
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    # Check if there are modified, deleted or untracked changes
    CHANGES=$(git status --porcelain)
    if [ -n "$CHANGES" ]; then
        echo ""
        echo "================================================================="
        echo " [CodeLens Hook] Build successful! Auto-committing changes..."
        echo "================================================================="
        git add -A
        TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
        COMMIT_MSG="${GIT_COMMIT_MSG:-build: auto-commit after successful build ($TIMESTAMP)}"
        git commit -m "$COMMIT_MSG"
        echo " [CodeLens Hook] ✅ Committed changes successfully: $COMMIT_MSG"
        echo "================================================================="
        echo ""
    else
        echo " [CodeLens Hook] ✨ Git working tree is clean. Nothing to commit."
    fi
fi

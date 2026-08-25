#!/usr/bin/env bash
set -e

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.0.2.jdk/Contents/Home}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

echo "🔨 Building CodeLens..."
./mvnw package "$@"

if [ -f "$REPO_ROOT/scripts/git-auto-commit.sh" ]; then
    bash "$REPO_ROOT/scripts/git-auto-commit.sh"
fi

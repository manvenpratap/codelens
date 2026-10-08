#!/usr/bin/env bash
# CodeStory Local & CI PR Change Story Runner
# Usage: ./scripts/codestory-pr.sh [BASE_REF] [HEAD_REF] [FLAGS...]
# Example: ./scripts/codestory-pr.sh main HEAD --markdown --output=pr-story.md

set -euo pipefail

BASE_REF="${1:-HEAD~1}"
HEAD_REF="${2:-HEAD}"
shift 2 2>/dev/null || shift $# 2>/dev/null || true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_FILE="$ROOT_DIR/codelens-app/target/codelens-app.jar"

if [ ! -f "$JAR_FILE" ]; then
  echo "[CodeStory] JAR not found at $JAR_FILE. Building..."
  (cd "$ROOT_DIR" && mvn package -DskipTests -q)
fi

# Ensure storage is initialized if not scanned yet
if [ ! -d "$ROOT_DIR/codelens-data" ]; then
  echo "[CodeStory] Initializing repository scan..."
  java -jar "$JAR_FILE" scan "$ROOT_DIR"
fi

java -jar "$JAR_FILE" pr-story "$BASE_REF" "$HEAD_REF" "$@"

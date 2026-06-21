#!/usr/bin/env bash
# Usage:
#   ./start.sh        — production: single server on http://localhost:8080
#   ./start.sh dev    — dev: backend on :8080 + Vite HMR on :5173
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$SCRIPT_DIR/realtime-codeshare"
JAR="$APP_DIR/target/realtime-codeshare-0.0.1-SNAPSHOT.jar"
FRONTEND_DIR="$APP_DIR/frontend"

build_if_needed() {
  if [[ ! -f "$JAR" ]]; then
    echo "==> JAR not found — building (includes React SPA, takes ~1 min on first run)..."
    cd "$APP_DIR"
    ./mvnw package -DskipTests
    cd "$SCRIPT_DIR"
  fi
}

if [[ "${1:-}" == "dev" ]]; then
  build_if_needed

  BACKEND_PID=""
  FRONTEND_PID=""
  DEV_CONFIG=""

  cleanup() {
    echo ""
    echo "==> Shutting down..."
    [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" 2>/dev/null || true
    [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
    [[ -n "$DEV_CONFIG" ]] && rm -f "$DEV_CONFIG"
  }
  trap cleanup INT TERM

  echo "==> Starting backend on :8080..."
  java -jar "$JAR" &
  BACKEND_PID=$!

  echo "==> Starting Vite dev server on :5173..."
  cd "$FRONTEND_DIR"
  [[ ! -d node_modules ]] && npm ci

  # Write a temporary Vite config that adds ngrok allowedHosts on top of the base config.
  # Deleted in cleanup so it never persists in the repo.
  DEV_CONFIG="$FRONTEND_DIR/vite.config.dev.ts"
  cat > "$DEV_CONFIG" << 'VITE_EOF'
/// <reference types="vitest/config" />
import { defineConfig, mergeConfig } from "vitest/config";
import base from "./vite.config";

export default mergeConfig(base, defineConfig({
  server: {
    allowedHosts: [
      ".ngrok-free.app",
      ".ngrok-free.dev",
      ".ngrok.app",
      ".ngrok.dev",
      ".ngrok.io",
    ],
  },
}));
VITE_EOF

  node_modules/.bin/vite --config vite.config.dev.ts &
  FRONTEND_PID=$!

  echo ""
  echo "  Dev URL:     http://localhost:5173  (React HMR enabled)"
  echo "  Backend URL: http://localhost:8080"
  echo ""
  echo "  Press Ctrl+C to stop both servers."

  wait

else
  build_if_needed

  echo ""
  echo "==> App running at http://localhost:8080"
  echo "    Press Ctrl+C to stop."
  echo ""
  java -jar "$JAR"
fi

#!/usr/bin/env bash
# run-client.sh — Avvia il client Connections

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

JAR="dist/connections-client.jar"

if [ ! -f "$JAR" ]; then
    echo "❌ JAR non trovato: $JAR"
    echo "   Esegui prima: ./build.sh"
    exit 1
fi

echo "🎮 Starting Connections Client..."
echo "   JAR: $JAR"
echo ""

java -jar "$JAR"

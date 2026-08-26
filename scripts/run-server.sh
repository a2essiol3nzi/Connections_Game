#!/usr/bin/env bash
# run-server.sh — Avvia il server Connections

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

JAR="dist/connections-server.jar"

if [ ! -f "$JAR" ]; then
    echo "❌ JAR non trovato: $JAR"
    echo "   Esegui prima: ./build.sh"
    exit 1
fi

echo "🚀 Starting Connections Server..."
echo "   JAR: $JAR"
echo ""

java -jar "$JAR"

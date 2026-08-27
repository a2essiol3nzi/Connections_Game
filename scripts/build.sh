#!/usr/bin/env bash
# build.sh — Script di build con verifica e output leggibile

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"/.. # usciamo dalla dir di scripts

echo "================================"
echo "🔨 Building Connections Game"
echo "================================"
echo "Project: $PROJECT_ROOT"
echo ""

# Esegui make
if make clean > /dev/null 2>&1; then
    echo "✓ Cleaned previous build"
fi

echo "Compiling..."
if make > /dev/null 2>&1; then
    echo "✅ Build successful!"
    echo ""
    echo "Output directory: ./out/"
    find ./out -name "*.class" | wc -l | xargs echo "Classes compiled:"
else
    echo "❌ Build failed!"
    echo "Running verbose compile..."
    make
    exit 1
fi

echo ""
echo "Packaging JARs..."
if make jar > /dev/null 2>&1; then
    echo "✅ Server JAR: dist/connections-server.jar"
    echo "✅ Client JAR: dist/connections-client.jar"
else
    echo "❌ JAR packaging failed!"
    make jar
    exit 1
fi

echo ""
echo "🚀 Ready to run:"
echo "   Server: scripts/run-server.sh"
echo "   Client: scripts/run-client.sh"

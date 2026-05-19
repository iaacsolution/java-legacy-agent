#!/bin/bash
# Télécharge les modèles LLM dans Ollama
echo "⏳ Téléchargement de Qwen2.5-Coder 7B (vision micro — analyse Java)..."
docker exec java-legacy-agent-ollama-1 ollama pull qwen2.5-coder:7b

echo "⏳ Téléchargement de Qwen2.5 14B (vision macro — synthèse documentaire)..."
docker exec java-legacy-agent-ollama-1 ollama pull qwen2.5:14b

echo "✅ Modèles disponibles :"
docker exec java-legacy-agent-ollama-1 ollama list

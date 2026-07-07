package com.audensiel.legacy.agent;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * Sélectionne le backend LLM selon l'environnement, par ordre de priorité :
 *
 *   1. VLLM_BASE_URL    → vLLM local GPU   (continuous batching, parallélisme réel, souverain)
 *   2. ANTHROPIC_API_KEY → Claude Haiku    (cloud, rapide, données sortent on-premise)
 *   3. sinon             → Ollama local CPU (sérialise les requêtes → AGENT_WORKERS=1 conseillé)
 */
public class LlmModelFactory {

    public static ChatLanguageModel create(String ollamaBaseUrl, double temperature, Duration timeout) {
        String vllmUrl  = System.getenv("VLLM_BASE_URL");
        String apiKey   = System.getenv("ANTHROPIC_API_KEY");

        if (vllmUrl != null && !vllmUrl.isBlank()) {
            System.out.println("  [LLM] vLLM local GPU — continuous batching (souverain, parallèle)");
            return OpenAiChatModel.builder()
                    .baseUrl(vllmUrl + "/v1")
                    .apiKey("EMPTY")                              // vLLM n'exige pas de clé
                    .modelName("Qwen/Qwen2.5-Coder-7B-Instruct")
                    .temperature(temperature)
                    .maxTokens(2048)
                    .timeout(timeout)
                    .build();
        }

        if (apiKey != null && !apiKey.isBlank()) {
            System.out.println("  [LLM] Anthropic Claude Haiku (cloud)");
            return AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("claude-haiku-4-5-20251001")
                    .temperature(temperature)
                    .maxTokens(2048)
                    .build();
        }

        System.out.println("  [LLM] Ollama local CPU (sérialise — AGENT_WORKERS=1 conseillé)");
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName("qwen2.5-coder:7b")
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }
}

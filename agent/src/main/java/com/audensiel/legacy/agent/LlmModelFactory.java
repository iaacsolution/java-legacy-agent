package com.audensiel.legacy.agent;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;

/**
 * Crée le modèle LLM selon l'environnement :
 * - ANTHROPIC_API_KEY présente → Claude Haiku (cloud)
 * - sinon → Qwen2.5-Coder via Ollama (local)
 */
public class LlmModelFactory {

    public static ChatLanguageModel create(String ollamaBaseUrl, double temperature, Duration timeout) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            System.out.println("  [LLM] Anthropic Claude Haiku (cloud)");
            return AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("claude-haiku-4-5-20251001")
                    .temperature(temperature)
                    .maxTokens(2048)
                    .build();
        }
        System.out.println("  [LLM] Qwen2.5-Coder via Ollama (local)");
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName("qwen2.5-coder:7b")
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }
}

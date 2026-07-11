package com.audensiel.legacy.agent;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * Sélectionne le backend LLM selon l'environnement, par ordre de priorité :
 *
 *   1. VLLM_BASE_URL     → vLLM local GPU   (continuous batching, parallélisme réel, souverain)
 *   2. ANTHROPIC_API_KEY → Claude Haiku     (cloud) — UNIQUEMENT si ALLOW_CLOUD_CODE_ANALYSIS=true
 *   3. sinon              → Ollama local CPU (sérialise les requêtes → AGENT_WORKERS=1 conseillé)
 *
 * create() alimente tous les agents du pipeline : celui qui analyse le code source brut du
 * client (JavaDocumentationAgent) ET ceux qui traitent les specs qui en dérivent (DAT, plan de
 * migration). Une clé Anthropic présente ne suffit plus, à elle seule, à faire sortir du code
 * client vers le cloud — il faut le flag explicite ALLOW_CLOUD_CODE_ANALYSIS en plus. Par défaut
 * (flag absent), tout reste on-prem : dégradation vers Ollama CPU plutôt que fuite silencieuse.
 */
public class LlmModelFactory {

    private static boolean cloudAllowed() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("ALLOW_CLOUD_CODE_ANALYSIS", "false"));
    }

    /**
     * True si create() va effectivement router vers Anthropic (cloud). Reflète exactement la même
     * priorité que create() : vLLM d'abord (jamais cloud), puis Anthropic si clé + flag présents.
     * Utilisé par les agents en amont de create() pour décider QUOI envoyer (ex: squelette anonymisé
     * sans corps de méthode vers le cloud, code complet OK en local) — défense en profondeur qui ne
     * dépend pas uniquement de la séparation infra (java-analyzer sans ANTHROPIC_API_KEY par défaut).
     */
    public static boolean isCloudActive() {
        String vllmUrl = System.getenv("VLLM_BASE_URL");
        String apiKey  = System.getenv("ANTHROPIC_API_KEY");
        return (vllmUrl == null || vllmUrl.isBlank())
                && apiKey != null && !apiKey.isBlank()
                && cloudAllowed();
    }

    /** Résumé une ligne du backend actif, pour la bannière de démarrage (Main). */
    public static String describeActiveBackend(String ollamaBaseUrl) {
        String vllmUrl = System.getenv("VLLM_BASE_URL");
        String apiKey  = System.getenv("ANTHROPIC_API_KEY");

        if (vllmUrl != null && !vllmUrl.isBlank()) {
            return "vLLM local GPU — " + vllmUrl + " (continuous batching, souverain)";
        }
        if (apiKey != null && !apiKey.isBlank() && cloudAllowed()) {
            return "Anthropic Claude Haiku (cloud, ALLOW_CLOUD_CODE_ANALYSIS=true — code envoyé hors périmètre)";
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return "Ollama local CPU — clé Anthropic ignorée (ALLOW_CLOUD_CODE_ANALYSIS non activé)";
        }
        return "Ollama local CPU — " + ollamaBaseUrl + " (sérialise, AGENT_WORKERS=1 conseillé)";
    }

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
            if (cloudAllowed()) {
                System.out.println("  [LLM] Anthropic Claude Haiku (cloud) — ALLOW_CLOUD_CODE_ANALYSIS=true, code envoyé hors périmètre");
                return AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("claude-haiku-4-5-20251001")
                        .temperature(temperature)
                        .maxTokens(2048)
                        .build();
            }
            System.out.println("  [LLM] Clé Anthropic présente mais ALLOW_CLOUD_CODE_ANALYSIS non activé — "
                    + "bascule sur Ollama local (le code ne sort pas du périmètre)");
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

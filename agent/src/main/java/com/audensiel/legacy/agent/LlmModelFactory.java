package com.audensiel.legacy.agent;

import com.audensiel.legacy.agent.governance.RedactingChatModel;
import com.audensiel.legacy.agent.governance.Redactor;
import com.audensiel.legacy.agent.observability.SpanRecorder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

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

    private static final Logger log = LoggerFactory.getLogger(LlmModelFactory.class);

    static final String VLLM_MODEL      = "Qwen/Qwen2.5-Coder-7B-Instruct";
    static final String ANTHROPIC_MODEL = "claude-haiku-4-5-20251001";
    static final String OLLAMA_MODEL    = "qwen2.5-coder:7b";

    /** Un seul Redactor par processus : un même secret garde le même marqueur sur tous les appels. */
    private static final Redactor REDACTOR = Redactor.withRandomKey();

    private static boolean cloudAllowed(Function<String, String> env) {
        return Boolean.parseBoolean(getOrDefault(env, "ALLOW_CLOUD_CODE_ANALYSIS", "false"));
    }

    private static String getOrDefault(Function<String, String> env, String key, String fallback) {
        String v = env.apply(key);
        return v == null ? fallback : v;
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * True si create() va effectivement router vers Anthropic (cloud). Reflète exactement la même
     * priorité que create() : vLLM d'abord (jamais cloud), puis Anthropic si clé + flag présents.
     * Utilisé par les agents en amont de create() pour décider QUOI envoyer (ex: squelette anonymisé
     * sans corps de méthode vers le cloud, code complet OK en local) — défense en profondeur qui ne
     * dépend pas uniquement de la séparation infra (java-analyzer sans ANTHROPIC_API_KEY par défaut).
     */
    public static boolean isCloudActive() {
        return isCloudActive(System::getenv);
    }

    /** Variante à environnement injecté (tests). */
    public static boolean isCloudActive(Function<String, String> env) {
        return !isSet(env.apply("VLLM_BASE_URL"))
                && isSet(env.apply("ANTHROPIC_API_KEY"))
                && cloudAllowed(env);
    }

    /**
     * Clé stable « backend/modèle » du backend que create() choisirait, même priorité.
     *
     * <p>Lue par l'évaluation (EvalMain --json) pour sélectionner la baseline F1 du bon
     * backend : les composantes LLM n'ont pas la même valeur d'un backend à l'autre, une
     * baseline Ollama ne doit jamais juger un passage Haiku.
     */
    public static String activeBackendKey() {
        if (isSet(System.getenv("VLLM_BASE_URL"))) return "vllm/" + VLLM_MODEL;
        if (isCloudActive()) return "anthropic/" + ANTHROPIC_MODEL;
        return "ollama/" + OLLAMA_MODEL;
    }

    /** Résumé une ligne du backend actif, pour la bannière de démarrage (Main). */
    public static String describeActiveBackend(String ollamaBaseUrl) {
        String vllmUrl = System.getenv("VLLM_BASE_URL");
        String apiKey  = System.getenv("ANTHROPIC_API_KEY");

        if (isSet(vllmUrl)) {
            return "vLLM local GPU — " + vllmUrl + " (continuous batching, souverain)";
        }
        if (isSet(apiKey) && cloudAllowed(System::getenv)) {
            return "Anthropic Claude Haiku (cloud, ALLOW_CLOUD_CODE_ANALYSIS=true — code envoyé hors périmètre)";
        }
        if (isSet(apiKey)) {
            return "Ollama local CPU — clé Anthropic ignorée (ALLOW_CLOUD_CODE_ANALYSIS non activé)";
        }
        return "Ollama local CPU — " + ollamaBaseUrl + " (sérialise, AGENT_WORKERS=1 conseillé)";
    }

    /**
     * Écouteurs du flight recorder, attachés ici plutôt que dans chaque agent.
     *
     * <p>create() est l'unique site de construction de modèle du projet : un écouteur posé
     * ici couvre tous les appels LLM du pipeline sans qu'aucun agent ne soit modifié.
     *
     * <p>L'écouteur ne lit que le décompte de jetons et le nom du modèle — jamais les
     * messages de la requête ni la réponse. À ne pas confondre avec logRequests/logResponses,
     * qui logueraient aussi les en-têtes HTTP (voir le garde pre-commit du dépôt).
     */
    private static java.util.List<ChatModelListener> listeners() {
        return java.util.List.of(SpanRecorder.get().chatModelListener());
    }

    public static ChatLanguageModel create(String ollamaBaseUrl, double temperature, Duration timeout) {
        return create(ollamaBaseUrl, temperature, timeout, System::getenv);
    }

    /** Variante à environnement injecté (tests) : mêmes priorités, même redaction. */
    static ChatLanguageModel create(String ollamaBaseUrl, double temperature, Duration timeout,
                                    Function<String, String> env) {
        String vllmUrl  = env.apply("VLLM_BASE_URL");
        String anthropicKey = env.apply("ANTHROPIC_API_KEY");

        if (isSet(vllmUrl)) {
            System.out.println("  [LLM] vLLM local GPU — continuous batching (souverain, parallèle)");
            return wrap(OpenAiChatModel.builder()
                    .baseUrl(vllmUrl + "/v1")
                    .apiKey("EMPTY")                              // vLLM n'exige pas de clé
                    .modelName(VLLM_MODEL)
                    .temperature(temperature)
                    .maxTokens(2048)
                    .timeout(timeout)
                    .listeners(listeners())
                    .build(), env);
        }

        if (isSet(anthropicKey)) {
            if (cloudAllowed(env)) {
                System.out.println("  [LLM] Anthropic Claude Haiku (cloud) — ALLOW_CLOUD_CODE_ANALYSIS=true, code envoyé hors périmètre");
                return wrap(AnthropicChatModel.builder()
                        .apiKey(anthropicKey)
                        .modelName(ANTHROPIC_MODEL)
                        .temperature(temperature)
                        .maxTokens(2048)
                        .listeners(listeners())
                        .build(), env);
            }
            System.out.println("  [LLM] Clé Anthropic présente mais ALLOW_CLOUD_CODE_ANALYSIS non activé — "
                    + "bascule sur Ollama local (le code ne sort pas du périmètre)");
        }

        System.out.println("  [LLM] Ollama local CPU (sérialise — AGENT_WORKERS=1 conseillé)");
        return wrap(OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(OLLAMA_MODEL)
                .temperature(temperature)
                .timeout(timeout)
                .listeners(listeners())
                .build(), env);
    }

    /**
     * Redaction des secrets et PII, posée sur TOUS les backends (cloud et on-prem).
     *
     * <p>{@code LLM_REDACTION} : {@code on} (défaut) ou {@code off}.
     * {@code LLM_REDACTION_POLICY} : {@code redact} (défaut) ou {@code block}.
     * Toute autre valeur est une erreur explicite — jamais un retour silencieux au défaut,
     * qui ferait croire à une politique qui n'est pas appliquée.
     */
    static ChatLanguageModel wrap(ChatLanguageModel model, Function<String, String> env) {
        String enabled = getOrDefault(env, "LLM_REDACTION", "on").trim().toLowerCase(Locale.ROOT);
        String policy  = getOrDefault(env, "LLM_REDACTION_POLICY", "redact").trim().toLowerCase(Locale.ROOT);

        RedactingChatModel.Policy p = switch (policy) {
            case "redact" -> RedactingChatModel.Policy.REDACT;
            case "block"  -> RedactingChatModel.Policy.BLOCK;
            default -> throw new IllegalStateException(
                    "LLM_REDACTION_POLICY invalide : « " + policy + " » (attendu : redact ou block)");
        };
        switch (enabled) {
            case "on" -> {
                System.out.println("  [LLM] Redaction des secrets/PII active — politique " + policy);
                return new RedactingChatModel(model, REDACTOR, p);
            }
            case "off" -> {
                log.warn("Redaction DÉSACTIVÉE (LLM_REDACTION=off) : secrets et PII éventuels partent tels quels vers le LLM");
                System.out.println("  [LLM] Redaction DÉSACTIVÉE (LLM_REDACTION=off)");
                return model;
            }
            default -> throw new IllegalStateException(
                    "LLM_REDACTION invalide : « " + enabled + " » (attendu : on ou off)");
        }
    }
}

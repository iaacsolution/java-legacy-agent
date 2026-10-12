package com.audensiel.legacy.agent;

import com.audensiel.legacy.agent.governance.CapturingChatModel;
import com.audensiel.legacy.agent.governance.CloudRawCodeRefusedException;
import com.audensiel.legacy.agent.governance.FakeSecrets;
import com.audensiel.legacy.agent.governance.RedactingChatModel;
import com.audensiel.legacy.agent.governance.Redactor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Câblage de la redaction sur le chemin réel : factory (3 backends), 5 proxys AiServices,
 * garde cloud. Aucun vrai LLM : la construction d'un client n'ouvre aucune connexion, et
 * les appels partent vers un faux modèle qui capture ce qu'il reçoit.
 */
class RedactionWiringTest {

    private static final Redactor REDACTOR = Redactor.withRandomKey();

    private static Function<String, String> env(Map<String, String> vars) {
        return vars::get;
    }

    /** Faux modèle enveloppé exactement comme le fait la factory, politique redact. */
    private static ChatLanguageModel wrapped(CapturingChatModel fake) {
        return LlmModelFactory.wrap(fake, env(Map.of()));
    }

    // ── Factory : la redaction est posée sur les trois backends ─────────────

    @Test
    @DisplayName("factory : vLLM, Anthropic et Ollama renvoient tous un modèle filtré")
    void troisBackends() {
        Map<String, Map<String, String>> backends = Map.of(
                "vllm", Map.of("VLLM_BASE_URL", "http://127.0.0.1:1"),
                "anthropic", Map.of("ANTHROPIC_API_KEY", "FAKE-NOT-A-REAL-KEY",
                                    "ALLOW_CLOUD_CODE_ANALYSIS", "true"),
                "ollama", Map.of());
        backends.forEach((name, vars) -> {
            ChatLanguageModel m = LlmModelFactory.create("http://127.0.0.1:1", 0.1, Duration.ofSeconds(1), env(vars));
            assertInstanceOf(RedactingChatModel.class, m, name + " : redaction absente");
            assertEquals(RedactingChatModel.Policy.REDACT, ((RedactingChatModel) m).policy());
        });
    }

    @Test
    @DisplayName("factory : LLM_REDACTION=off désactive, block est appliqué, valeur inconnue = erreur explicite")
    void configuration() {
        ChatLanguageModel off = LlmModelFactory.create("http://127.0.0.1:1", 0.1, Duration.ofSeconds(1),
                env(Map.of("LLM_REDACTION", "off")));
        assertFalse(off instanceof RedactingChatModel);

        ChatLanguageModel block = LlmModelFactory.create("http://127.0.0.1:1", 0.1, Duration.ofSeconds(1),
                env(Map.of("LLM_REDACTION_POLICY", "block")));
        assertEquals(RedactingChatModel.Policy.BLOCK, ((RedactingChatModel) block).policy());

        assertThrows(IllegalStateException.class, () -> LlmModelFactory.create("http://127.0.0.1:1", 0.1,
                Duration.ofSeconds(1), env(Map.of("LLM_REDACTION", "peut-etre"))));
        assertThrows(IllegalStateException.class, () -> LlmModelFactory.create("http://127.0.0.1:1", 0.1,
                Duration.ofSeconds(1), env(Map.of("LLM_REDACTION_POLICY", "ignore"))));
    }

    // ── Les 5 proxys AiServices : aucune valeur ne sort ─────────────────────

    @Test
    @DisplayName("5 proxys (code, squelette, méthode, DAT, plan) : aucune valeur de fixture dans le payload")
    void cinqProxys() {
        CapturingChatModel fake = new CapturingChatModel();
        ChatLanguageModel model = wrapped(fake);
        JavaDocumentationAgent doc = new JavaDocumentationAgent(model, model, env(Map.of()));
        MigrationPlannerAgent planner = new MigrationPlannerAgent(model);

        String code = FakeSecrets.legacyClass();
        doc.analyzeJavaClass(code, "", false, false);                                  // CodeAnalyzer

        AstParserAgent.AstAnalysis ast = new AstParserAgent().analyze(
                new FileScannerAgent.JavaFile(Path.of("BillingGatewayBean.java"), "BillingGatewayBean", code));
        assertFalse(ast.heavyMethods().isEmpty(), "la fixture doit contenir une méthode lourde");
        doc.analyzeJavaClassWithAst(ast);                                              // Skeleton + Method

        String specs = "### BillingGatewayBean\n\n" + code;   // pire cas : du code brut dans les specs
        doc.generateDAT(specs);                                                        // DocumentationSynthesizer
        planner.generateMigrationPlan(specs);                                          // MigrationPlanner

        List<List<ChatMessage>> calls = fake.calls();
        assertTrue(calls.size() >= 5, "chaque proxy doit avoir appelé le modèle : " + calls.size());
        for (List<ChatMessage> call : calls) {
            assertInstanceOf(SystemMessage.class, call.get(0), "le message système fait partie du payload filtré");
        }
        String sent = fake.allText();
        for (String v : FakeSecrets.values()) {
            assertFalse(sent.contains(v), "une valeur de fixture est partie vers le modèle");
        }
    }

    // ── Garde cloud sur analyzeJavaClass ────────────────────────────────────

    private static final Map<String, String> CLOUD = Map.of(
            "ANTHROPIC_API_KEY", "FAKE-NOT-A-REAL-KEY", "ALLOW_CLOUD_CODE_ANALYSIS", "true");

    @Test
    @DisplayName("garde cloud : backend cloud sans ALLOW_CLOUD_RAW_CODE → refus explicite, rien n'est envoyé")
    void gardeCloudRefuse() {
        CapturingChatModel fake = new CapturingChatModel();
        ChatLanguageModel model = wrapped(fake);
        JavaDocumentationAgent doc = new JavaDocumentationAgent(model, model, env(CLOUD));

        CloudRawCodeRefusedException e = assertThrows(CloudRawCodeRefusedException.class,
                () -> doc.analyzeJavaClass("public class X {}", "", false, false));

        assertTrue(e.getMessage().contains("ALLOW_CLOUD_RAW_CODE"), "le message nomme le drapeau manquant");
        assertTrue(fake.calls().isEmpty(), "le faux modèle ne doit rien avoir reçu");
    }

    @Test
    @DisplayName("garde cloud : avec ALLOW_CLOUD_RAW_CODE=true l'appel part, filtré ; en on-prem aucune garde")
    void gardeCloudDrapeau() {
        CapturingChatModel fake = new CapturingChatModel();
        ChatLanguageModel model = wrapped(fake);

        Map<String, String> autorise = new java.util.HashMap<>(CLOUD);
        autorise.put("ALLOW_CLOUD_RAW_CODE", "true");
        new JavaDocumentationAgent(model, model, env(autorise))
                .analyzeJavaClass(FakeSecrets.legacyClass(), "", false, false);
        new JavaDocumentationAgent(model, model, env(Map.of()))
                .analyzeJavaClass(FakeSecrets.legacyClass(), "", false, false);

        assertEquals(2, fake.calls().size());
        for (String v : FakeSecrets.values()) {
            assertFalse(fake.allText().contains(v));
        }
    }
}

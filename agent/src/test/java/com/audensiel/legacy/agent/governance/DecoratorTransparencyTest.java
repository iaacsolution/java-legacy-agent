package com.audensiel.legacy.agent.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transparence du décorateur sur un texte sans secret — test déterministe, sans LLM.
 *
 * <p>Un faux modèle interne enregistre CHAQUE appel reçu : méthode, liste de messages,
 * outils, format de réponse. On compare l'appel direct et l'appel à travers
 * {@link RedactingChatModel} : la requête doit être identique (égalité des objets
 * LangChain4j, pas seulement du texte), et {@code supportedCapabilities()} doit renvoyer
 * la même valeur — c'est elle qui décide si AiServices passe par {@code chat()} ou par
 * {@code generate()}.
 *
 * <p>Les paramètres d'échantillonnage (température, maxTokens, timeout) ne transitent pas
 * par la requête en LangChain4j 0.36.2 : ils sont fixés à la construction du modèle
 * interne, que le décorateur ne touche pas.
 *
 * <p>C'est ce test, et non une mesure statistique du F1, qui établit que la redaction ne
 * modifie pas ce qui part vers le LLM sur le golden dataset.
 */
class DecoratorTransparencyTest {

    /** Un appel reçu par le faux modèle, champ par champ. */
    record Call(String method, List<ChatMessage> messages, List<ToolSpecification> tools,
                ToolSpecification tool, ResponseFormat responseFormat) {}

    /** Faux modèle interne : enregistre tout, répond un texte fixe, annonce des capacités données. */
    static final class RecordingModel implements ChatLanguageModel {
        final List<Call> calls = new ArrayList<>();
        final Set<Capability> capabilities;

        RecordingModel(Set<Capability> capabilities) {
            this.capabilities = capabilities;
        }

        private static Response<AiMessage> ok() {
            return Response.from(AiMessage.from("ok"));
        }

        @Override public Response<AiMessage> generate(List<ChatMessage> m) {
            calls.add(new Call("generate(List)", m, null, null, null)); return ok();
        }
        @Override public Response<AiMessage> generate(List<ChatMessage> m, List<ToolSpecification> t) {
            calls.add(new Call("generate(List,List)", m, t, null, null)); return ok();
        }
        @Override public Response<AiMessage> generate(List<ChatMessage> m, ToolSpecification t) {
            calls.add(new Call("generate(List,Tool)", m, null, t, null)); return ok();
        }
        @Override public ChatResponse chat(ChatRequest r) {
            calls.add(new Call("chat", r.messages(), r.toolSpecifications(), null, r.responseFormat()));
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }
        @Override public Set<Capability> supportedCapabilities() {
            return capabilities;
        }
    }

    interface Analyzer {
        @dev.langchain4j.service.SystemMessage("Analyse le code fourni.")
        String analyze(String code);
    }

    private static List<String> goldenCodes() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(Path.of("..", "golden_dataset.json")));
        List<String> codes = new ArrayList<>();
        cases.forEach(c -> codes.add(c.get("javaCode").asText()));
        assertEquals(5, codes.size());
        return codes;
    }

    private static RedactingChatModel decorate(ChatLanguageModel inner) {
        return new RedactingChatModel(inner, Redactor.withRandomKey(), RedactingChatModel.Policy.REDACT);
    }

    @Test
    @DisplayName("appel direct = appel décoré : méthode, messages, outils, format — 4 méthodes × 5 cas")
    void requetesIdentiques() throws Exception {
        ToolSpecification tool = ToolSpecification.builder().name("outil_factice").description("aucun").build();
        ResponseFormat json = ResponseFormat.builder().type(ResponseFormatType.JSON).build();

        for (String code : goldenCodes()) {
            List<ChatMessage> messages = List.of(SystemMessage.from("Analyse le code fourni."), UserMessage.from(code));
            ChatRequest request = ChatRequest.builder()
                    .messages(messages).toolSpecifications(List.of(tool)).responseFormat(json).build();

            RecordingModel direct = new RecordingModel(Set.of());
            RecordingModel inner = new RecordingModel(Set.of());
            ChatLanguageModel decorated = decorate(inner);

            for (ChatLanguageModel m : List.of(direct, decorated)) {
                m.generate(messages);
                m.generate(messages, List.of(tool));
                m.generate(messages, tool);
                m.chat(request);
            }

            assertEquals(direct.calls, inner.calls,
                    "le décorateur a modifié la méthode appelée ou un argument de la requête");
        }
    }

    @Test
    @DisplayName("supportedCapabilities() : même valeur avec et sans décorateur")
    void capacitesIdentiques() {
        for (Set<Capability> caps : List.of(Set.<Capability>of(), Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))) {
            RecordingModel inner = new RecordingModel(caps);
            assertEquals(inner.supportedCapabilities(), decorate(inner).supportedCapabilities());
        }
    }

    @Test
    @DisplayName("via AiServices : même chemin d'appel et même requête, avec et sans décorateur")
    void aiServicesIdentique() throws Exception {
        for (Set<Capability> caps : List.of(Set.<Capability>of(), Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))) {
            for (String code : goldenCodes()) {
                RecordingModel direct = new RecordingModel(caps);
                RecordingModel inner = new RecordingModel(caps);

                AiServices.create(Analyzer.class, direct).analyze(code);
                AiServices.create(Analyzer.class, decorate(inner)).analyze(code);

                assertEquals(1, direct.calls.size());
                assertEquals(direct.calls, inner.calls, "AiServices doit emprunter le même chemin, même requête");
            }
        }
    }
}

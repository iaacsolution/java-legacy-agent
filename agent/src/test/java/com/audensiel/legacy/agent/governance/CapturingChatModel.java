package com.audensiel.legacy.agent.governance;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Faux modèle : n'appelle aucun LLM, capture chaque liste de messages reçue et répond un
 * texte fixe. Ce qu'il capture est exactement ce qui serait parti vers le backend.
 */
public final class CapturingChatModel implements ChatLanguageModel {

    private final List<List<ChatMessage>> calls = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        calls.add(List.copyOf(messages));
        return Response.from(AiMessage.from("Réponse factice du faux modèle."));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> tools) {
        return generate(messages);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        calls.add(List.copyOf(request.messages()));
        return ChatResponse.builder().aiMessage(AiMessage.from("Réponse factice du faux modèle.")).build();
    }

    public List<List<ChatMessage>> calls() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    /** Tout le texte capturé, tous appels et tous messages confondus. */
    public String allText() {
        StringBuilder sb = new StringBuilder();
        for (List<ChatMessage> call : calls()) {
            for (ChatMessage m : call) sb.append(textOf(m)).append('\n');
        }
        return sb.toString();
    }

    public static String textOf(ChatMessage m) {
        if (m instanceof SystemMessage sm) return sm.text();
        if (m instanceof UserMessage um) return um.singleText();
        if (m instanceof AiMessage am) return am.text();
        if (m instanceof ToolExecutionResultMessage tr) return tr.text();
        return String.valueOf(m);
    }
}

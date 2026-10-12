package com.audensiel.legacy.agent.governance;

import com.audensiel.legacy.agent.observability.Span;
import com.audensiel.legacy.agent.observability.SpanRecorder;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Décorateur de {@link ChatLanguageModel} : filtre chaque message (système compris) par le
 * {@link Redactor} avant qu'il atteigne le modèle réel, quel que soit le backend.
 *
 * <p>Posé par {@code LlmModelFactory.create()}, unique site de construction de modèle : il
 * couvre donc les 5 proxys {@code AiServices} du projet sans qu'aucun agent ne soit modifié.
 * Toutes les méthodes d'appel sont surchargées — pas seulement {@code generate(List)} :
 * {@code AiServices} 0.36.2 passe par {@code chat(ChatRequest)} quand un format de réponse
 * JSON est demandé, et par {@code generate(List, List)} quand il y a des outils.
 *
 * <p>L'écouteur du flight recorder reste attaché au modèle interne : il observe la requête
 * déjà filtrée.
 */
public final class RedactingChatModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(RedactingChatModel.class);

    public enum Policy { REDACT, BLOCK }

    private final ChatLanguageModel delegate;
    private final Redactor redactor;
    private final Policy policy;
    private final Supplier<SpanRecorder> recorder;

    public RedactingChatModel(ChatLanguageModel delegate, Redactor redactor, Policy policy) {
        this(delegate, redactor, policy, SpanRecorder::get);
    }

    /** Pour les tests : enregistreur fourni. */
    RedactingChatModel(ChatLanguageModel delegate, Redactor redactor, Policy policy,
                       Supplier<SpanRecorder> recorder) {
        this.delegate = delegate;
        this.redactor = redactor;
        this.policy = policy;
        this.recorder = recorder;
    }

    public Policy policy() {
        return policy;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return delegate.generate(filter(messages));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return delegate.generate(filter(messages), toolSpecifications);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return delegate.generate(filter(messages), toolSpecification);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatRequest filtered = ChatRequest.builder()
                .messages(filter(request.messages()))
                .toolSpecifications(request.toolSpecifications())
                .responseFormat(request.responseFormat())
                .build();
        return delegate.chat(filtered);
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    /**
     * Filtre tous les messages, comptabilise les détections, applique la politique. En mode
     * BLOCK, l'exception part AVANT tout appel au modèle : rien n'est envoyé.
     */
    private List<ChatMessage> filter(List<ChatMessage> messages) {
        EnumMap<Redactor.Type, Integer> counts = new EnumMap<>(Redactor.Type.class);
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            out.add(filterOne(m, counts));
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        record(counts, total);

        if (policy == Policy.BLOCK && total > 0) {
            log.error("Envoi au LLM bloqué (LLM_REDACTION_POLICY=block) : {} détection(s) — {}", total, counts);
            throw new RedactionBlockedException(counts);
        }
        if (total > 0) {
            log.info("Redaction : {} détection(s) remplacée(s) avant envoi au LLM — {}", total, counts);
        }
        // Payload EXACT envoyé, donc déjà filtré. DEBUG, désactivé par défaut (logback.xml) ;
        // ce log vivait dans JavaDocumentationAgent, avant la redaction : activé, il aurait
        // écrit les secrets en clair dans les journaux.
        if (log.isDebugEnabled()) {
            for (ChatMessage m : out) {
                log.debug("Payload envoyé au LLM [{}] : {}", m.type(), textOf(m));
            }
        }
        return out;
    }

    private ChatMessage filterOne(ChatMessage message, Map<Redactor.Type, Integer> counts) {
        if (message instanceof SystemMessage sm) {
            return SystemMessage.from(redact(sm.text(), counts));
        }
        if (message instanceof UserMessage um) {
            List<Content> contents = new ArrayList<>(um.contents().size());
            for (Content c : um.contents()) {
                contents.add(c instanceof TextContent tc ? TextContent.from(redact(tc.text(), counts)) : c);
            }
            return um.name() == null ? UserMessage.from(contents) : UserMessage.from(um.name(), contents);
        }
        if (message instanceof AiMessage am && am.text() != null) {
            String text = redact(am.text(), counts);
            return am.hasToolExecutionRequests() ? AiMessage.from(text, am.toolExecutionRequests()) : AiMessage.from(text);
        }
        if (message instanceof ToolExecutionResultMessage tr) {
            return ToolExecutionResultMessage.from(tr.id(), tr.toolName(), redact(tr.text(), counts));
        }
        return message;
    }

    private String redact(String text, Map<Redactor.Type, Integer> counts) {
        Redactor.Result r = redactor.redact(text);
        r.counts().forEach((type, n) -> counts.merge(type, n, Integer::sum));
        return r.text();
    }

    /**
     * Flight recorder : un span « redaction » par appel, fils du span courant (le span
     * « agent » de RunMetrics, qui porte l'attribut {@code class} — d'où l'attribution par
     * fichier). Uniquement des nombres et la politique : jamais une valeur filtrée.
     */
    private void record(Map<Redactor.Type, Integer> counts, int total) {
        SpanRecorder rec = recorder.get();
        if (rec == null || !rec.enabled()) return;
        try (Span span = rec.span("redaction", "Redactor", "redact")) {
            span.attribute("redaction.policy", policy.name().toLowerCase())
                .attribute("redaction.total", total);
            counts.forEach((type, n) -> span.attribute("redaction." + type.name(), n));
            if (policy == Policy.BLOCK && total > 0) span.status("blocked");
        }
    }

    private static String textOf(ChatMessage m) {
        if (m instanceof SystemMessage sm) return sm.text();
        if (m instanceof UserMessage um) return um.hasSingleText() ? um.singleText() : String.valueOf(um.contents());
        if (m instanceof AiMessage am) return am.text();
        if (m instanceof ToolExecutionResultMessage tr) return tr.text();
        return String.valueOf(m);
    }
}

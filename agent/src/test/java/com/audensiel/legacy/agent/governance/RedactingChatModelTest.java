package com.audensiel.legacy.agent.governance;

import com.audensiel.legacy.agent.observability.Span;
import com.audensiel.legacy.agent.observability.SpanRecord;
import com.audensiel.legacy.agent.observability.SpanRecorder;
import com.audensiel.legacy.agent.observability.SpanSink;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RedactingChatModelTest {

    private static final Redactor REDACTOR =
            Redactor.withKey("cle-de-test-fixe-NON-SECRETE".getBytes(StandardCharsets.UTF_8));

    private static List<ChatMessage> messagesWithSecrets() {
        // Le message système contient lui aussi une valeur sensible, filtrée comme les autres.
        return List.of(
                SystemMessage.from("Contexte d'exécution : " + FakeSecrets.LINE_PW),
                UserMessage.from(FakeSecrets.legacyClass()));
    }

    private static void assertNoFixtureValue(String sent) {
        for (String v : FakeSecrets.values()) {
            assertFalse(sent.contains(v), "une valeur de fixture est partie vers le modèle");
        }
        assertTrue(sent.contains("<REDACTED:"), "les marqueurs doivent remplacer les valeurs");
    }

    @Test
    @DisplayName("redact : generate(List) — système compris, aucune valeur ne part")
    void generateFiltre() {
        CapturingChatModel fake = new CapturingChatModel();
        new RedactingChatModel(fake, REDACTOR, RedactingChatModel.Policy.REDACT, SpanRecorder::disabled)
                .generate(messagesWithSecrets());

        assertEquals(1, fake.calls().size());
        List<ChatMessage> sent = fake.calls().get(0);
        assertInstanceOf(SystemMessage.class, sent.get(0), "le message système reste un message système");
        assertTrue(((SystemMessage) sent.get(0)).text().contains("<REDACTED:" + Redactor.Type.PASSWORD + ":"),
                "le message système est filtré lui aussi");
        assertNoFixtureValue(fake.allText());
    }

    @Test
    @DisplayName("redact : chat(ChatRequest) et generate(List, outils) sont filtrés aussi")
    void autresCheminsFiltres() {
        CapturingChatModel fake = new CapturingChatModel();
        RedactingChatModel model =
                new RedactingChatModel(fake, REDACTOR, RedactingChatModel.Policy.REDACT, SpanRecorder::disabled);

        model.chat(ChatRequest.builder().messages(messagesWithSecrets()).build());
        model.generate(messagesWithSecrets(), List.<ToolSpecification>of());

        assertEquals(2, fake.calls().size());
        assertNoFixtureValue(fake.allText());
    }

    @Test
    @DisplayName("block : exception AVANT l'appel, le faux modèle ne reçoit rien")
    void blockNEnvoieRien() {
        CapturingChatModel fake = new CapturingChatModel();
        RedactingChatModel model =
                new RedactingChatModel(fake, REDACTOR, RedactingChatModel.Policy.BLOCK, SpanRecorder::disabled);

        RedactionBlockedException e = assertThrows(RedactionBlockedException.class,
                () -> model.generate(messagesWithSecrets()));

        assertTrue(fake.calls().isEmpty(), "aucun message ne doit avoir atteint le modèle");
        assertTrue(e.counts().get(Redactor.Type.PASSWORD) >= 1);
        for (String v : FakeSecrets.values()) {
            assertFalse(e.getMessage().contains(v), "le message d'exception ne porte jamais une valeur");
        }
    }

    @Test
    @DisplayName("block : un message sans secret passe")
    void blockLaissePasserLeCodePropre() {
        CapturingChatModel fake = new CapturingChatModel();
        new RedactingChatModel(fake, REDACTOR, RedactingChatModel.Policy.BLOCK, SpanRecorder::disabled)
                .generate(List.of(UserMessage.from("public class Propre { int x; }")));
        assertEquals(1, fake.calls().size());
    }

    // ── Flight recorder ─────────────────────────────────────────────────────

    /** Puits en mémoire (RecordingSink d'observability est package-private). */
    private static final class MemorySink implements SpanSink {
        final List<SpanRecord> records = Collections.synchronizedList(new ArrayList<>());
        @Override public void accept(SpanRecord r) { records.add(r); }
        @Override public void close() { }
    }

    @Test
    @DisplayName("flight recorder : span « redaction » fils du span agent, comptages seuls, aucune valeur")
    void spanRedaction() {
        MemorySink sink = new MemorySink();
        SpanRecorder recorder = SpanRecorder.forSink(sink, UUID.randomUUID());
        CapturingChatModel fake = new CapturingChatModel();
        RedactingChatModel model =
                new RedactingChatModel(fake, REDACTOR, RedactingChatModel.Policy.REDACT, () -> recorder);

        try (Span agent = recorder.span("agent", "JavaDocumentationAgent", "analyze")) {
            agent.attribute("class", "BillingGatewayBean");
            model.generate(messagesWithSecrets());
        }

        SpanRecord agentSpan = sink.records.stream().filter(r -> r.spanKind().equals("agent")).findFirst().orElseThrow();
        SpanRecord red = sink.records.stream().filter(r -> r.spanKind().equals("redaction")).findFirst().orElseThrow();

        assertEquals(agentSpan.spanId(), red.parentId(),
                "le span redaction est rattaché au span agent, qui porte la classe (le fichier)");
        assertTrue(agentSpan.attributesJson().contains("BillingGatewayBean"));

        String attrs = red.attributesJson();
        for (String v : FakeSecrets.values()) {
            assertFalse(attrs.contains(v), "jamais une valeur dans les attributs du flight recorder");
        }
        assertFalse(attrs.contains("<REDACTED:"), "ni marqueur : uniquement des nombres");
        assertTrue(attrs.contains("\"redaction.policy\":\"redact\""), attrs);
        assertTrue(attrs.matches(".*\"redaction\\.PASSWORD\":\"\\d+\".*"), attrs);
        assertTrue(attrs.matches(".*\"redaction\\.JDBC_CREDENTIALS\":\"\\d+\".*"), attrs);
        assertTrue(attrs.matches(".*\"redaction\\.total\":\"\\d+\".*"), attrs);
    }

    @Test
    @DisplayName("flight recorder : un blocage est enregistré avec le statut blocked")
    void spanBlocage() {
        MemorySink sink = new MemorySink();
        SpanRecorder recorder = SpanRecorder.forSink(sink, UUID.randomUUID());
        RedactingChatModel model = new RedactingChatModel(
                new CapturingChatModel(), REDACTOR, RedactingChatModel.Policy.BLOCK, () -> recorder);

        assertThrows(RedactionBlockedException.class, () -> model.generate(messagesWithSecrets()));

        SpanRecord red = sink.records.stream().filter(r -> r.spanKind().equals("redaction")).findFirst().orElseThrow();
        assertEquals("blocked", red.status());
    }
}

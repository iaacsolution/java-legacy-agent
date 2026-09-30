package com.audensiel.legacy.agent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Un échec doit être <strong>enregistré</strong> et <strong>relancé</strong>. Avaler
 * l'exception transformerait une étape échouée en étape apparemment réussie — c'est
 * exactement le défaut qui avait déjà frappé ce pipeline (commit 4128e54 : des
 * {@code ConcurrentModificationException} silencieusement avalées faisaient rapporter
 * un succès sur une analyse vide).
 */
class SpanExceptionTest {

    private SpanRecorder recorderSur(RecordingSink sink) {
        return SpanRecorder.forSink(sink, UUID.randomUUID());
    }

    @Test
    @DisplayName("une exception est enregistrée ET relancée à l'identique")
    void exceptionEnregistreeEtRelancee() throws Exception {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = recorderSur(sink);
        IllegalStateException attendue = new IllegalStateException("panne simulée");

        IllegalStateException relancee = assertThrows(IllegalStateException.class,
                () -> recorder.call("agent", "AstParserAgent", "ast", () -> {
                    throw attendue;
                }));

        assertSame(attendue, relancee, "l'exception doit être relancée telle quelle, non enveloppée");

        assertEquals(1, sink.size(), "l'échec doit tout de même produire un span");
        SpanRecord span = sink.records().get(0);
        assertEquals("error", span.status());
        assertEquals("AstParserAgent", span.agent());
        assertTrue(span.attributesJson().contains("java.lang.IllegalStateException"),
                "le type d'exception doit figurer dans les attributs");
    }

    @Test
    @DisplayName("le message d'exception n'est jamais enregistré (minimisation)")
    void messageJamaisEnregistre() {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = recorderSur(sink);

        // Un message d'exception peut transporter du code client, une URL portant un
        // identifiant d'accès ou un fragment de prompt : seul le type est conservé.
        // Le marqueur ci-dessous tient lieu de tel contenu sans en être un.
        String messageSensible = "MARQUEUR-CONFIDENTIEL-7F3A dans ClientServiceBean.calculerSolde()";

        assertThrows(IOException.class,
                () -> recorder.call("agent", "JavaDocumentationAgent", "analyze", () -> {
                    throw new IOException(messageSensible);
                }));

        SpanRecord span = sink.records().get(0);
        assertTrue(span.attributesJson().contains("java.io.IOException"));
        assertFalse(span.attributesJson().contains("MARQUEUR-CONFIDENTIEL-7F3A"),
                "aucun fragment du message ne doit atteindre les attributs");
        assertFalse(span.attributesJson().contains("calculerSolde"),
                "aucun nom de méthode issu du message ne doit atteindre les attributs");
    }

    @Test
    @DisplayName("un timeout est distingué d'une erreur ordinaire")
    void timeoutDistingue() {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = recorderSur(sink);

        assertThrows(TimeoutException.class,
                () -> recorder.call("llm", "MigrationPlannerAgent", "migration-plan", () -> {
                    throw new TimeoutException();
                }));

        assertEquals("timeout", sink.records().get(0).status());
    }

    @Test
    @DisplayName("un timeout enveloppé dans une autre exception est reconnu")
    void timeoutEnveloppeReconnu() {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = recorderSur(sink);

        // LangChain4j enveloppe volontiers les échecs réseau : la détection remonte les causes.
        assertThrows(RuntimeException.class,
                () -> recorder.call("llm", "JavaDocumentationAgent", "analyze", () -> {
                    throw new RuntimeException("appel du modèle échoué", new TimeoutException());
                }));

        assertEquals("timeout", sink.records().get(0).status());
    }

    @Test
    @DisplayName("un succès produit un span de statut ok et rend la valeur")
    void succesRendLaValeur() throws Exception {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = recorderSur(sink);

        String resultat = recorder.call("agent", "FileScannerAgent", "scan", () -> "42 fichiers");

        assertEquals("42 fichiers", resultat);
        assertEquals("ok", sink.records().get(0).status());
    }

    @Test
    @DisplayName("désactivé, le bloc s'exécute et rien n'est enregistré")
    void desactiveNeFaitRien() throws Exception {
        SpanRecorder inerte = SpanRecorder.disabled();

        String resultat = inerte.call("agent", "FileScannerAgent", "scan", () -> "exécuté");
        assertEquals("exécuté", resultat);

        // Et une exception reste relancée même sans enregistrement.
        assertThrows(IllegalArgumentException.class,
                () -> inerte.call("agent", "FileScannerAgent", "scan", () -> {
                    throw new IllegalArgumentException();
                }));
    }

    @Test
    @DisplayName("une valeur d'attribut trop longue est tronquée")
    void attributTronque() {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = recorderSur(sink);

        // Garde-fou : même en cas d'écart à la règle de minimisation, on ne déverse pas
        // un fichier source entier dans la base.
        String enorme = "X".repeat(5000);
        try (Span span = recorder.span("agent", "AstParserAgent", "ast")) {
            span.attribute("class", enorme);
        }

        String json = sink.records().get(0).attributesJson();
        assertTrue(json.contains("tronqué"), "la valeur doit être marquée comme tronquée");
        assertTrue(json.length() < 1000, "la valeur ne doit pas être stockée en entier");
    }

    @Test
    @DisplayName("le JSON des attributs est échappé, pas concaténé à la main")
    void attributsEchappes() {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = recorderSur(sink);

        try (Span span = recorder.span("agent", "AstParserAgent", "ast")) {
            span.attribute("class", "Nom\"avec\\guillemets");
        }

        // Le JSON doit rester relisable : c'est Jackson qui échappe, pas une concaténation.
        String json = sink.records().get(0).attributesJson();
        assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
    }
}

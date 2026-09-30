package com.audensiel.legacy.agent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L'observabilité ne doit jamais faire tomber le pipeline. Une base absente est le cas
 * nominal, pas un cas d'erreur : le recorder est désactivé par défaut, donc la base
 * <em>sera</em> absente la plupart du temps.
 *
 * <p>Mais l'échec ne doit pas non plus être silencieux : les spans perdus sont comptés,
 * pour qu'un tableau de bord vide ne se confonde jamais avec un pipeline sans activité.
 */
class SpanWriterOfflineTest {

    /** Port 1 : réservé, aucun service. Le refus de connexion est immédiat. */
    private static final String DSN_MORT = "jdbc:postgresql://127.0.0.1:1/legacyrec";

    /**
     * Vraie tentative de connexion PgJDBC vers un port mort, plutôt qu'une fabrique qui
     * lève directement : on veut vérifier que la PSQLException réelle est bien absorbée.
     */
    private static SpanWriter writerMort(int capacite) {
        return new SpanWriter(() -> DriverManager.getConnection(DSN_MORT, "postgres", ""), capacite);
    }

    private SpanWriter writerMort() {
        return writerMort(100);
    }

    @Test
    @DisplayName("base injoignable : le travail applicatif se termine normalement")
    void pipelineContinueSansBase() throws Exception {
        SpanWriter writer = writerMort();
        SpanRecorder recorder = SpanRecorder.forSink(writer, UUID.randomUUID());
        AtomicInteger travailFait = new AtomicInteger();

        String resultat = assertDoesNotThrow(() ->
                recorder.call("run", "Pipeline", "analyze", () -> {
                    travailFait.incrementAndGet();
                    return "analyse terminée";
                }));

        assertEquals("analyse terminée", resultat);
        assertEquals(1, travailFait.get(), "le corps doit s'être exécuté malgré la base absente");

        assertDoesNotThrow(writer::close, "la fermeture ne doit jamais lever");
    }

    @Test
    @DisplayName("base injoignable : les spans perdus sont comptés, rien n'est écrit")
    void spansPerdusComptes() throws Exception {
        SpanWriter writer = writerMort();
        SpanRecorder recorder = SpanRecorder.forSink(writer, UUID.randomUUID());

        for (int i = 0; i < 5; i++) {
            recorder.call("agent", "AstParserAgent", "ast", () -> "ok");
        }

        writer.close();
        SpanWriter.Stats stats = writer.stats();

        assertEquals(0, stats.written(), "rien ne peut avoir été écrit sans base");
        assertEquals(5, stats.droppedTotal(), "les 5 spans doivent être comptés perdus");
        assertEquals(5, stats.droppedDbUnreachable());
        assertNotNull(stats.lastError(), "la dernière erreur doit être conservée pour le diagnostic");
    }

    @Test
    @DisplayName("file pleine : le dépôt ne bloque pas et les pertes sont comptées à part")
    void filePleineNeBloquePas() {
        // Capacité 2, base morte : la file sature et le surplus doit être rejeté
        // immédiatement plutôt que de faire attendre le thread appelant.
        SpanWriter writer = writerMort(2);

        long debut = System.currentTimeMillis();
        for (int i = 0; i < 500; i++) {
            writer.accept(unSpan());
        }
        long duree = System.currentTimeMillis() - debut;

        assertTrue(duree < 2_000,
                "500 dépôts doivent être quasi instantanés (non bloquants), observé : " + duree + " ms");

        writer.close();
        SpanWriter.Stats stats = writer.stats();
        assertTrue(stats.droppedQueueFull() > 0, "la saturation de la file doit être comptée séparément");
        assertEquals(500, stats.written() + stats.droppedTotal(), "aucun span ne doit disparaître des compteurs");
    }

    @Test
    @DisplayName("la fermeture est idempotente")
    void fermetureIdempotente() {
        SpanWriter writer = writerMort();
        writer.close();
        assertDoesNotThrow(writer::close);
    }

    private static SpanRecord unSpan() {
        SpanId.Stamped s = SpanId.next();
        return new SpanRecord(s.time(), s.id(), null, UUID.randomUUID(), null,
                SpanRecorder.PROJECT, "AstParserAgent", "agent", "ast", "ok",
                1.5, 0, 0, java.math.BigDecimal.ZERO.setScale(6), "{}");
    }
}

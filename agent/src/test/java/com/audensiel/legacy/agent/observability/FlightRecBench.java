package com.audensiel.legacy.agent.observability;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Microbenchmark du flight recorder. <strong>Exclu du build par défaut</strong>
 * ({@code @Tag("bench")}), il dure plusieurs minutes :
 *
 * <pre>mvn -f agent/pom.xml test -Dgroups=bench -DexcludedGroups=</pre>
 *
 * <p><strong>Pourquoi il existe.</strong> Le surcoût de l'instrumentation ne peut pas être
 * mesuré de bout en bout par {@code scripts/benchmark_speedup.py} : la durée d'un run y est
 * dominée par les appels LLM réels (médianes de référence 20 776 ms / 7 218 ms) et varie de
 * plusieurs centaines de millisecondes d'un run à l'autre par le seul effet du réseau. Un
 * span coûtant quelques microsecondes, le delta bout en bout est nécessairement noyé dans ce
 * bruit — en tirer un pourcentage donnerait un chiffre qu'on ne pourrait pas honnêtement
 * attribuer au recorder. Ce banc mesure donc directement ce qui est attribuable.
 *
 * <p>Deux coûts <strong>distincts</strong>, mesurés séparément :
 * <ol>
 *   <li><strong>côté agent</strong> — ouverture, fermeture et dépôt en file. C'est le seul
 *       coût payé par le thread qui fait le travail utile, donc le seul qui puisse ralentir
 *       le pipeline ;</li>
 *   <li><strong>débit d'écriture</strong> — spans par seconde absorbés par le thread
 *       d'arrière-plan via COPY. Il ne ralentit pas le pipeline ; s'il est insuffisant, la
 *       file borne l'effet et les spans excédentaires sont comptés perdus.</li>
 * </ol>
 *
 * <p>Chaque mesure est précédée d'une phase de chauffe non mesurée, pour que le JIT ait
 * compilé le code avant qu'on le chronomètre.
 */
@Tag("bench")
class FlightRecBench {

    private static final int WARMUP_SPANS = 200_000;
    private static final int SPANS_PER_SERIES = 100_000;
    private static final int SERIES = 5;

    /**
     * Nombre de spans d'un run réel du pipeline, pour la projection. Mesuré en base après
     * un run instrumenté ; surchargeable par {@code -Dflightrec.bench.spansPerRun=N}.
     */
    private static final int SPANS_PER_RUN =
            Integer.getInteger("flightrec.bench.spansPerRun", 30);

    /** Durée médiane d'un run du pipeline, en ms (README : 7218 ms à 4 workers). */
    private static final double RUN_MEDIAN_MS =
            Double.parseDouble(System.getProperty("flightrec.bench.runMedianMs", "7218"));

    // ── 1. Coût côté agent ──────────────────────────────────────────────────

    @Test
    @DisplayName("coût côté agent : ouverture + fermeture + dépôt en file, µs/span")
    void coutCoteAgent() throws Exception {
        // File réelle, drainée et jetée : reproduit exactement accept() de SpanWriter
        // (offer non bloquant sur une ArrayBlockingQueue) sans faire intervenir la base.
        try (DrainingSink sink = new DrainingSink(10_000)) {
            SpanRecorder recorder = SpanRecorder.forSink(sink, UUID.randomUUID());

            // ── Chauffe, NON mesurée ──
            for (int i = 0; i < WARMUP_SPANS; i++) {
                recorder.span("agent", "AstParserAgent", "ast").close();
            }

            double[] usPerSpan = new double[SERIES];
            for (int s = 0; s < SERIES; s++) {
                long debut = System.nanoTime();
                for (int i = 0; i < SPANS_PER_SERIES; i++) {
                    recorder.span("agent", "AstParserAgent", "ast").close();
                }
                long duree = System.nanoTime() - debut;
                usPerSpan[s] = duree / 1_000.0 / SPANS_PER_SERIES;
            }

            rapporte("COÛT CÔTÉ AGENT (ouverture + fermeture + dépôt)", usPerSpan, sink.acceptes());
        }
    }

    @Test
    @DisplayName("coût côté agent, recorder désactivé : doit être quasi nul")
    void coutQuandDesactive() {
        SpanRecorder inerte = SpanRecorder.disabled();

        for (int i = 0; i < WARMUP_SPANS; i++) {
            inerte.span("agent", "AstParserAgent", "ast").close();
        }

        double[] usPerSpan = new double[SERIES];
        for (int s = 0; s < SERIES; s++) {
            long debut = System.nanoTime();
            for (int i = 0; i < SPANS_PER_SERIES; i++) {
                inerte.span("agent", "AstParserAgent", "ast").close();
            }
            usPerSpan[s] = (System.nanoTime() - debut) / 1_000.0 / SPANS_PER_SERIES;
        }

        rapporte("COÛT CÔTÉ AGENT — RECORDER DÉSACTIVÉ (défaut)", usPerSpan, SERIES * (long) SPANS_PER_SERIES);
    }

    // ── 2. Débit du thread d'écriture ───────────────────────────────────────

    @Test
    @DisplayName("débit d'écriture COPY vers la base, spans/seconde")
    void debitEcritureCopy() throws Exception {
        String url  = envOr("FLIGHTREC_URL", "jdbc:postgresql://localhost:5432/legacyrec");
        String user = envOr("FLIGHTREC_USER", "postgres");
        String creds = credentials();

        try (Connection c = DriverManager.getConnection(url, user, creds)) {
            c.createStatement().execute("SELECT 1 FROM agent_span LIMIT 0");
        } catch (Exception e) {
            Assumptions.abort("Base " + url + " injoignable — banc de débit ignoré : " + e.getMessage());
        }

        UUID runId = SpanId.next().id();
        int total = 50_000;

        // Le writer est fermé AVANT de lire les compteurs : awaitDrain ne garantit que
        // la file vide, or le dernier lot peut encore être en cours d'écriture. Lire
        // stats() avant close() sous-estimerait le nombre de spans écrits.
        SpanWriter writer = new SpanWriter(() -> DriverManager.getConnection(url, user, creds), 100_000);
        try {
            SpanRecorder recorder = SpanRecorder.forSink(writer, runId);

            long debut = System.nanoTime();
            for (int i = 0; i < total; i++) {
                recorder.span("agent", "AstParserAgent", "ast").close();
            }
            writer.awaitDrain(120_000);
            writer.close();                       // joint le thread et vide le dernier lot
            double secondes = (System.nanoTime() - debut) / 1e9;

            SpanWriter.Stats stats = writer.stats();
            System.out.println();
            System.out.println("══ DÉBIT D'ÉCRITURE COPY ══");
            System.out.printf("  spans soumis        : %d%n", total);
            System.out.printf("  spans écrits        : %d%n", stats.written());
            System.out.printf("  spans perdus        : %d%n", stats.droppedTotal());
            System.out.printf("  durée dépôt+écriture: %.2f s%n", secondes);
            System.out.printf("  débit               : %.0f spans/s%n", stats.written() / secondes);
            System.out.println("  (ce coût est payé par le thread d'arrière-plan, pas par le pipeline)");
        } finally {
            writer.close();                       // idempotent
            try (Connection c = DriverManager.getConnection(url, user, creds);
                 PreparedStatement ps = c.prepareStatement("DELETE FROM agent_span WHERE run_id = ?")) {
                ps.setObject(1, runId);
                ps.executeUpdate();
            }
        }
    }

    // ── Rapport ─────────────────────────────────────────────────────────────

    private static void rapporte(String titre, double[] usPerSpan, long spansTraites) {
        double[] tri = usPerSpan.clone();
        Arrays.sort(tri);
        double mediane = tri[tri.length / 2];
        double min = tri[0];
        double max = tri[tri.length - 1];

        System.out.println();
        System.out.println("══ " + titre + " ══");
        System.out.printf("  séries              : %d × %d spans (chauffe de %d spans non mesurée)%n",
                SERIES, SPANS_PER_SERIES, WARMUP_SPANS);
        System.out.print("  valeurs (µs/span)   : ");
        for (double v : usPerSpan) System.out.printf("%.3f  ", v);
        System.out.println();
        System.out.printf("  médiane             : %.3f µs/span%n", mediane);
        System.out.printf("  étendue             : %.3f – %.3f µs/span%n", min, max);
        System.out.printf("  spans traités       : %d%n", spansTraites);

        // ── Projection sur un run réel du pipeline ──
        double coutRunMs = mediane * SPANS_PER_RUN / 1_000.0;
        System.out.printf("  projection          : %d spans/run × %.3f µs = %.4f ms par run%n",
                SPANS_PER_RUN, mediane, coutRunMs);
        System.out.printf("                        soit %.6f %% d'un run médian de %.0f ms%n",
                100.0 * coutRunMs / RUN_MEDIAN_MS, RUN_MEDIAN_MS);
    }

    // ── Utilitaires ─────────────────────────────────────────────────────────

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String credentials() {
        String v = System.getenv("FLIGHTREC_PASSWORD");
        if (v == null || v.isBlank()) v = System.getenv("PGPASSWORD");
        return v == null ? "" : v;
    }

    /**
     * File réelle drainée et jetée : isole le coût payé par le thread appelant de tout
     * ce qui se passe en aval (sérialisation CSV, réseau, base).
     */
    private static final class DrainingSink implements SpanSink {
        private final BlockingQueue<SpanRecord> queue;
        private final Thread drainer;
        private final AtomicLong acceptes = new AtomicLong();
        private volatile boolean running = true;

        DrainingSink(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.drainer = new Thread(() -> {
                while (running || !queue.isEmpty()) {
                    try {
                        queue.poll(50, TimeUnit.MILLISECONDS);   // consommé et jeté
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "bench-drainer");
            this.drainer.setDaemon(true);
            this.drainer.start();
        }

        @Override
        public void accept(SpanRecord record) {
            acceptes.incrementAndGet();
            queue.offer(record);   // non bloquant, comme SpanWriter
        }

        long acceptes() { return acceptes.get(); }

        @Override
        public void close() {
            running = false;
            try {
                drainer.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

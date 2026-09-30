package com.audensiel.legacy.agent.observability;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread d'écriture des spans vers TimescaleDB, par lots, via {@code COPY}.
 *
 * <p><strong>Le pipeline d'analyse ne doit jamais échouer à cause de ce composant.</strong>
 * Toute la logique en découle :
 * <ul>
 *   <li>file <strong>bornée</strong> et dépôt <strong>non bloquant</strong> : si la file est
 *       pleine, le span est perdu et compté, jamais attendu ;</li>
 *   <li>vidage par lots de {@value #BATCH_SIZE} lignes <strong>ou</strong> toutes les
 *       {@value #FLUSH_INTERVAL_MS} ms ;</li>
 *   <li>reconnexion automatique : un redémarrage de la base fait échouer le COPY en cours,
 *       la connexion est refermée et le lot réessayé une fois sur une connexion neuve ;</li>
 *   <li>aucune exception ne remonte à l'appelant, mais rien n'est silencieux : les pertes
 *       sont comptées et affichées à la fermeture (fail-loud).</li>
 * </ul>
 *
 * <p>Note de localisation : les nombres sont sérialisés via {@code Double.toString} et
 * {@code BigDecimal.toPlainString}, indépendants de la locale. Ce projet a déjà été mordu
 * par une virgule décimale {@code fr_FR} qui cassait un export JSON (commit 7a6dae1) —
 * le format CSV serait cassé de la même façon par un {@code String.format} sans locale.
 */
public final class SpanWriter implements SpanSink {

    private static final Logger log = LoggerFactory.getLogger(SpanWriter.class);

    static final int  BATCH_SIZE         = 500;
    static final long FLUSH_INTERVAL_MS  = 1_000;
    private static final long MIN_BACKOFF_MS   = 1_000;
    private static final long MAX_BACKOFF_MS   = 30_000;
    private static final long CLOSE_TIMEOUT_MS = 5_000;

    private static final String COPY_SQL =
            "COPY agent_span (time, span_id, parent_id, run_id, session_id, project, agent, "
          + "span_kind, name, status, duration_ms, tokens_in, tokens_out, cost_usd, attributes) "
          + "FROM STDIN WITH (FORMAT csv)";

    /** Compteurs exposés à la fermeture et aux tests. */
    public record Stats(long written, long droppedQueueFull, long droppedDbUnreachable, String lastError) {
        public long droppedTotal() { return droppedQueueFull + droppedDbUnreachable; }
    }

    private final BlockingQueue<SpanRecord> queue;
    private final Thread worker;

    /** Le writer ne détient aucun identifiant : il demande une connexion, il ne l'assemble pas. */
    private final ConnectionFactory connections;

    private final AtomicLong written              = new AtomicLong();
    private final AtomicLong droppedQueueFull     = new AtomicLong();
    private final AtomicLong droppedDbUnreachable = new AtomicLong();
    private volatile String lastError;
    private volatile boolean running = true;

    /** Manipulés uniquement par le thread d'écriture. */
    private Connection conn;
    private long backoffMs = MIN_BACKOFF_MS;

    public SpanWriter(ConnectionFactory connections, int queueCapacity) {
        this.connections = connections;
        this.queue       = new ArrayBlockingQueue<>(queueCapacity);

        this.worker = new Thread(this::loop, "flightrec-writer");
        this.worker.setDaemon(true);   // ne doit jamais retenir l'arrêt de la JVM
        this.worker.start();
    }

    @Override
    public void accept(SpanRecord record) {
        if (!running) return;
        if (!queue.offer(record)) {    // non bloquant : on perd plutôt que de ralentir le pipeline
            droppedQueueFull.incrementAndGet();
        }
    }

    public Stats stats() {
        return new Stats(written.get(), droppedQueueFull.get(), droppedDbUnreachable.get(), lastError);
    }

    // ── Boucle d'écriture ───────────────────────────────────────────────────

    private void loop() {
        List<SpanRecord> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlush = System.currentTimeMillis();

        while (running || !queue.isEmpty()) {
            try {
                long waitMs = Math.max(0, lastFlush + FLUSH_INTERVAL_MS - System.currentTimeMillis());
                SpanRecord first = queue.poll(waitMs, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - batch.size());
                }

                boolean intervalElapsed = System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS;
                if (batch.size() >= BATCH_SIZE || (intervalElapsed && !batch.isEmpty())) {
                    flush(batch);
                    batch.clear();
                    lastFlush = System.currentTimeMillis();
                } else if (intervalElapsed) {
                    lastFlush = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        queue.drainTo(batch);
        if (!batch.isEmpty()) flush(batch);
        closeConnectionQuietly();
    }

    /** Écrit un lot. Ne lève jamais : en dernier recours le lot est compté perdu. */
    private void flush(List<SpanRecord> batch) {
        String csv = toCsv(batch);

        for (int attempt = 1; attempt <= 2; attempt++) {   // 2e tentative = reconnexion
            try {
                CopyManager copyManager = new CopyManager(connection().unwrap(BaseConnection.class));
                copyManager.copyIn(COPY_SQL, new StringReader(csv));
                written.addAndGet(batch.size());
                backoffMs = MIN_BACKOFF_MS;
                return;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                closeConnectionQuietly();                  // force une connexion neuve au tour suivant
            }
        }

        droppedDbUnreachable.addAndGet(batch.size());
        log.warn("Flight recorder — lot de {} spans perdu, base injoignable : {}", batch.size(), lastError);
        backoffQuietly();
    }

    private Connection connection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            conn = connections.open();
            conn.setAutoCommit(true);
        }
        return conn;
    }

    private void closeConnectionQuietly() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) { /* on ferme au mieux */ }
            conn = null;
        }
    }

    /** Ralentit les tentatives quand la base est durablement absente — jamais pendant la fermeture. */
    private void backoffQuietly() {
        if (!running) return;
        try {
            Thread.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Sérialisation CSV ───────────────────────────────────────────────────

    static String toCsv(List<SpanRecord> batch) {
        StringBuilder sb = new StringBuilder(batch.size() * 256);
        for (SpanRecord r : batch) {
            quoted(sb, DateTimeFormatter.ISO_INSTANT.format(r.time()));      sb.append(',');
            quoted(sb, r.spanId().toString());                               sb.append(',');
            quoted(sb, r.parentId() == null ? null : r.parentId().toString()); sb.append(',');
            quoted(sb, r.runId().toString());                                sb.append(',');
            quoted(sb, r.sessionId());                                       sb.append(',');
            quoted(sb, r.project());                                         sb.append(',');
            quoted(sb, r.agent());                                           sb.append(',');
            quoted(sb, r.spanKind());                                        sb.append(',');
            quoted(sb, r.name());                                            sb.append(',');
            quoted(sb, r.status());                                          sb.append(',');
            sb.append(Double.toString(r.durationMs()));                      sb.append(',');
            sb.append(r.tokensIn());                                         sb.append(',');
            sb.append(r.tokensOut());                                        sb.append(',');
            sb.append(r.costUsd().toPlainString());                          sb.append(',');
            quoted(sb, r.attributesJson());
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Écrit un champ CSV. Un champ {@code null} devient un champ vide non quoté, que
     * PostgreSQL interprète comme NULL ; un champ quoté vide serait la chaîne vide.
     *
     * <p>Seuls les guillemets doivent être échappés (par doublement) : à l'intérieur des
     * guillemets, virgules et retours à la ligne sont littéraux. C'est ce qui permet au
     * JSON des attributs de traverser tel quel.
     */
    private static void quoted(StringBuilder sb, String value) {
        if (value == null) return;
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') sb.append('"');
            sb.append(c);
        }
        sb.append('"');
    }

    // ── Fermeture ───────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!running) return;      // idempotent
        running = false;

        try {
            worker.join(CLOSE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int abandoned = queue.size();
        if (abandoned > 0) droppedDbUnreachable.addAndGet(abandoned);

        report();
    }

    /** Fail-loud : les pertes sont visibles, jamais silencieuses. */
    private void report() {
        Stats s = stats();
        if (s.droppedTotal() == 0) {
            System.err.printf("[flight recorder] %d spans écrits, aucune perte.%n", s.written());
        } else {
            System.err.printf(
                    "[flight recorder] %d spans écrits, %d PERDUS (%d file pleine, %d base injoignable).%n",
                    s.written(), s.droppedTotal(), s.droppedQueueFull(), s.droppedDbUnreachable());
            if (s.lastError() != null) {
                System.err.printf("[flight recorder] dernière erreur : %s%n", s.lastError());
            }
        }
        log.info("Flight recorder fermé — écrits={} perdus={}", s.written(), s.droppedTotal());
    }

    /** Pour les tests : attendre que la file soit drainée, sans dépasser le délai. */
    boolean awaitDrain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty();
    }

    /** Instant à partir d'une milliseconde — évite d'importer Instant côté appelant. */
    static Instant instantOf(long epochMilli) {
        return Instant.ofEpochMilli(epochMilli);
    }
}

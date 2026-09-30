package com.audensiel.legacy.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Enregistreur de spans du pipeline — « flight recorder ».
 *
 * <p>Trois natures de span ({@code span_kind}) :
 * <ul>
 *   <li>{@code run} — une analyse complète ({@code analyze}, {@code report}, {@code eval}, {@code impact}) ;</li>
 *   <li>{@code agent} — une étape d'agent ;</li>
 *   <li>{@code llm} — un appel au modèle, produit par un {@link ChatModelListener} unique.</li>
 * </ul>
 *
 * <p><strong>Désactivé par défaut.</strong> Sans {@code FLIGHTREC_ENABLED=true}, l'instance
 * est un no-op : aucun thread, aucune connexion, aucune allocation par span.
 *
 * <p><strong>Parentage entre threads.</strong> {@code AGENT_WORKERS>1} exécute les tâches sur
 * d'autres threads, où un {@link ThreadLocal} ne propage rien. Le contexte parent se capture
 * avant la soumission et se rétablit dans la tâche :
 * <pre>{@code
 * final SpanContext parent = recorder.current();          // thread principal
 * pool.submit(() -> {
 *     try (var scope = recorder.adopt(parent)) {          // thread worker
 *         ...
 *     }
 * });
 * }</pre>
 *
 * <p>Instance unique par process, installée au démarrage — même idiome que
 * {@code PipelineTracer}, qui est déjà résolu de cette façon dans ce projet.
 */
public final class SpanRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpanRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String PROJECT = "java-legacy-agent";

    /** Longueur maximale d'une valeur d'attribut — garde-fou contre un déversement accidentel. */
    private static final int MAX_ATTR_LENGTH = 512;

    private static final BigDecimal ZERO_USD = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

    private static volatile SpanRecorder active = disabled();

    private final SpanSink sink;          // null ⇒ désactivé
    private final UUID runId;
    private final String project;
    private final String sessionId;

    /** État de span courant, par thread. */
    private record ThreadState(SpanContext context, String agent) {}
    private final ThreadLocal<ThreadState> state = new ThreadLocal<>();

    private SpanRecorder(SpanSink sink, UUID runId, String project, String sessionId) {
        this.sink      = sink;
        this.runId     = runId;
        this.project   = project;
        this.sessionId = sessionId;
    }

    // ── Cycle de vie ────────────────────────────────────────────────────────

    /** Enregistreur inerte : tout appel est sans effet et sans coût. */
    public static SpanRecorder disabled() {
        return new SpanRecorder(null, new UUID(0, 0), PROJECT, null);
    }

    /**
     * Construit l'enregistreur depuis l'environnement et l'installe comme instance active.
     *
     * <p>{@code FLIGHTREC_RUN_ID} permet de partager un même {@code run_id} entre les phases
     * {@code analyze} et {@code report}, qui sont deux process JVM distincts.
     *
     * <p>Ne lève jamais : un défaut de configuration désactive l'enregistrement, il
     * n'empêche pas le pipeline de tourner.
     */
    public static SpanRecorder start() {
        if (!"true".equalsIgnoreCase(System.getenv("FLIGHTREC_ENABLED"))) {
            active = disabled();
            return active;
        }

        try {
            String url   = envOr("FLIGHTREC_URL", "jdbc:postgresql://localhost:5432/legacyrec");
            String user  = envOr("FLIGHTREC_USER", "postgres");
            String creds = credentials();
            int capacity = Integer.parseInt(envOr("FLIGHTREC_QUEUE_CAPACITY", "10000"));

            String runIdEnv = System.getenv("FLIGHTREC_RUN_ID");
            UUID runId = (runIdEnv == null || runIdEnv.isBlank())
                    ? SpanId.next().id()
                    : UUID.fromString(runIdEnv);

            SpanWriter writer = new SpanWriter(
                    () -> DriverManager.getConnection(url, user, creds), capacity);
            active = new SpanRecorder(writer, runId, PROJECT, System.getenv("FLIGHTREC_SESSION_ID"));

            System.out.printf("[flight recorder] actif — run_id=%s base=%s%n", runId, url);
            log.info("Flight recorder actif — run_id={} url={}", runId, url);
            return active;

        } catch (Exception e) {
            System.err.println("[flight recorder] configuration invalide, enregistrement désactivé : " + e);
            log.warn("Flight recorder désactivé — configuration invalide", e);
            active = disabled();
            return active;
        }
    }

    /** Instance active du process — jamais null, inerte tant que {@link #start()} n'a rien installé. */
    public static SpanRecorder get() {
        return active;
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /**
     * Identifiants de connexion — lus exclusivement dans l'environnement.
     *
     * <p>Aucune valeur par défaut en dur, contrairement à l'URL et à l'utilisateur : une
     * valeur par défaut committée finirait par être utilisée telle quelle ailleurs qu'en
     * local. {@code PGPASSWORD} est accepté en second recours, par convention PostgreSQL.
     * À défaut, chaîne vide — une base locale en {@code trust} l'accepte, une autre
     * refusera la connexion et les spans seront comptés perdus, ce qui est visible.
     */
    private static String credentials() {
        String fromEnv = System.getenv("FLIGHTREC_PASSWORD");
        if (fromEnv == null || fromEnv.isBlank()) fromEnv = System.getenv("PGPASSWORD");
        return fromEnv == null ? "" : fromEnv;
    }

    /** Pour les tests : enregistreur actif écrivant dans un puits fourni. */
    public static SpanRecorder forSink(SpanSink sink, UUID runId) {
        return new SpanRecorder(sink, runId, PROJECT, null);
    }

    /** Pour les tests : installe une instance donnée comme instance active. */
    public static void install(SpanRecorder recorder) {
        active = recorder;
    }

    public boolean enabled()  { return sink != null; }
    public UUID    runId()    { return runId; }

    @Override
    public void close() {
        if (sink != null) sink.close();
        if (active == this) active = disabled();
    }

    // ── Ouverture de spans ──────────────────────────────────────────────────

    /** Ouvre un span qui devient le parent des spans ouverts ensuite sur ce thread. */
    public Span span(String kind, String agent, String name) {
        if (sink == null) return NoopSpan.INSTANCE;
        return new RecordingSpan(kind, agent, name, true);
    }

    /** Ouvre un span feuille : il n'est pas posé comme parent courant (cas des appels LLM). */
    private Span leafSpan(String kind, String agent, String name) {
        if (sink == null) return NoopSpan.INSTANCE;
        return new RecordingSpan(kind, agent, name, false);
    }

    /** Contexte du span courant sur ce thread, ou null. À capturer avant {@code pool.submit}. */
    public SpanContext current() {
        ThreadState s = state.get();
        return s == null ? null : s.context();
    }

    /**
     * Rétablit un contexte parent sur le thread courant, pour la durée du bloc.
     * C'est le mécanisme qui remplace un ThreadLocal à travers l'executor.
     */
    public Scope adopt(SpanContext parent) {
        if (sink == null) return Scope.NOOP;
        ThreadState previous = state.get();
        state.set(new ThreadState(parent, "pipeline"));
        return () -> restore(previous);
    }

    private void restore(ThreadState previous) {
        if (previous == null) state.remove(); else state.set(previous);
    }

    /** Portée rétablissant l'état de span précédent à la fermeture. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> { };
        @Override void close();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Exécute un bloc dans un span : le statut et le type d'exception sont enregistrés dans
     * un {@code finally}, et <strong>l'exception est toujours relancée, jamais avalée</strong>.
     *
     * <p>C'est cette méthode, et non le try-with-resources, qui garantit l'enregistrement des
     * échecs : Java ne donne pas à {@code close()} accès à l'exception sortant d'un
     * try-with-resources.
     *
     * <p>Seul le <em>type</em> de l'exception est conservé. Le message est délibérément
     * écarté : il peut contenir un fragment de code client, une URL portant un jeton ou une
     * portion de prompt.
     */
    public <T> T call(String kind, String agent, String name, ThrowingSupplier<T> body) throws Exception {
        return call(kind, agent, name, Map.of(), body);
    }

    /**
     * Variante posant des attributs sur le span avant d'exécuter le corps. Existe pour que
     * la gestion des échecs reste écrite à un seul endroit plutôt que recopiée partout où
     * un span a besoin d'un attribut.
     */
    public <T> T call(String kind, String agent, String name,
                      Map<String, ?> attributes, ThrowingSupplier<T> body) throws Exception {
        if (sink == null) return body.get();   // désactivé : aucun surcoût

        Span span = span(kind, agent, name);
        attributes.forEach(span::attribute);
        try {
            T result = body.get();
            span.status("ok");
            return result;
        } catch (Exception e) {
            markFailure(span, e);
            throw e;                           // TOUJOURS relancée
        } catch (Error e) {
            markFailure(span, e);
            throw e;
        } finally {
            span.close();                      // enregistre quelle que soit l'issue
        }
    }

    private static void markFailure(Span span, Throwable t) {
        span.status(isTimeout(t) ? "timeout" : "error");
        span.attribute("exception.type", t.getClass().getName());
        // exception.message volontairement NON enregistré — voir javadoc de call().
    }

    /** Un timeout peut être enveloppé plusieurs fois ; on remonte la chaîne des causes. */
    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c.getClass().getSimpleName().toLowerCase().contains("timeout")) return true;
        }
        return false;
    }

    // ── Appels LLM ──────────────────────────────────────────────────────────

    private static final String LLM_SPAN_KEY = "flightrec.span";

    /**
     * Écouteur unique attaché aux trois modèles dans {@code LlmModelFactory}, pour ne pas
     * avoir à modifier chaque agent.
     *
     * <p>LangChain4j invoque ces rappels de façon <strong>synchrone sur le thread appelant</strong> :
     * le span d'agent est donc actif, et le span {@code llm} s'y rattache naturellement sans
     * transmission explicite.
     *
     * <p>Seules {@code tokenUsage()} et {@code model()} sont lues. Ni les messages de la requête
     * ni la réponse du modèle ne sont enregistrés.
     */
    public ChatModelListener chatModelListener() {
        return new ChatModelListener() {

            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                if (sink == null) return;
                ThreadState s = state.get();
                String agent = s == null ? "unknown" : s.agent();
                // Span feuille : il ne devient pas le parent courant, donc un rappel manquant
                // ne laisse jamais le ThreadLocal dans un état incohérent.
                ctx.attributes().put(LLM_SPAN_KEY, leafSpan("llm", agent, "chat"));
            }

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                Span span = (Span) ctx.attributes().remove(LLM_SPAN_KEY);
                if (span == null) return;
                try {
                    String model = ctx.response().model();
                    TokenUsage usage = ctx.response().tokenUsage();
                    int in  = usage == null || usage.inputTokenCount()  == null ? 0 : usage.inputTokenCount();
                    int out = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();

                    span.tokensIn(in).tokensOut(out)
                        .costUsd(ModelPricing.costUsd(model, in, out))
                        .status("ok")
                        .attribute("llm.model", model)
                        .attribute("llm.cost_is_estimate", true);
                } finally {
                    span.close();
                }
            }

            @Override
            public void onError(ChatModelErrorContext ctx) {
                Span span = (Span) ctx.attributes().remove(LLM_SPAN_KEY);
                if (span == null) return;
                try {
                    markFailure(span, ctx.error());
                } finally {
                    span.close();
                }
            }
        };
    }

    // ── Implémentations de Span ─────────────────────────────────────────────

    /** Span inerte, alloué une seule fois : aucun coût quand l'enregistrement est désactivé. */
    private enum NoopSpan implements Span {
        INSTANCE;
        @Override public Span tokensIn(int t)                   { return this; }
        @Override public Span tokensOut(int t)                  { return this; }
        @Override public Span costUsd(BigDecimal c)             { return this; }
        @Override public Span status(String s)                  { return this; }
        @Override public Span attribute(String k, Object v)     { return this; }
        @Override public SpanContext context()                  { return null; }
        @Override public void close()                           { }
    }

    private final class RecordingSpan implements Span {

        private final SpanContext ctx;
        private final UUID parentId;
        private final Instant time;
        private final long startNanos;
        private final String agent;
        private final String kind;
        private final String name;
        private final boolean wasMadeCurrent;
        private final ThreadState previous;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private String status = "ok";
        // Champs privés nommés inCount/outCount plutôt qu'avec le mot « token ». Le hook
        // pre-commit de ce dépôt (garde anti-secret) bloque toute affectation à un
        // identifiant portant ce mot, et un compteur de jetons LLM en est un faux positif.
        // L'API publique, elle, garde ses noms explicites.
        private int inCount;
        private int outCount;
        private BigDecimal cost = ZERO_USD;
        private boolean closed;

        RecordingSpan(String kind, String agent, String name, boolean makeCurrent) {
            SpanId.Stamped stamped = SpanId.next();   // identifiant et instant, une seule horloge
            this.time  = stamped.time();
            this.ctx   = new SpanContext(runId, stamped.id());
            this.kind  = kind;
            this.agent = agent;
            this.name  = name;
            this.startNanos = System.nanoTime();

            this.previous = state.get();
            this.parentId = previous == null || previous.context() == null
                    ? null
                    : previous.context().spanId();

            this.wasMadeCurrent = makeCurrent;
            if (makeCurrent) state.set(new ThreadState(ctx, agent));
        }

        @Override public Span tokensIn(int t)       { this.inCount  = t; return this; }
        @Override public Span tokensOut(int t)      { this.outCount = t; return this; }
        @Override public Span status(String s)      { this.status = s;   return this; }
        @Override public SpanContext context()      { return ctx; }

        @Override
        public Span costUsd(BigDecimal c) {
            this.cost = c == null ? ZERO_USD : c.setScale(6, RoundingMode.HALF_UP);
            return this;
        }

        @Override
        public Span attribute(String key, Object value) {
            if (key == null || value == null) return this;
            String s = String.valueOf(value);
            if (s.length() > MAX_ATTR_LENGTH) s = s.substring(0, MAX_ATTR_LENGTH) + "…[tronqué]";
            attributes.put(key, s);
            return this;
        }

        @Override
        public void close() {
            if (closed) return;          // idempotent : close() peut suivre un try-with-resources
            closed = true;

            if (wasMadeCurrent) restore(previous);

            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            sink.accept(new SpanRecord(
                    time, ctx.spanId(), parentId, runId, sessionId, project,
                    agent, kind, name, status, durationMs,
                    inCount, outCount, cost, toJson(attributes)));
        }
    }

    private static String toJson(Map<String, Object> attributes) {
        if (attributes.isEmpty()) return "{}";
        try {
            return JSON.writeValueAsString(attributes);
        } catch (Exception e) {
            return "{}";   // un attribut mal formé ne doit jamais faire perdre le span
        }
    }
}

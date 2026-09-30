package com.audensiel.legacy.agent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AGENT_WORKERS=4} exécute les analyses de classe sur d'autres threads
 * ({@code LegacyMigrationOrchestrator}, un unique {@code pool.submit}). Un
 * {@link ThreadLocal} ne franchit pas cette frontière : le contexte parent doit être
 * capturé avant la soumission et rétabli dans la tâche.
 *
 * <p>Ce test rejoue exactement ce montage, sans base de données. C'est lui qui échoue si
 * quelqu'un remplace le passage explicite par un simple ThreadLocal — un défaut qui, en
 * production, ne casserait rien de visible : les spans continueraient d'être écrits, mais
 * l'arbre de replay serait plat et les agrégats par run silencieusement faux.
 */
class SpanParentPropagationTest {

    private static final int WORKERS = 4;
    private static final int CLASSES = 40;

    @Test
    @DisplayName("chaque span d'agent créé dans un worker a le span run pour parent")
    void chaqueSpanAgentAPourParentLeSpanRun() throws Exception {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = SpanRecorder.forSink(sink, UUID.randomUUID());
        Set<String> threadsUtilises = ConcurrentHashMap.newKeySet();

        UUID runSpanId;
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            try (Span runSpan = recorder.span("run", "LegacyMigrationOrchestrator", "analyze")) {
                runSpanId = runSpan.context().spanId();

                // Capture AVANT la soumission — le geste même que le test protège.
                final SpanContext parentCapture = recorder.current();

                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < CLASSES; i++) {
                    futures.add(pool.submit(() -> {
                        threadsUtilises.add(Thread.currentThread().getName());
                        try (SpanRecorder.Scope scope = recorder.adopt(parentCapture)) {
                            return recorder.call("agent", "AstParserAgent", "ast", () -> "analysé");
                        }
                    }));
                }
                for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertTrue(threadsUtilises.size() > 1,
                "le test n'a de sens que si plusieurs threads ont réellement travaillé, observé : " + threadsUtilises);

        List<SpanRecord> spansAgent = sink.records().stream()
                .filter(r -> "agent".equals(r.spanKind()))
                .toList();

        assertEquals(CLASSES, spansAgent.size(), "chaque classe doit produire un span d'agent");
        for (SpanRecord span : spansAgent) {
            assertNotNull(span.parentId(),
                    "un span d'agent sans parent signifie que le contexte n'a pas traversé l'executor");
            assertEquals(runSpanId, span.parentId(),
                    "le parent doit être le span run, pas un span d'un autre worker");
        }
    }

    @Test
    @DisplayName("sans adopt, le parent serait perdu — c'est ce que le passage explicite corrige")
    void sansAdoptLeParentEstPerdu() throws Exception {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = SpanRecorder.forSink(sink, UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            try (Span runSpan = recorder.span("run", "LegacyMigrationOrchestrator", "analyze")) {
                assertNotNull(runSpan.context());
                // Soumission SANS adopt : le thread worker n'a aucun état de span.
                pool.submit(() -> recorder.call("agent", "AstParserAgent", "ast", () -> "analysé"))
                    .get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        SpanRecord spanAgent = sink.records().stream()
                .filter(r -> "agent".equals(r.spanKind()))
                .findFirst()
                .orElseThrow();

        assertNull(spanAgent.parentId(),
                "sans transmission explicite le parent est nul — d'où le recorder.adopt() de l'orchestrateur");
    }

    @Test
    @DisplayName("un appel LLM dans un worker se rattache au span d'agent, pas au span run")
    void spanLlmSeRattacheAuSpanAgent() throws Exception {
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = SpanRecorder.forSink(sink, UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            try (Span runSpan = recorder.span("run", "LegacyMigrationOrchestrator", "analyze")) {
                final SpanContext parentCapture = recorder.current();
                pool.submit(() -> {
                    try (SpanRecorder.Scope scope = recorder.adopt(parentCapture)) {
                        return recorder.call("agent", "JavaDocumentationAgent", "analyze", () -> {
                            // LangChain4j invoque ses écouteurs sur le thread appelant :
                            // le span d'agent est actif, le span llm s'y rattache seul.
                            try (Span llm = recorder.span("llm", "JavaDocumentationAgent", "chat")) {
                                llm.tokensIn(120).tokensOut(340);
                            }
                            return "specs";
                        });
                    }
                }).get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        List<SpanRecord> records = sink.records();
        SpanRecord spanAgent = records.stream().filter(r -> "agent".equals(r.spanKind())).findFirst().orElseThrow();
        SpanRecord spanLlm   = records.stream().filter(r -> "llm".equals(r.spanKind())).findFirst().orElseThrow();

        assertEquals(spanAgent.spanId(), spanLlm.parentId(),
                "le span llm doit avoir le span d'agent pour parent, pas le span run");
        assertEquals(120, spanLlm.tokensIn());
        assertEquals(340, spanLlm.tokensOut());
    }

    @Test
    @DisplayName("tous les spans d'un run partagent le même run_id")
    void memeRunIdPourTousLesSpans() throws Exception {
        UUID runId = SpanId.next().id();
        RecordingSink sink = new RecordingSink();
        SpanRecorder recorder = SpanRecorder.forSink(sink, runId);

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            try (Span runSpan = recorder.span("run", "LegacyMigrationOrchestrator", "analyze")) {
                final SpanContext parentCapture = recorder.current();
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < CLASSES; i++) {
                    futures.add(pool.submit(() -> {
                        try (SpanRecorder.Scope scope = recorder.adopt(parentCapture)) {
                            return recorder.call("agent", "AstParserAgent", "ast", () -> "analysé");
                        }
                    }));
                }
                for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertTrue(sink.records().stream().allMatch(r -> runId.equals(r.runId())),
                "le run_id doit être constant, c'est lui qui regroupe l'arbre de replay");
    }
}

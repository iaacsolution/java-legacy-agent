package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Collecte et exporte les métriques d'exécution du pipeline.
 * Produit un fichier metrics.json + pousse vers Prometheus Pushgateway.
 */
public class RunMetrics {

    private static final Logger log = LoggerFactory.getLogger(RunMetrics.class);

    public enum Status { OK, FAILED, SKIPPED }

    public record StepMetric(
            String className,
            String step,
            long durationMs,
            Status status,
            String errorMessage
    ) {}

    // Résumé structuré exposé au MetricsPusher
    public record RunSummary(
            long totalMs,
            int filesTotal,
            int filesSuccess,
            int filesFailed,
            double successRatePct,
            long stepsFailedTotal,
            long stepAvgMs,
            long stepMaxMs,
            Map<String, Long> stepDurations   // step name → durée ms (dernière occurrence)
    ) {}

    private final String projectName;
    private final Instant startTime = Instant.now();
    private final List<StepMetric> steps = new ArrayList<>();
    private int filesTotal;
    private int filesSuccess;
    private int filesFailed;

    public RunMetrics(String projectName) {
        this.projectName = projectName;
    }

    // ── Enregistre une étape avec timing ────────────────────────────────────
    public <T> T track(String className, String stepName, StepSupplier<T> supplier) {
        long start = System.currentTimeMillis();
        MDC.put("className", className);
        MDC.put("step", stepName);
        MDC.put("project", projectName);

        try {
            T result = supplier.get();
            long duration = System.currentTimeMillis() - start;
            steps.add(new StepMetric(className, stepName, duration, Status.OK, null));
            MDC.put("durationMs", String.valueOf(duration));
            MDC.put("status", "OK");
            log.info("Étape réussie : {} / {}", className, stepName);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            steps.add(new StepMetric(className, stepName, duration, Status.FAILED, e.getMessage()));
            MDC.put("durationMs", String.valueOf(duration));
            MDC.put("status", "FAILED");
            log.error("Étape échouée : {} / {} — {}", className, stepName, e.getMessage());
            throw new RuntimeException("Échec étape [" + stepName + "] sur " + className, e);
        } finally {
            MDC.clear();
        }
    }

    public void recordFile(boolean success) {
        filesTotal++;
        if (success) filesSuccess++; else filesFailed++;
    }

    // ── Construit le résumé pour le Pusher ──────────────────────────────────
    public RunSummary buildSummary() {
        long totalMs     = Instant.now().toEpochMilli() - startTime.toEpochMilli();
        long avgMs       = steps.isEmpty() ? 0 : steps.stream().mapToLong(StepMetric::durationMs).sum() / steps.size();
        long maxMs       = steps.stream().mapToLong(StepMetric::durationMs).max().orElse(0);
        long failedSteps = steps.stream().filter(s -> s.status() == Status.FAILED).count();
        double rate      = filesTotal > 0 ? (double) filesSuccess / filesTotal * 100 : 0;

        // Durée par nom d'étape (moyenne si plusieurs fichiers)
        Map<String, List<Long>> byStep = new LinkedHashMap<>();
        for (StepMetric s : steps) {
            byStep.computeIfAbsent(s.step(), k -> new ArrayList<>()).add(s.durationMs());
        }
        Map<String, Long> stepDurations = new LinkedHashMap<>();
        byStep.forEach((step, durations) ->
                stepDurations.put(step, (long) durations.stream().mapToLong(Long::longValue).average().orElse(0)));

        return new RunSummary(totalMs, filesTotal, filesSuccess, filesFailed,
                rate, failedSteps, avgMs, maxMs, stepDurations);
    }

    // ── Export JSON ──────────────────────────────────────────────────────────
    public void exportJson(Path outputDir) throws IOException {
        RunSummary s = buildSummary();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"project\": \"").append(projectName).append("\",\n");
        json.append("  \"timestamp\": \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        json.append("  \"duration_total_ms\": ").append(s.totalMs()).append(",\n");
        json.append("  \"files_total\": ").append(s.filesTotal()).append(",\n");
        json.append("  \"files_success\": ").append(s.filesSuccess()).append(",\n");
        json.append("  \"files_failed\": ").append(s.filesFailed()).append(",\n");
        json.append("  \"success_rate_pct\": ").append(String.format("%.1f", s.successRatePct())).append(",\n");
        json.append("  \"steps\": [\n");

        for (int i = 0; i < steps.size(); i++) {
            StepMetric sm = steps.get(i);
            json.append("    {");
            json.append("\"class\": \"").append(sm.className()).append("\", ");
            json.append("\"step\": \"").append(sm.step()).append("\", ");
            json.append("\"duration_ms\": ").append(sm.durationMs()).append(", ");
            json.append("\"status\": \"").append(sm.status()).append("\"");
            if (sm.errorMessage() != null)
                json.append(", \"error\": \"").append(sm.errorMessage().replace("\"", "'")).append("\"");
            json.append("}");
            if (i < steps.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n}");

        Files.createDirectories(outputDir);
        Path metricsFile = outputDir.resolve("metrics_" + projectName + ".json");
        Files.writeString(metricsFile, json.toString());
        log.info("Métriques exportées : {}", metricsFile);
        System.out.println("📊 Métriques JSON : " + metricsFile);
    }

    // ── Résumé console ───────────────────────────────────────────────────────
    public void printSummary() {
        RunSummary s = buildSummary();
        System.out.println("\n📊 MÉTRIQUES D'EXÉCUTION");
        System.out.println("─".repeat(45));
        System.out.printf("  Durée totale         : %d ms%n", s.totalMs());
        System.out.printf("  Fichiers traités     : %d / %d%n", s.filesSuccess(), s.filesTotal());
        System.out.printf("  Taux de succès       : %.1f%%%n", s.successRatePct());
        System.out.printf("  Étapes échouées      : %d%n", s.stepsFailedTotal());
        System.out.printf("  Durée moy. par étape : %d ms%n", s.stepAvgMs());
        System.out.printf("  Étape la plus longue : %d ms%n", s.stepMaxMs());
    }

    @FunctionalInterface
    public interface StepSupplier<T> {
        T get() throws Exception;
    }
}

package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Pousse les métriques du pipeline vers Prometheus Pushgateway.
 *
 * Format Prometheus text :
 *   metric_name{label="val"} value
 *
 * Endpoint : PUT http://pushgateway:9091/metrics/job/java-legacy-agent/instance/<project>
 */
public class MetricsPusher {

    private static final Logger log = LoggerFactory.getLogger(MetricsPusher.class);

    private final String pushgatewayUrl;
    private final HttpClient http;

    public MetricsPusher(String pushgatewayUrl) {
        this.pushgatewayUrl = pushgatewayUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Pousse toutes les métriques d'un run vers le Pushgateway.
     */
    public void push(String projectName, RunMetrics.RunSummary summary) {
        String body = buildPayload(projectName, summary);
        String url  = pushgatewayUrl + "/metrics/job/java-legacy-agent/instance/" + sanitize(projectName);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 202) {
                log.info("Métriques poussées vers Pushgateway — projet={}", projectName);
                System.out.println("📡 Métriques envoyées à Prometheus Pushgateway");
            } else {
                log.warn("Pushgateway a retourné HTTP {} pour {}", response.statusCode(), projectName);
            }
        } catch (Exception e) {
            // Non bloquant : l'observabilité ne doit pas faire planter le pipeline
            log.warn("Impossible de joindre le Pushgateway : {}", e.getMessage());
            System.out.println("⚠️  Pushgateway non disponible (métriques dans le fichier JSON)");
        }
    }

    private String buildPayload(String project, RunMetrics.RunSummary s) {
        StringBuilder sb = new StringBuilder();
        String lbl = "project=\"" + sanitize(project) + "\"";

        metric(sb, "agent_run_duration_ms",    "Durée totale du run en ms",              lbl, s.totalMs());
        metric(sb, "agent_files_total",        "Nombre total de fichiers analysés",      lbl, s.filesTotal());
        metric(sb, "agent_files_success",      "Fichiers traités avec succès",           lbl, s.filesSuccess());
        metric(sb, "agent_files_failed",       "Fichiers en erreur",                     lbl, s.filesFailed());
        metric(sb, "agent_success_rate_pct",   "Taux de succès en %",                    lbl, s.successRatePct());
        metric(sb, "agent_steps_failed_total", "Nombre d'étapes échouées",               lbl, s.stepsFailedTotal());
        metric(sb, "agent_step_avg_ms",        "Durée moyenne par étape en ms",          lbl, s.stepAvgMs());
        metric(sb, "agent_step_max_ms",        "Durée max d'une étape en ms",            lbl, s.stepMaxMs());

        // Durée par étape — # HELP et # TYPE une seule fois pour ce nom de métrique
        if (!s.stepDurations().isEmpty()) {
            sb.append("# HELP agent_step_duration_ms Duree par etape en ms\n");
            sb.append("# TYPE agent_step_duration_ms gauge\n");
            for (var entry : s.stepDurations().entrySet()) {
                String stepLbl = lbl + ",step=\"" + sanitize(entry.getKey()) + "\"";
                sb.append("agent_step_duration_ms{").append(stepLbl).append("} ")
                  .append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    private void metric(StringBuilder sb, String name, String help, String labels, double value) {
        // Supprime les accents pour rester en ASCII pur (exigé par le format Prometheus)
        String safeHelp = help.replaceAll("[^\\x00-\\x7F]", "?");
        sb.append("# HELP ").append(name).append(" ").append(safeHelp).append("\n");
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append("{").append(labels).append("} ").append(value).append("\n");
    }

    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}

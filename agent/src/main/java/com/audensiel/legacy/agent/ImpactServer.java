package com.audensiel.legacy.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serveur HTTP minimal exposant BreakingChangeDetector — point d'intégration pour
 * jira-java-router (JAVA_AGENT_URL), qui appelle POST /impact depuis son endpoint
 * /impact pour enrichir un commentaire Jira avec l'analyse de changement cassant.
 *
 * Volontairement sans framework (pas de Spring/Javalin) : com.sun.net.httpserver
 * est fourni par le JDK, aucune nouvelle dépendance. Le corps JSON de la requête
 * est à 2 champs plats — parsing par regex plutôt qu'ajouter Jackson comme
 * dépendance directe (déjà présent transitivement, mais pas déclaré).
 */
public class ImpactServer {

    private static final Logger log = LoggerFactory.getLogger(ImpactServer.class);
    private static final Pattern CLASS_NAME_FIELD  = Pattern.compile("\"class_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_NAME_FIELD = Pattern.compile("\"method_name\"\\s*:\\s*\"([^\"]*)\"");

    public void start(Path projectRoot, int port) throws IOException {
        BreakingChangeDetector detector = new BreakingChangeDetector();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.createContext("/impact", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String className   = extractField(CLASS_NAME_FIELD, requestBody);
                String methodName  = extractField(METHOD_NAME_FIELD, requestBody);

                if (className == null || methodName == null) {
                    sendJson(exchange, 400, "{\"error\":\"class_name et method_name requis\"}");
                    return;
                }

                log.info("Requête impact reçue — {}.{}()", className, methodName);
                BreakingChangeDetector.BreakingChangeReport report =
                        detector.analyze(projectRoot, className, methodName);
                sendJson(exchange, 200, report.toJson());
            } catch (Exception e) {
                log.error("Échec traitement /impact", e);
                sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        server.setExecutor(null); // executor par défaut (mono-thread, suffisant — analyse < 200ms)
        server.start();

        System.out.println("🌐 Serveur d'impact démarré sur http://0.0.0.0:" + port);
        System.out.println("   Projet scanné : " + projectRoot);
        System.out.println("   Endpoints : POST /impact {class_name, method_name} · GET /health");
        log.info("ImpactServer démarré sur le port {} pour le projet {}", port, projectRoot);
    }

    private String extractField(Pattern pattern, String json) {
        Matcher m = pattern.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
    }
}

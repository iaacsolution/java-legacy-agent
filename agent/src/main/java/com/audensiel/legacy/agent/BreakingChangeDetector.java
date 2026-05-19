package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Détecte les changements cassants sur une signature de méthode.
 *
 * Workflow :
 *   1. Scanner le projet via CallGraphAgent → call graph
 *   2. Pour la méthode modifiée, récupérer tous les callers
 *   3. Retourner un rapport d'impact avec sévérité
 *
 * Intégration CI/CD : appelé par le hook pre-commit ou le webhook Jira.
 */
public class BreakingChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(BreakingChangeDetector.class);

    public enum Severity { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    public record BreakingChangeReport(
            String className,
            String methodName,
            List<String> callers,
            Severity severity,
            String recommendation
    ) {
        public boolean isBreaking() { return severity != Severity.NONE; }

        public String toConsoleReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append("=".repeat(60)).append("\n");
            sb.append("  BREAKING CHANGE ANALYSIS\n");
            sb.append("  Méthode : ").append(className).append(".").append(methodName).append("()\n");
            sb.append("  Sévérité : ").append(severity).append("\n");
            sb.append("=".repeat(60)).append("\n");

            if (callers.isEmpty()) {
                sb.append("  Aucun appel entrant détecté — changement sûr.\n");
            } else {
                sb.append("  ").append(callers.size()).append(" appel(s) entrant(s) :\n");
                callers.forEach(c -> sb.append("    → ").append(c).append("\n"));
                sb.append("\n  Recommandation : ").append(recommendation).append("\n");
            }
            sb.append("=".repeat(60)).append("\n");
            return sb.toString();
        }

        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"class\": \"").append(className).append("\",\n");
            json.append("  \"method\": \"").append(methodName).append("\",\n");
            json.append("  \"severity\": \"").append(severity).append("\",\n");
            json.append("  \"callers_count\": ").append(callers.size()).append(",\n");
            json.append("  \"callers\": [\n");
            for (int i = 0; i < callers.size(); i++) {
                json.append("    \"").append(callers.get(i)).append("\"");
                if (i < callers.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  ],\n");
            json.append("  \"recommendation\": \"").append(recommendation).append("\",\n");
            json.append("  \"breaking\": ").append(isBreaking()).append("\n");
            json.append("}");
            return json.toString();
        }
    }

    private final CallGraphAgent callGraphAgent = new CallGraphAgent();

    public BreakingChangeReport analyze(Path projectRoot, String className, String methodName) {
        log.info("Analyse impact — {}.{}() dans {}", className, methodName, projectRoot);

        CallGraphAgent.CallGraph graph;
        try {
            graph = callGraphAgent.buildCallGraph(projectRoot);
        } catch (Exception e) {
            log.error("Échec scan AST : {}", e.getMessage());
            return new BreakingChangeReport(className, methodName,
                    List.of(), Severity.NONE, "Scan AST échoué — vérifiez le chemin du projet");
        }

        List<String> callers = graph.callersOf(className, methodName);
        Severity severity    = computeSeverity(callers);
        String recommendation = buildRecommendation(callers, severity);

        log.info("Résultat : {} callers, sévérité={}", callers.size(), severity);
        return new BreakingChangeReport(className, methodName, callers, severity, recommendation);
    }

    private Severity computeSeverity(List<String> callers) {
        if (callers.isEmpty())  return Severity.NONE;
        if (callers.size() <= 2) return Severity.LOW;
        if (callers.size() <= 5) return Severity.MEDIUM;
        if (callers.size() <= 10) return Severity.HIGH;
        return Severity.CRITICAL;
    }

    private String buildRecommendation(List<String> callers, Severity severity) {
        return switch (severity) {
            case NONE     -> "Changement sûr — aucun appelant détecté.";
            case LOW      -> "Mettre à jour " + callers.size() + " appelant(s) avant le commit.";
            case MEDIUM   -> "Refactoring requis sur " + callers.size() + " sites. Envisager une méthode dépréciée @Deprecated en transition.";
            case HIGH     -> "Impact élevé (" + callers.size() + " callers). Créer une nouvelle signature, garder l'ancienne @Deprecated.";
            case CRITICAL -> "BLOQUANT — " + callers.size() + " appelants. Ne pas merger sans plan de migration complet.";
        };
    }
}

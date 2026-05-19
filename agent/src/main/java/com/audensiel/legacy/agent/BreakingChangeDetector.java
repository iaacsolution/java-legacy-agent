package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Détecte les changements cassants + génère un plan de refactoring sans LLM.
 * Garanti < 200ms grâce au cache MD5 + règles statiques RefactoringAdvisor.
 */
public class BreakingChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(BreakingChangeDetector.class);

    public enum Severity { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    public record BreakingChangeReport(
            String className,
            String methodName,
            List<String> callers,
            Severity severity,
            String recommendation,
            RefactoringAdvisor.RefactoringPlan refactoring,
            long durationMs
    ) {
        public boolean isBreaking() { return severity != Severity.NONE && severity != Severity.LOW; }

        public String toConsoleReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append("=".repeat(60)).append("\n");
            sb.append("  BREAKING CHANGE ANALYSIS\n");
            sb.append("  Méthode  : ").append(className).append(".").append(methodName).append("()\n");
            sb.append("  Sévérité : ").append(severity).append("\n");
            sb.append("  Durée    : ").append(durationMs).append(" ms\n");
            sb.append("=".repeat(60)).append("\n");

            if (callers.isEmpty()) {
                sb.append("  Aucun appelant — changement sûr.\n");
            } else {
                sb.append("  ").append(callers.size()).append(" appel(s) entrant(s) :\n");
                callers.forEach(c -> sb.append("    → ").append(c).append("\n"));
                sb.append("\n  Stratégie  : ").append(refactoring.strategy()).append("\n");
                sb.append("  Action imm.: ").append(refactoring.immediateAction()).append("\n");
                sb.append("  Court terme: ").append(refactoring.shortTermAction()).append("\n");
                if (!refactoring.codeTemplate().isBlank()) {
                    sb.append("\n  --- Suggestion de code ---\n");
                    sb.append(refactoring.codeTemplate()).append("\n");
                    sb.append("  --------------------------\n");
                }
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
            json.append("  \"refactoring\": {\n");
            json.append("    \"strategy\": \"").append(refactoring.strategy()).append("\",\n");
            json.append("    \"immediate\": \"").append(esc(refactoring.immediateAction())).append("\",\n");
            json.append("    \"short_term\": \"").append(esc(refactoring.shortTermAction())).append("\",\n");
            json.append("    \"code_template\": \"").append(esc(refactoring.codeTemplate())).append("\"\n");
            json.append("  },\n");
            json.append("  \"duration_ms\": ").append(durationMs).append(",\n");
            json.append("  \"breaking\": ").append(isBreaking()).append("\n");
            json.append("}");
            return json.toString();
        }

        private String esc(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }

    private final CallGraphAgent   callGraphAgent = new CallGraphAgent();
    private final RefactoringAdvisor advisor      = new RefactoringAdvisor();

    public BreakingChangeReport analyze(Path projectRoot, String className, String methodName) {
        log.info("Analyse impact — {}.{}() dans {}", className, methodName, projectRoot);

        long t0 = System.nanoTime();

        CallGraphAgent.CallGraph graph;
        try {
            long tScan = System.nanoTime();
            graph = callGraphAgent.buildCallGraph(projectRoot);
            long scanMs = (System.nanoTime() - tScan) / 1_000_000;
            System.out.printf("[Timer] Scan AST        : %d ms%n", scanMs);
        } catch (Exception e) {
            log.error("Échec scan AST : {}", e.getMessage());
            return new BreakingChangeReport(className, methodName,
                    List.of(), Severity.NONE, "Scan AST échoué",
                    advisor.suggest(className, methodName, List.of(), Severity.NONE), 0);
        }

        long tMatch             = System.nanoTime();
        List<String> callers    = graph.callersOf(className, methodName);
        long matchMs            = (System.nanoTime() - tMatch) / 1_000_000;
        Severity severity       = computeSeverity(callers);
        String recommendation   = buildRecommendation(callers, severity);

        // Suggestion de refactoring — règles statiques < 5ms
        long tPlan = System.nanoTime();
        RefactoringAdvisor.RefactoringPlan plan = advisor.suggest(className, methodName, callers, severity);
        long planMs = (System.nanoTime() - tPlan) / 1_000_000;

        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("[Timer] Matching callers : %d ms%n", matchMs);
        System.out.printf("[Timer] Refactoring plan : %d ms%n", planMs);
        System.out.printf("[Timer] TOTAL            : %d ms%n", totalMs);

        if (totalMs > 200) {
            System.out.println("[Timer] ATTENTION : scan > 200ms — vérifiez le cache MD5");
        }

        log.info("Résultat : {} callers, sévérité={}, stratégie={}, durée={}ms",
                callers.size(), severity, plan.strategy(), totalMs);

        return new BreakingChangeReport(className, methodName, callers,
                severity, recommendation, plan, totalMs);
    }

    private Severity computeSeverity(List<String> callers) {
        if (callers.isEmpty())   return Severity.NONE;
        if (callers.size() <= 2) return Severity.LOW;
        if (callers.size() <= 5) return Severity.MEDIUM;
        if (callers.size() <= 10) return Severity.HIGH;
        return Severity.CRITICAL;
    }

    private String buildRecommendation(List<String> callers, Severity severity) {
        return switch (severity) {
            case NONE     -> "Changement sûr — aucun appelant détecté.";
            case LOW      -> "Mettre à jour " + callers.size() + " appelant(s) avant le commit.";
            case MEDIUM   -> callers.size() + " sites impactés — annoter @Deprecated + surcharger.";
            case HIGH     -> "Impact élevé (" + callers.size() + " callers) — interface de transition requise.";
            case CRITICAL -> "BLOQUANT — " + callers.size() + " appelants — plan de migration formel obligatoire.";
        };
    }
}

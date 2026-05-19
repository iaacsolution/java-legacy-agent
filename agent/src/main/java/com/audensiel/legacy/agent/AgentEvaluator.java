package com.audensiel.legacy.agent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Évaluateur F1 pour les agents de migration.
 *
 * Deux types de mesure :
 *  - DependencyMapperAgent : F1 exact sur les ensembles extraits vs ground truth
 *  - CodeAnalyzerAgent     : F1 par rappel de mots-clés dans la sortie LLM
 */
public class AgentEvaluator {

    // ── Résultat d'évaluation ─────────────────────────────────────────────────
    public record EvalResult(double precision, double recall, double f1, int tp, int fp, int fn) {
        @Override
        public String toString() {
            return String.format("Precision=%.3f  Recall=%.3f  F1=%.3f  (TP=%d FP=%d FN=%d)",
                    precision, recall, f1, tp, fp, fn);
        }
    }

    // ── Cas de test ──────────────────────────────────────────────────────────
    public record TestCase(
            String name,
            String javaCode,
            Set<String> expectedDependencies,   // pour DependencyMapper
            Set<String> expectedRiskKeywords,   // pour CodeAnalyzer (LLM)
            Set<String> expectedResponsibilities
    ) {}

    // ── F1 exact (DependencyMapper) ──────────────────────────────────────────
    public EvalResult evaluateExact(Set<String> predicted, Set<String> groundTruth) {
        Set<String> pred  = normalize(predicted);
        Set<String> truth = normalize(groundTruth);

        int tp = (int) pred.stream().filter(truth::contains).count();
        int fp = pred.size() - tp;
        int fn = (int) truth.stream().filter(s -> !pred.contains(s)).count();

        double precision = tp + fp > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall    = tp + fn > 0 ? (double) tp / (tp + fn) : 0.0;
        double f1        = precision + recall > 0
                ? 2.0 * precision * recall / (precision + recall) : 0.0;

        return new EvalResult(precision, recall, f1, tp, fp, fn);
    }

    // ── F1 par mots-clés (CodeAnalyzer / LLM) ───────────────────────────────
    // On mesure le rappel des concepts attendus dans la sortie libre du LLM.
    public EvalResult evaluateKeywords(String llmOutput, Set<String> expectedKeywords) {
        String lower = llmOutput.toLowerCase();

        Set<String> found    = expectedKeywords.stream()
                .filter(k -> lower.contains(k.toLowerCase()))
                .collect(Collectors.toSet());
        Set<String> missing  = expectedKeywords.stream()
                .filter(k -> !lower.contains(k.toLowerCase()))
                .collect(Collectors.toSet());

        int tp = found.size();
        int fn = missing.size();

        // Precision = 1.0 (on ne sait pas compter les FP dans du texte libre)
        double precision = 1.0;
        double recall    = tp + fn > 0 ? (double) tp / (tp + fn) : 0.0;
        double f1        = 2.0 * precision * recall / (precision + recall + 1e-9);

        return new EvalResult(precision, recall, f1, tp, 0, fn);
    }

    // ── Normalisation ────────────────────────────────────────────────────────
    private Set<String> normalize(Set<String> set) {
        return set.stream()
                .map(s -> s.toLowerCase().trim())
                .collect(Collectors.toSet());
    }

    // ── Affichage d'un rapport ───────────────────────────────────────────────
    public static void printReport(String label, EvalResult result) {
        System.out.printf("  %-35s %s%n", label + " :", result);
    }
}

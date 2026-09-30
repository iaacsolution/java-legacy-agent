package com.audensiel.legacy.agent;

import com.audensiel.legacy.agent.observability.SpanRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Runner d'évaluation F1 — mesure la qualité des agents sur des cas de test annotés.
 *
 * Cas de test chargés depuis golden_dataset.json (racine du projet), pas codés en dur —
 * voir README.md, section "Golden dataset", pour les limites connues de ce dataset.
 *
 * Usage : java -cp app.jar com.audensiel.legacy.agent.EvalMain [chemin_dataset.json]
 */
public class EvalMain {

    private static final String DEFAULT_DATASET_PATH = "golden_dataset.json";

    // ── Forme JSON d'un cas de test (désérialisée par Jackson) ─────────────────
    record GoldenCase(
            String name,
            String description,
            String javaCode,
            List<String> expectedDependencies,
            List<String> expectedRiskKeywords,
            List<String> expectedResponsibilities
    ) {}

    public static void main(String[] args) throws IOException {
        String ollamaUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        Path datasetPath = Path.of(args.length >= 1 ? args[0] : DEFAULT_DATASET_PATH);

        System.out.println("═".repeat(65));
        System.out.println("  ÉVALUATION F1 — Java Legacy Migration Agents");
        System.out.println("  Dataset : " + datasetPath.toAbsolutePath());
        System.out.println("═".repeat(65));

        List<GoldenCase> cases = loadDataset(datasetPath);
        System.out.printf("  %d cas de test chargés%n", cases.size());

        AgentEvaluator evaluator = new AgentEvaluator();
        DependencyMapperAgent depMapper = new DependencyMapperAgent();
        JavaDocumentationAgent docAgent = new JavaDocumentationAgent(ollamaUrl);

        List<double[]> f1Scores = new ArrayList<>();

        // Racine « eval ». DependencyMapperAgent et AgentEvaluator ne sont instanciés que
        // dans ce mode : sans cette racine, ces deux agents n'apparaîtraient dans aucun span.
        SpanRecorder recorder = SpanRecorder.get();
        RunMetrics metrics = new RunMetrics("golden-dataset", recorder);

        try (var runSpan = recorder.span("run", "AgentEvaluator", "eval")) {
            runSpan.attribute("dataset", datasetPath.getFileName().toString())
                   .attribute("cases", cases.size());

            for (GoldenCase gc : cases) {
                System.out.println("\n━━━ " + gc.name() + " : " + gc.description() + " ━━━");
                f1Scores.add(runTestCase(evaluator, depMapper, docAgent, gc, metrics));
            }
        }

        // ── Score global ──────────────────────────────────────────
        System.out.println("\n" + "═".repeat(65));
        System.out.println("  SCORES GLOBAUX");
        System.out.println("─".repeat(65));

        double avgDepF1  = f1Scores.stream().mapToDouble(a -> a[0]).average().orElse(0);
        double avgRiskF1 = f1Scores.stream().mapToDouble(a -> a[1]).average().orElse(0);
        double avgRespF1 = f1Scores.stream().mapToDouble(a -> a[2]).average().orElse(0);
        double globalF1  = (avgDepF1 + avgRiskF1 + avgRespF1) / 3.0;

        System.out.printf("  DependencyMapper F1 moyen  : %.3f%n", avgDepF1);
        System.out.printf("  Risques LLM F1 moyen       : %.3f%n", avgRiskF1);
        System.out.printf("  Responsabilités F1 moyen   : %.3f%n", avgRespF1);
        System.out.println("─".repeat(65));
        System.out.printf("  ★ F1 GLOBAL (%d cas)        : %.3f%n", cases.size(), globalF1);
        System.out.println("═".repeat(65));
    }

    private static List<GoldenCase> loadDataset(Path datasetPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String json = Files.readString(datasetPath);
        GoldenCase[] arr = mapper.readValue(json, GoldenCase[].class);
        return List.of(arr);
    }

    private static double[] runTestCase(
            AgentEvaluator evaluator,
            DependencyMapperAgent depMapper,
            JavaDocumentationAgent docAgent,
            GoldenCase gc,
            RunMetrics metrics) {

        Set<String> gtDeps = new HashSet<>(gc.expectedDependencies());
        Set<String> gtRisks = new HashSet<>(gc.expectedRiskKeywords());
        Set<String> gtResp = new HashSet<>(gc.expectedResponsibilities());

        // ── DependencyMapper (regex, déterministe) ────────────────
        FileScannerAgent.JavaFile fakeFile = new FileScannerAgent.JavaFile(
                Path.of(gc.name() + ".java"), gc.name(), gc.javaCode());
        DependencyMapperAgent.ClassDependencies deps = metrics.track(
                gc.name(), "dependency-map", () -> depMapper.analyze(fakeFile));

        // Toutes les entités extraites (imports + champs + extends + implements)
        Set<String> extracted = new HashSet<>();
        deps.imports().forEach(i -> extracted.add(i.contains(".") ? i.substring(i.lastIndexOf('.') + 1) : i));
        extracted.addAll(deps.fieldTypes());
        extracted.addAll(deps.extendsList());
        extracted.addAll(deps.implementsList());

        AgentEvaluator.EvalResult depResult = metrics.track(
                gc.name(), "evaluate", () -> evaluator.evaluateExact(extracted, gtDeps));
        AgentEvaluator.printReport("DependencyMapper", depResult);

        // ── CodeAnalyzer LLM ──────────────────────────────────────
        System.out.println("  → Appel LLM pour l'analyse...");
        String llmOutput = metrics.track(
                gc.name(), "analyze", () -> docAgent.analyzeJavaClass(gc.javaCode(), "", false, false));

        AgentEvaluator.EvalResult riskResult = evaluator.evaluateKeywords(llmOutput, gtRisks);
        AgentEvaluator.printReport("Risques (LLM keyword recall)", riskResult);

        AgentEvaluator.EvalResult respResult = evaluator.evaluateKeywords(llmOutput, gtResp);
        AgentEvaluator.printReport("Responsabilités (LLM recall)", respResult);

        return new double[]{depResult.f1(), riskResult.f1(), respResult.f1()};
    }
}

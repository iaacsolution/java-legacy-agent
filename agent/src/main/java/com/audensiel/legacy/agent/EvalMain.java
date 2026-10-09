package com.audensiel.legacy.agent;

import com.audensiel.legacy.agent.observability.SpanRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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
 *             [--deterministic-only] [--json fichier_resultat.json]
 *
 *   --deterministic-only : seule la composante DependencyMapper (regex) est calculée.
 *                          Aucun modèle n'est construit, aucun appel LLM ne part.
 *   --json               : écrit le résultat structuré (UTF-8, nombres JSON — insensibles à
 *                          la locale, contrairement au printf de la sortie console qui
 *                          affiche « 0,714 » sous fr_FR). Lu par scripts/eval_golden_dataset.py.
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

    /** Résultat d'un cas. {@code risques} et {@code responsabilites} sont null en mode déterministe. */
    record CaseResult(
            String name,
            AgentEvaluator.EvalResult dependencyMapper,
            AgentEvaluator.EvalResult risques,
            AgentEvaluator.EvalResult responsabilites
    ) {}

    /** Résultat d'un passage complet. Les moyennes LLM et le global sont null en mode déterministe. */
    record EvalReport(
            List<CaseResult> cases,
            double dependencyMapper,
            Double risques,
            Double responsabilites,
            Double global
    ) {}

    public static void main(String[] args) throws IOException {
        String datasetArg = null;
        String jsonOut = null;
        boolean deterministicOnly = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--deterministic-only" -> deterministicOnly = true;
                case "--json" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--json exige un chemin de fichier");
                    jsonOut = args[++i];
                }
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("option inconnue : " + args[i]);
                    datasetArg = args[i];
                }
            }
        }

        // Le recorder doit démarrer AVANT la construction des agents : LlmModelFactory
        // attache son ChatModelListener au moment où le modèle est construit, en lisant le
        // recorder actif. Démarré après, le listener serait celui du recorder inerte et
        // aucun span « llm » ne serait jamais enregistré en mode eval.
        //
        // start() et non get() : EvalMain est aussi un point d'entrée à part entière
        // (java -cp app.jar ...EvalMain), auquel cas Main n'a jamais démarré le recorder et
        // FLIGHTREC_ENABLED=true resterait sans effet. start() est idempotent, donc l'appel
        // depuis Main (mode « eval ») réutilise le recorder déjà actif.
        SpanRecorder recorder = SpanRecorder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(recorder::close, "flightrec-eval-shutdown"));

        String ollamaUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        Path datasetPath = Path.of(datasetArg != null ? datasetArg : DEFAULT_DATASET_PATH);

        System.out.println("═".repeat(65));
        System.out.println("  ÉVALUATION F1 — Java Legacy Migration Agents");
        System.out.println("  Dataset : " + cheminRelatif(datasetPath));
        if (deterministicOnly) {
            System.out.println("  Mode    : déterministe (DependencyMapper seul, aucun appel LLM)");
        }
        System.out.println("═".repeat(65));

        List<GoldenCase> cases = loadDataset(datasetPath);
        System.out.printf("  %d cas de test chargés%n", cases.size());

        // Le modèle n'est construit qu'en mode complet : en mode déterministe, aucun client
        // LLM n'existe, donc aucun appel ne peut partir, même par erreur.
        JavaDocumentationAgent docAgent = deterministicOnly ? null : new JavaDocumentationAgent(ollamaUrl);

        EvalReport report = evaluate(cases, docAgent, recorder, datasetPath.getFileName().toString());

        // ── Score global ──────────────────────────────────────────
        System.out.println("\n" + "═".repeat(65));
        System.out.println("  SCORES GLOBAUX");
        System.out.println("─".repeat(65));

        System.out.printf("  DependencyMapper F1 moyen  : %.3f%n", report.dependencyMapper());
        if (report.global() != null) {
            System.out.printf("  Risques LLM F1 moyen       : %.3f%n", report.risques());
            System.out.printf("  Responsabilités F1 moyen   : %.3f%n", report.responsabilites());
            System.out.println("─".repeat(65));
            System.out.printf("  ★ F1 GLOBAL (%d cas)        : %.3f%n", cases.size(), report.global());
        } else {
            System.out.println("─".repeat(65));
            System.out.println("  F1 GLOBAL : non calculé (mode déterministe, composantes LLM absentes)");
        }
        System.out.println("═".repeat(65));

        if (jsonOut != null) {
            writeJson(Path.of(jsonOut), report, cheminRelatif(datasetPath), deterministicOnly);
        }
    }

    /**
     * Évalue tous les cas. {@code docAgent} null = mode déterministe : les composantes LLM ne
     * sont ni calculées ni moyennées, et le global (moyenne des trois) n'existe pas.
     */
    static EvalReport evaluate(List<GoldenCase> cases, JavaDocumentationAgent docAgent,
                               SpanRecorder recorder, String datasetName) {
        AgentEvaluator evaluator = new AgentEvaluator();
        DependencyMapperAgent depMapper = new DependencyMapperAgent();

        List<CaseResult> results = new ArrayList<>();

        RunMetrics metrics = new RunMetrics("golden-dataset", recorder);

        // Racine « eval » : DependencyMapperAgent et AgentEvaluator ne sont instanciés que
        // dans ce mode, sans elle ces deux agents n'apparaîtraient dans aucun span.
        try (var runSpan = recorder.span("run", "AgentEvaluator", "eval")) {
            runSpan.attribute("dataset", datasetName)
                   .attribute("cases", cases.size());

            for (GoldenCase gc : cases) {
                System.out.println("\n━━━ " + gc.name() + " : " + gc.description() + " ━━━");
                results.add(runTestCase(evaluator, depMapper, docAgent, gc, metrics));
            }
        }

        double avgDepF1 = results.stream().mapToDouble(r -> r.dependencyMapper().f1()).average().orElse(0);
        if (docAgent == null) {
            return new EvalReport(results, avgDepF1, null, null, null);
        }
        double avgRiskF1 = results.stream().mapToDouble(r -> r.risques().f1()).average().orElse(0);
        double avgRespF1 = results.stream().mapToDouble(r -> r.responsabilites().f1()).average().orElse(0);
        double globalF1  = (avgDepF1 + avgRiskF1 + avgRespF1) / 3.0;
        return new EvalReport(results, avgDepF1, avgRiskF1, avgRespF1, globalF1);
    }

    /**
     * Résultat structuré pour scripts/eval_golden_dataset.py. Jackson sérialise les doubles
     * avec Double.toString : point décimal quelle que soit la locale de la JVM.
     */
    private static void writeJson(Path out, EvalReport report, String dataset, boolean deterministicOnly)
            throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dataset", dataset);
        root.put("mode", deterministicOnly ? "deterministic" : "full");
        root.put("backend", deterministicOnly ? null : LlmModelFactory.activeBackendKey());

        List<Map<String, Object>> parCas = new ArrayList<>();
        for (CaseResult r : report.cases()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cas", r.name());
            c.put("dependency_mapper", r.dependencyMapper());
            c.put("risques", r.risques());
            c.put("responsabilites", r.responsabilites());
            parCas.add(c);
        }
        root.put("par_cas", parCas);

        Map<String, Object> moyennes = new LinkedHashMap<>();
        moyennes.put("dependency_mapper", report.dependencyMapper());
        moyennes.put("risques", report.risques());
        moyennes.put("responsabilites", report.responsabilites());
        root.put("composantes_moyennes", moyennes);
        root.put("f1_global", report.global());

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            mapper.writeValue(w, root);
        }
    }

    /**
     * Chemin affichable : relatif au répertoire de travail quand c'est possible.
     *
     * <p>Les sorties de ce runner sont conservées comme preuves de mesure et commitées
     * (voir {@code results/raw/}). Un chemin absolu y ferait fuiter le nom d'utilisateur et
     * l'arborescence locale vers un dépôt public — c'est arrivé, et il a fallu assainir six
     * logs après coup.
     *
     * <p>Replie sur le nom de fichier si le dataset est hors du répertoire de travail :
     * {@code relativize} produirait sinon une enfilade de {@code ..} tout aussi bavarde.
     */
    static String cheminRelatif(Path chemin) {
        Path absolu = chemin.toAbsolutePath().normalize();
        Path base = Path.of("").toAbsolutePath().normalize();
        return absolu.startsWith(base)
                ? base.relativize(absolu).toString()
                : absolu.getFileName().toString();
    }

    static List<GoldenCase> loadDataset(Path datasetPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String json = Files.readString(datasetPath, StandardCharsets.UTF_8);
        GoldenCase[] arr = mapper.readValue(json, GoldenCase[].class);
        return List.of(arr);
    }

    private static CaseResult runTestCase(
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

        if (docAgent == null) {
            return new CaseResult(gc.name(), depResult, null, null);
        }

        // ── CodeAnalyzer LLM ──────────────────────────────────────
        System.out.println("  → Appel LLM pour l'analyse...");
        String llmOutput = metrics.track(
                gc.name(), "analyze", () -> docAgent.analyzeJavaClass(gc.javaCode(), "", false, false));

        AgentEvaluator.EvalResult riskResult = evaluator.evaluateKeywords(llmOutput, gtRisks);
        AgentEvaluator.printReport("Risques (LLM keyword recall)", riskResult);

        AgentEvaluator.EvalResult respResult = evaluator.evaluateKeywords(llmOutput, gtResp);
        AgentEvaluator.printReport("Responsabilités (LLM recall)", respResult);

        return new CaseResult(gc.name(), depResult, riskResult, respResult);
    }
}

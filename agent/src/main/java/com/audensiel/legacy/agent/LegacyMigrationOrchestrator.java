package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Orchestrateur — coordonne les agents pour produire un dossier de migration.
 *
 * Deux phases, séparables en deux process/conteneurs distincts (voir SECURITY_PLAN.md,
 * "casser le cumul de la trifecta sur le graphe d'agents") :
 *
 *   ANALYZE (runAnalyzePhase) : FileScannerAgent → AstParserAgent → JavaDocumentationAgent
 *       (code brut, non fiable, ingéré ici) → écrit un HandoffBundle sur disque.
 *       N'a besoin que d'un accès on-prem (vLLM/Ollama) — jamais d'ANTHROPIC_API_KEY.
 *
 *   REPORT (runReportPhase) : lit le HandoffBundle (jamais le code source brut) →
 *       synthèse DAT → MigrationPlannerAgent → export Markdown.
 *
 * run() enchaîne les deux phases dans le même process (mode démo/dev monolithique) —
 * en déploiement séparé (docker-compose java-analyzer / java-reporter), seul le
 * passage par disque du HandoffBundle relie les deux, jamais le code source lui-même.
 *
 * Chaque étape LLM passe par executeWithRetry() : si l'output est invalide,
 * l'étape est relancée jusqu'à MAX_RETRIES fois (gate qualité inspiré RAGAS).
 */
public class LegacyMigrationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationOrchestrator.class);
    private static final int MAX_RETRIES = 3;
    private static final int DEFAULT_WORKERS = 4;

    private record ClassResult(AstParserAgent.AstAnalysis ast, String specs, long startMs, long endMs) {}

    private final FileScannerAgent        scanner;
    private final AstParserAgent          astParser;
    private final JavaDocumentationAgent  documentationAgent;
    private final MigrationPlannerAgent   migrationPlanner;
    private final OutputValidator         validator;
    private final MetricsPusher           metricsPusher;
    private final String                  llmBackend;

    public LegacyMigrationOrchestrator(String ollamaBaseUrl, String pushgatewayUrl) {
        this.scanner            = new FileScannerAgent();
        this.astParser          = new AstParserAgent();
        this.documentationAgent = new JavaDocumentationAgent(ollamaBaseUrl);
        this.migrationPlanner   = new MigrationPlannerAgent(ollamaBaseUrl);
        this.validator          = new OutputValidator();
        this.metricsPusher      = new MetricsPusher(pushgatewayUrl);
        this.llmBackend         = LlmModelFactory.describeActiveBackend(ollamaBaseUrl);
    }

    /**
     * Mode monolithique (démo/dev) : enchaîne analyze + report dans le même process,
     * via un HandoffBundle temporaire local. En production, préférer runAnalyzePhase
     * et runReportPhase dans deux conteneurs séparés (java-analyzer / java-reporter).
     */
    public void run(Path projectPath, Path outputDir) throws IOException {
        Path handoffDir = Files.createTempDirectory("java-legacy-handoff-");
        try {
            runAnalyzePhase(projectPath, handoffDir);
            runReportPhase(handoffDir, projectPath, outputDir);
        } finally {
            deleteRecursively(handoffDir);
        }
    }

    /**
     * Phase ANALYZE — seule à lire le code source brut. N'utilise que le backend LLM
     * on-prem (vLLM/Ollama) : le process qui exécute cette méthode ne doit jamais
     * porter ANTHROPIC_API_KEY (voir docker-compose.yml, service java-analyzer).
     */
    public void runAnalyzePhase(Path projectPath, Path handoffDir) throws IOException {

        String projectName = projectPath.getFileName().toString();
        RunMetrics metrics = new RunMetrics(projectName);
        Tracer tracer = PipelineTracer.get();

        Span batchSpan = tracer.spanBuilder("analyze-batch")
            .setAttribute("project.name", projectName)
            .setAttribute("llm.backend", llmBackend)
            .startSpan();
        Context batchCtx = Context.current().with(batchSpan);

        System.out.println("=".repeat(60));
        System.out.println("  ANALYZER — ingestion du code source (jamais de sortie cloud)");
        System.out.println("  Projet : " + projectPath);
        System.out.println("=".repeat(60));
        log.info("Démarrage phase analyze — projet={}", projectName);

        // ── Scan ──────────────────────────────────────────────────
        System.out.println("\n[1/3] Scan des fichiers Java...");
        List<FileScannerAgent.JavaFile> javaFiles = metrics.track(
                projectName, "scan", () -> scanner.scanProject(projectPath));

        if (javaFiles.isEmpty()) {
            System.out.println("Aucun fichier Java trouvé. Arrêt.");
            batchSpan.end();
            return;
        }

        // ── AST + analyse LLM enrichie (parallèle) ──────────────────
        int workers = Integer.parseInt(System.getenv().getOrDefault("AGENT_WORKERS", String.valueOf(DEFAULT_WORKERS)));
        System.out.printf("\n[2/3] Analyse AST + LLM par classe (workers=%d)...%n", workers);
        log.info("Démarrage analyse parallèle — classes={} workers={}", javaFiles.size(), workers);

        batchSpan.setAttribute("batch.size", javaFiles.size());
        batchSpan.setAttribute("workers", workers);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<ClassResult>> futures = new ArrayList<>();
        final long batchStartMs = System.currentTimeMillis();

        for (int i = 0; i < javaFiles.size(); i++) {
            final FileScannerAgent.JavaFile file = javaFiles.get(i);
            final Context capturedCtx = batchCtx;
            futures.add(pool.submit(() -> {
                long startMs = System.currentTimeMillis();
                Span classSpan = tracer.spanBuilder("analyze")
                    .setParent(capturedCtx)
                    .setAttribute("class.name", file.className())
                    .startSpan();
                try (Scope ignored = classSpan.makeCurrent()) {
                    AstParserAgent.AstAnalysis ast = metrics.track(
                            file.className(), "ast", () -> astParser.analyze(file));

                    if (ast.cyclomaticComplexity() > 10) {
                        log.warn("Complexité cyclomatique élevée — classe={} cc={}", file.className(), ast.cyclomaticComplexity());
                        System.out.printf("    [CC=%d] classe complexe — analyse approfondie : %s%n",
                                ast.cyclomaticComplexity(), file.className());
                    }

                    classSpan.setAttribute("cyclomatic_complexity", ast.cyclomaticComplexity());

                    PromptInjectionScanner.ScanResult scanResult = PromptInjectionScanner.scan(file.content());
                    classSpan.setAttribute("injection_scan.suspicious", scanResult.suspicious());
                    if (scanResult.suspicious()) {
                        log.warn("Pattern(s) d'injection potentielle détecté(s) avant analyse — classe={} patterns={}",
                                file.className(), scanResult.matchedPatterns());
                        System.out.printf("    [ALERTE SÉCURITÉ] pattern(s) suspect(s) dans %s — traité comme donnée, jamais exécuté%n",
                                file.className());
                    }

                    AnalysisCommand<String> analyzeCmd = AnalysisCommand.of(
                            "analyze:" + file.className(),
                            () -> documentationAgent.analyzeJavaClassWithAst(ast));

                    String specs = executeWithRetry(
                            file.className(), "analyze", metrics, analyzeCmd,
                            output -> validator.validateAnalysis(file.className(), output));

                    if (scanResult.suspicious()) {
                        specs = "> ⚠️ **Alerte sécurité** : pattern(s) évoquant une injection de prompt détecté(s) "
                              + "dans le code source de cette classe avant analyse (" + scanResult.matchedPatterns().size()
                              + " pattern(s)). Le contenu a été traité comme donnée uniquement, jamais comme instruction "
                              + "ni exécuté — revue manuelle recommandée.\n\n" + specs;
                    }

                    boolean ok = !specs.startsWith("_[Génération échouée");
                    classSpan.setAttribute("success", ok);
                    metrics.recordFile(ok);
                    long endMs = System.currentTimeMillis();
                    return new ClassResult(ast, specs, startMs - batchStartMs, endMs - batchStartMs);
                } catch (Exception e) {
                    classSpan.recordException(e);
                    throw e;
                } finally {
                    classSpan.end();
                }
            }));
        }

        pool.shutdown();

        List<AstParserAgent.AstAnalysis> astResults = new ArrayList<>();
        List<String> allSpecs = new ArrayList<>();
        record TimingRow(String name, long startMs, long endMs) {}
        List<TimingRow> timings = new ArrayList<>();
        int completed = 0;

        for (int i = 0; i < futures.size(); i++) {
            String className = javaFiles.get(i).className();
            try {
                ClassResult result = futures.get(i).get();
                astResults.add(result.ast());
                allSpecs.add("### " + className + "\n\n" + result.specs());
                timings.add(new TimingRow(className, result.startMs(), result.endMs()));
                completed++;
                System.out.printf("  [%d/%d] %s termine%n", completed, javaFiles.size(), className);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Pipeline interrompu — classe={}", className);
                astResults.add(null);
                allSpecs.add("### " + className + "\n\n_[Generation interrompue]_");
                timings.add(new TimingRow(className, 0, 0));
            } catch (ExecutionException e) {
                log.error("Echec analyse — classe={} erreur={}", className, e.getCause().getMessage());
                metrics.recordFile(false);
                astResults.add(null);
                allSpecs.add("### " + className + "\n\n_[Generation echouee]_");
                timings.add(new TimingRow(className, 0, 0));
            }
        }

        try {
            pool.awaitTermination(4, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long batchTotalMs = System.currentTimeMillis() - batchStartMs;
        System.out.println("\n" + "=".repeat(62));
        System.out.printf("  TIMELINE PARALLELE  (workers=%d, classes=%d)%n", workers, javaFiles.size());
        System.out.println("=".repeat(62));
        System.out.printf("  %-28s %7s %7s %9s%n", "CLASSE", "DEBUT", "FIN", "DUREE");
        System.out.println("  " + "-".repeat(58));

        long prevEndMs = 0;
        for (TimingRow t : timings) {
            long durMs = t.endMs() - t.startMs();
            String parallel = (t.startMs() < prevEndMs && t.startMs() > 0) ? " <<" : "";
            System.out.printf("  %-28s %7s %7s %9s%s%n",
                t.name().length() > 28 ? t.name().substring(0, 25) + "..." : t.name(),
                fmtMs(t.startMs()),
                fmtMs(t.endMs()),
                fmtDur(durMs),
                parallel);
            if (t.endMs() > prevEndMs) prevEndMs = t.endMs();
        }

        System.out.println("  " + "-".repeat(58));
        System.out.printf("  %-28s %7s %7s %9s%n",
            "LOT TOTAL", "00:00", fmtMs(batchTotalMs), fmtDur(batchTotalMs));
        System.out.println("  << = classe demarree en parallele avec la precedente");
        System.out.println("=".repeat(62));

        String aggregatedSpecs = String.join("\n\n---\n\n", allSpecs);

        // ── Carte des dépendances AST ────────────────────────────
        System.out.println("\n[3/3] Carte des dépendances AST...");
        String dependencyReport = astParser.buildDependencyReport(astResults);

        HandoffBundle.write(handoffDir, new HandoffBundle.Data(
                projectName, javaFiles.size(), aggregatedSpecs, dependencyReport));

        metrics.exportJson(handoffDir.resolve(projectName));
        metrics.printSummary();
        metricsPusher.push(projectName + "-analyze", metrics.buildSummary());

        batchSpan.end();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Analyse terminée — bundle : " + handoffDir.resolve(projectName));
        System.out.println("=".repeat(60));
        log.info("Phase analyze terminée — projet={}", projectName);
    }

    /**
     * Phase REPORT — ne lit jamais le code source d'origine, seulement le
     * HandoffBundle produit par runAnalyzePhase. Peut légitimement utiliser
     * Anthropic (governé par ALLOW_CLOUD_CODE_ANALYSIS) puisqu'il ne traite
     * plus le code brut du client, seulement des spécifications déjà dérivées.
     */
    public void runReportPhase(Path handoffDir, Path projectPath, Path outputDir) throws IOException {

        String projectName = projectPath.getFileName().toString();
        HandoffBundle.Data bundle = HandoffBundle.read(handoffDir, projectName);
        RunMetrics metrics = new RunMetrics(projectName);
        Tracer tracer = PipelineTracer.get();

        Span batchSpan = tracer.spanBuilder("report-batch")
            .setAttribute("project.name", projectName)
            .setAttribute("llm.backend", llmBackend)
            .startSpan();

        System.out.println("=".repeat(60));
        System.out.println("  REPORTER — synthèse à partir des specs (jamais le code brut)");
        System.out.println("  Projet : " + projectPath + " (" + bundle.fileCount() + " classes)");
        System.out.println("=".repeat(60));
        log.info("Démarrage phase report — projet={}", projectName);

        try (Scope ignored = batchSpan.makeCurrent()) {

            // ── DAT avec retry gate ─────────────────────────
            System.out.println("\n[1/2] Génération du DAT...");
            AnalysisCommand<String> datCmd = AnalysisCommand.of(
                    "dat:" + projectName,
                    () -> documentationAgent.generateDAT(bundle.aggregatedSpecs())
            );
            String dat = executeWithRetry(
                    projectName, "dat", metrics, datCmd,
                    output -> validator.validateDAT(output));

            // ── Plan de migration avec retry gate ───────────
            System.out.println("\n[2/2] Génération du plan de migration...");
            AnalysisCommand<String> planCmd = AnalysisCommand.of(
                    "migration-plan:" + projectName,
                    () -> migrationPlanner.generateMigrationPlan(bundle.aggregatedSpecs())
            );
            String migrationPlan = executeWithRetry(
                    projectName, "migration-plan", metrics, planCmd,
                    output -> validator.validateMigrationPlan(output));

            // ── Export ────────────────────────────────────────────────
            String timestamp      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
            String outputFileName = "migration_" + projectName + "_" + timestamp + ".md";

            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve(outputFileName);
            Files.writeString(outputFile, buildReport(projectPath, bundle.fileCount(),
                    bundle.aggregatedSpecs(), bundle.dependencyReport(), dat, migrationPlan));

            metrics.exportJson(outputDir);
            metrics.printSummary();
            metricsPusher.push(projectName + "-report", metrics.buildSummary());

            System.out.println("\n" + "=".repeat(60));
            System.out.println("Rapport : " + outputFile);
            System.out.println("=".repeat(60));
            log.info("Phase report terminée — rapport={}", outputFile);
        } finally {
            batchSpan.end();
        }
    }

    /**
     * Exécute une commande avec retry si l'output échoue la validation qualité.
     * Si MAX_RETRIES atteint sans succès → fallback (gate qualité inspiré RAGAS).
     * Le retry_count est loggué pour audit.
     */
    private String executeWithRetry(String className, String stepName,
            RunMetrics metrics, AnalysisCommand<String> command,
            Function<String, OutputValidator.ValidationResult> validate) {

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            final int currentAttempt = attempt;
            String stepKey = attempt == 1 ? stepName : stepName + "_retry" + attempt;

            try {
                String result = metrics.track(className, stepKey, command::execute);
                OutputValidator.ValidationResult vr = validate.apply(result);

                if (vr.valid()) {
                    if (!vr.warnings().isEmpty())
                        log.warn("Output valide avec avertissements — cmd={} warnings={}", command.describe(), vr.warnings());
                    return result;
                }

                log.warn("Output invalide — cmd={} attempt={}/{} raisons={}",
                        command.describe(), currentAttempt, MAX_RETRIES, vr.warnings());
                System.out.printf("    [retry %d/%d] qualité insuffisante pour %s%n",
                        currentAttempt, MAX_RETRIES, className);

            } catch (Exception e) {
                log.error("Échec exécution — cmd={} attempt={}/{} error={}",
                        command.describe(), currentAttempt, MAX_RETRIES, e.getMessage());
            }
        }

        log.error("Gate qualité épuisé après {} tentatives — escalade fallback cmd={}", MAX_RETRIES, command.describe());
        System.out.printf("    [ESCALADE] %d tentatives échouées pour %s — fallback activé%n", MAX_RETRIES, className);
        return validator.fallback(className);
    }

    private static String fmtMs(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        return s >= 60 ? String.format("%dm%02ds", s / 60, s % 60) : s + "s";
    }

    private String buildReport(Path projectPath, int fileCount,
                               String specs, String deps, String dat, String migrationPlan) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        return """
                # Dossier de Migration — %s

                > Généré le %s par Legacy Migration Orchestrator (Qwen2.5-Coder via Ollama)

                ---

                ## Périmètre analysé

                - **Projet** : `%s`
                - **Fichiers analysés** : %d classes Java

                ---

                %s

                ---

                %s

                ---

                ## Spécifications techniques par classe

                %s
                """.formatted(
                projectPath.getFileName(), timestamp, projectPath,
                fileCount, dat, migrationPlan, specs);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }
}

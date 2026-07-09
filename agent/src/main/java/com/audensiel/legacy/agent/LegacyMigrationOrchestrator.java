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
 * Orchestrateur principal — coordonne les agents pour produire un dossier de migration.
 *
 * Pipeline :
 *   FileScannerAgent → AstParserAgent → JavaDocumentationAgent (specs enrichies AST)
 *       → JavaDocumentationAgent (DAT) → MigrationPlannerAgent → export Markdown
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

    public void run(Path projectPath, Path outputDir) throws IOException {

        String projectName = projectPath.getFileName().toString();
        RunMetrics metrics = new RunMetrics(projectName);
        Tracer tracer = PipelineTracer.get();

        // Span racine — couvre tout le batch (métrique 2 : durée totale du lot)
        Span batchSpan = tracer.spanBuilder("batch")
            .setAttribute("project.name", projectName)
            .setAttribute("llm.backend", llmBackend)
            .startSpan();
        Context batchCtx = Context.current().with(batchSpan);

        System.out.println("=".repeat(60));
        System.out.println("  LEGACY MIGRATION ORCHESTRATOR");
        System.out.println("  Projet : " + projectPath);
        System.out.println("=".repeat(60));
        log.info("Démarrage pipeline — projet={}", projectName);

        // ── Étape 1 : Scan ────────────────────────────────────────
        System.out.println("\n[1/5] Scan des fichiers Java...");
        List<FileScannerAgent.JavaFile> javaFiles = metrics.track(
                projectName, "scan", () -> scanner.scanProject(projectPath));

        if (javaFiles.isEmpty()) {
            System.out.println("Aucun fichier Java trouvé. Arrêt.");
            return;
        }

        // ── Étape 2 : AST + analyse LLM enrichie (parallèle) ────────
        int workers = Integer.parseInt(System.getenv().getOrDefault("AGENT_WORKERS", String.valueOf(DEFAULT_WORKERS)));
        System.out.printf("\n[2/5] Analyse AST + LLM par classe (workers=%d)...%n", workers);
        log.info("Démarrage analyse parallèle — classes={} workers={}", javaFiles.size(), workers);

        // Compléter le span racine avec les attributs du lot (maintenant que javaFiles est connu)
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

                    AnalysisCommand<String> analyzeCmd = AnalysisCommand.of(
                            "analyze:" + file.className(),
                            () -> documentationAgent.analyzeJavaClassWithAst(file.content(), ast));

                    String specs = executeWithRetry(
                            file.className(), "analyze", metrics, analyzeCmd,
                            output -> validator.validateAnalysis(file.className(), output));

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

        // ── Tableau de timeline (3 metriques d'observation) ──────────
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

        // ── Étape 3 : Rapport de dépendances AST ──────────────────
        System.out.println("\n[3/5] Carte des dépendances AST...");
        String dependencyReport = astParser.buildDependencyReport(astResults);

        // ── Étape 4 : DAT avec retry gate ─────────────────────────
        System.out.println("\n[4/5] Génération du DAT...");
        AnalysisCommand<String> datCmd = AnalysisCommand.of(
                "dat:" + projectName,
                () -> documentationAgent.generateDAT(aggregatedSpecs)
        );
        String dat = executeWithRetry(
                projectName, "dat", metrics, datCmd,
                output -> validator.validateDAT(output));

        // ── Étape 5 : Plan de migration avec retry gate ───────────
        System.out.println("\n[5/5] Génération du plan de migration...");
        AnalysisCommand<String> planCmd = AnalysisCommand.of(
                "migration-plan:" + projectName,
                () -> migrationPlanner.generateMigrationPlan(aggregatedSpecs)
        );
        String migrationPlan = executeWithRetry(
                projectName, "migration-plan", metrics, planCmd,
                output -> validator.validateMigrationPlan(output));

        // ── Export ────────────────────────────────────────────────
        String timestamp      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        String outputFileName = "migration_" + projectName + "_" + timestamp + ".md";

        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve(outputFileName);
        Files.writeString(outputFile, buildReport(projectPath, javaFiles.size(),
                aggregatedSpecs, dependencyReport, dat, migrationPlan));

        metrics.exportJson(outputDir);
        metrics.printSummary();
        metricsPusher.push(projectName, metrics.buildSummary());

        batchSpan.end(); // ferme le span racine — durée totale du lot enregistrée

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Rapport : " + outputFile);
        System.out.println("=".repeat(60));
        log.info("Pipeline terminé — rapport={}", outputFile);
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

                // Output invalide — décision retry ou escalade
                log.warn("Output invalide — cmd={} attempt={}/{} raisons={}",
                        command.describe(), currentAttempt, MAX_RETRIES, vr.warnings());
                System.out.printf("    [retry %d/%d] qualité insuffisante pour %s%n",
                        currentAttempt, MAX_RETRIES, className);

            } catch (Exception e) {
                log.error("Échec exécution — cmd={} attempt={}/{} error={}",
                        command.describe(), currentAttempt, MAX_RETRIES, e.getMessage());
            }
        }

        // Toutes les tentatives épuisées — escalade vers fallback (équivalent interrupt node)
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
}

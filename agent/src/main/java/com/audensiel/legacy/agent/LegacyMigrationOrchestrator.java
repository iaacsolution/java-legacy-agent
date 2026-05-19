package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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

    private final FileScannerAgent        scanner;
    private final AstParserAgent          astParser;
    private final JavaDocumentationAgent  documentationAgent;
    private final MigrationPlannerAgent   migrationPlanner;
    private final OutputValidator         validator;
    private final MetricsPusher           metricsPusher;

    public LegacyMigrationOrchestrator(String ollamaBaseUrl, String pushgatewayUrl) {
        this.scanner            = new FileScannerAgent();
        this.astParser          = new AstParserAgent();
        this.documentationAgent = new JavaDocumentationAgent(ollamaBaseUrl);
        this.migrationPlanner   = new MigrationPlannerAgent(ollamaBaseUrl);
        this.validator          = new OutputValidator();
        this.metricsPusher      = new MetricsPusher(pushgatewayUrl);
    }

    public void run(Path projectPath, Path outputDir) throws IOException {

        String projectName = projectPath.getFileName().toString();
        RunMetrics metrics = new RunMetrics(projectName);

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

        // ── Étape 2 : AST + analyse LLM enrichie ─────────────────
        System.out.println("\n[2/5] Analyse AST + LLM par classe...");
        List<AstParserAgent.AstAnalysis> astResults = new ArrayList<>();
        List<String> allSpecs = new ArrayList<>();

        for (int i = 0; i < javaFiles.size(); i++) {
            FileScannerAgent.JavaFile file = javaFiles.get(i);
            System.out.printf("  -> (%d/%d) %s%n", i + 1, javaFiles.size(), file.className());

            // AST instantané (pas de LLM — déterministe)
            AstParserAgent.AstAnalysis ast = metrics.track(
                    file.className(), "ast", () -> astParser.analyze(file));
            astResults.add(ast);

            // Complexité élevée → loggée pour arbitrage manuel
            if (ast.cyclomaticComplexity() > 10) {
                log.warn("Complexité cyclomatique élevée — classe={} cc={}", file.className(), ast.cyclomaticComplexity());
                System.out.printf("    [CC=%d] classe complexe — analyse approfondie%n", ast.cyclomaticComplexity());
            }

            // Analyse LLM avec contexte AST injecté dans le prompt + retry gate
            final AstParserAgent.AstAnalysis finalAst = ast;
            AnalysisCommand<String> analyzeCmd = AnalysisCommand.of(
                    "analyze:" + file.className(),
                    () -> documentationAgent.analyzeJavaClassWithAst(file.content(), finalAst)
            );

            String specs = executeWithRetry(
                    file.className(), "analyze", metrics, analyzeCmd,
                    output -> validator.validateAnalysis(file.className(), output));

            allSpecs.add("### " + file.className() + "\n\n" + specs);
            metrics.recordFile(!specs.startsWith("_[Génération échouée"));
        }

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

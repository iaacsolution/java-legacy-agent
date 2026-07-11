package com.audensiel.legacy.agent;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Point d'entrée — plusieurs modes :
 *
 *   Mode démo    : java -jar app.jar
 *                  → analyse un exemple EJB intégré
 *
 *   Mode projet  : java -jar app.jar /chemin/vers/projet [/dossier/sortie]
 *                  → pipeline complet (analyze + report) dans le même process
 *
 *   Mode analyze : java -jar app.jar analyze /chemin/vers/projet /dossier/handoff
 *                  → ingère le code brut, écrit un HandoffBundle. À déployer sans
 *                    ANTHROPIC_API_KEY (voir docker-compose.yml, java-analyzer).
 *
 *   Mode report  : java -jar app.jar report /dossier/handoff /chemin/vers/projet /dossier/sortie
 *                  → lit le HandoffBundle (jamais le code source brut), génère le rapport.
 *
 *   Mode serve   : java -jar app.jar serve /chemin/vers/projet [port]
 *                  → expose BreakingChangeDetector en HTTP (POST /impact) pour un appelant
 *                    externe (ex: jira-java-router, JAVA_AGENT_URL). Port par défaut : 8081.
 */
public class Main {

    static final String SAMPLE_LEGACY_CODE = """
            public class ClientServiceBean implements ClientService {

                private static final Logger log = Logger.getLogger(ClientServiceBean.class);
                private DataSource dataSource;

                public Client findClientByCode(String codeClient) throws ServiceException {
                    Connection conn = null;
                    PreparedStatement ps = null;
                    ResultSet rs = null;
                    Client client = null;
                    try {
                        conn = dataSource.getConnection();
                        ps = conn.prepareStatement(
                            "SELECT CLI_ID, CLI_NOM, CLI_PRENOM, CLI_STATUT, CLI_DATE_CREATION " +
                            "FROM T_CLIENT WHERE CLI_CODE = ? AND CLI_STATUT != 'S'"
                        );
                        ps.setString(1, codeClient);
                        rs = ps.executeQuery();
                        if (rs.next()) {
                            client = new Client();
                            client.setId(rs.getLong("CLI_ID"));
                            client.setNom(rs.getString("CLI_NOM"));
                            client.setPrenom(rs.getString("CLI_PRENOM"));
                            client.setStatut(rs.getString("CLI_STATUT"));
                            client.setDateCreation(rs.getDate("CLI_DATE_CREATION"));
                        }
                    } catch (SQLException e) {
                        log.error("Erreur findClientByCode: " + codeClient, e);
                        throw new ServiceException("Erreur base de donnees", e);
                    } finally {
                        closeResources(conn, ps, rs);
                    }
                    return client;
                }

                private void closeResources(Connection conn, PreparedStatement ps, ResultSet rs) {
                    try { if (rs   != null) rs.close();   } catch (SQLException e) { log.warn(e); }
                    try { if (ps   != null) ps.close();   } catch (SQLException e) { log.warn(e); }
                    try { if (conn != null) conn.close();  } catch (SQLException e) { log.warn(e); }
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        String ollamaUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        String otlpEndpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");

        PipelineTracer.init(otlpEndpoint);

        System.out.println("🚀 Java Legacy Migration Agent");
        System.out.println("🧠 LLM backend: " + LlmModelFactory.describeActiveBackend(ollamaUrl));
        System.out.println("📊 OTLP endpoint: " + otlpEndpoint);
        System.out.println("─".repeat(60));

        String pushgatewayUrl = System.getenv().getOrDefault("PUSHGATEWAY_URL", "http://pushgateway:9091");

        if (args.length >= 4 && args[0].equals("impact")) {
            // ── Mode détection changements cassants ─────────────────────
            // Usage : impact <chemin_projet> <ClassName> <methodName> [--json]
            Path projectPath = Paths.get(args[1]);
            String className  = args[2];
            String methodName = args[3];
            boolean json      = args.length >= 5 && args[4].equals("--json");

            System.out.println("Scanning AST : " + projectPath);
            System.out.println("Méthode cible : " + className + "." + methodName + "()");
            System.out.println("─".repeat(60));

            BreakingChangeDetector detector = new BreakingChangeDetector();
            BreakingChangeDetector.BreakingChangeReport report =
                    detector.analyze(projectPath, className, methodName);

            if (json) {
                System.out.println(report.toJson());
            } else {
                System.out.println(report.toConsoleReport());
            }

            // Log CSV ROI depuis Java (Linux Docker → pas de CRLF Windows)
            if (report.isBreaking()) {
                Path csvRoot = projectPath.getParent() != null
                        ? projectPath.getParent().getParent() : projectPath;
                RoiLogger.log(csvRoot, report);
                System.exit(1);
            }

        } else if (args.length >= 2 && args[0].equals("serve")) {
            // ── Mode serveur d'impact — expose BreakingChangeDetector en HTTP ──
            // Usage : serve <chemin_projet> [port]
            Path projectPath = Paths.get(args[1]);
            int port = args.length >= 3 ? Integer.parseInt(args[2]) : 8081;

            new ImpactServer().start(projectPath, port);
            // Le serveur tourne sur son propre thread HttpServer — ne pas quitter le process.
            Thread.currentThread().join();

        } else if (args.length >= 1 && args[0].equals("eval")) {
            // ── Mode évaluation F1 ──────────────────────────────────
            EvalMain.main(new String[]{});

        } else if (args.length >= 1 && args[0].equals("plan")) {
            // ── Mode plan de migration — demo Screenshot 4 ──────────
            System.out.println("Mode plan de migration (demo)");
            System.out.println("─".repeat(60));

            JavaDocumentationAgent docAgent = new JavaDocumentationAgent(ollamaUrl);
            MigrationPlannerAgent plannerAgent = new MigrationPlannerAgent(ollamaUrl);

            System.out.println("🔍 Étape 1/2 — Analyse du code legacy...");
            String specs = docAgent.analyzeJavaClass(SAMPLE_LEGACY_CODE, "", false, false);

            System.out.println("\n" + "─".repeat(60));
            System.out.println("🗺️  Étape 2/2 — Génération du plan de migration...");
            System.out.println("─".repeat(60) + "\n");

            String plan = plannerAgent.generateMigrationPlan(specs);

            System.out.println("╔══════════════════════════════════════════════════════════╗");
            System.out.println("║          PLAN DE MIGRATION — ClientServiceBean           ║");
            System.out.println("╚══════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.println(plan);
            System.out.println();
            System.out.println("─".repeat(60));
            System.out.println("✅ Livrable généré — prêt pour l'équipe architecture");
            System.out.println("─".repeat(60));

        } else if (args.length >= 3 && args[0].equals("analyze")) {
            // ── Mode ANALYZER — ingère le code brut, jamais de sortie cloud ──
            // Usage : analyze <projet> <dossier_handoff>
            // À déployer sans ANTHROPIC_API_KEY (voir docker-compose.yml, java-analyzer).
            Path projectPath = Paths.get(args[1]);
            Path handoffDir  = Paths.get(args[2]);

            LegacyMigrationOrchestrator orchestrator = new LegacyMigrationOrchestrator(ollamaUrl, pushgatewayUrl);
            orchestrator.runAnalyzePhase(projectPath, handoffDir);
            PipelineTracer.shutdown();

        } else if (args.length >= 4 && args[0].equals("report")) {
            // ── Mode REPORTER — ne lit jamais le code source brut ───
            // Usage : report <dossier_handoff> <projet> <dossier_sortie>
            Path handoffDir  = Paths.get(args[1]);
            Path projectPath = Paths.get(args[2]);
            Path outputDir   = Paths.get(args[3]);

            LegacyMigrationOrchestrator orchestrator = new LegacyMigrationOrchestrator(ollamaUrl, pushgatewayUrl);
            orchestrator.runReportPhase(handoffDir, projectPath, outputDir);
            PipelineTracer.shutdown();

        } else if (args.length >= 1) {
            // ── Mode projet : pipeline complet sur un vrai dossier ──
            Path projectPath = Paths.get(args[0]);
            Path outputDir   = args.length >= 2
                    ? Paths.get(args[1])
                    : projectPath.resolve("migration-output");

            LegacyMigrationOrchestrator orchestrator = new LegacyMigrationOrchestrator(ollamaUrl, pushgatewayUrl);
            orchestrator.run(projectPath, outputDir);
            PipelineTracer.shutdown(); // flush BatchSpanProcessor avant exit

        } else {
            // ── Mode démo : exemple EJB intégré avec métriques ──────
            System.out.println("Mode démo (aucun argument fourni)");
            System.out.println("Usage : java -jar app.jar <chemin_projet> [chemin_sortie]");
            System.out.println("─".repeat(60));

            RunMetrics metrics = new RunMetrics("demo");
            MetricsPusher pusher = new MetricsPusher(pushgatewayUrl);
            JavaDocumentationAgent agent = new JavaDocumentationAgent(ollamaUrl);

            String specs = metrics.track("ClientServiceBean", "analyze",
                    () -> agent.analyzeJavaClass(SAMPLE_LEGACY_CODE, "", false, false));
            metrics.recordFile(true);
            System.out.println("\n## Spécifications extraites (vision micro)\n");
            System.out.println(specs);

            System.out.println("\n" + "─".repeat(60));

            String dat = metrics.track("demo", "dat",
                    () -> agent.generateDAT(specs));
            System.out.println("\n## Dossier d'Architecture Technique (vision macro)\n");
            System.out.println(dat);

            metrics.printSummary();
            pusher.push("demo", metrics.buildSummary());
        }
    }
}

package com.audensiel.legacy.agent;

import java.util.*;

/**
 * Runner d'évaluation F1 — mesure la qualité des agents sur des cas de test annotés.
 *
 * Usage : java -cp app.jar com.audensiel.legacy.agent.EvalMain
 */
public class EvalMain {

    // ── Cas de test 1 : EJB ClientServiceBean ────────────────────────────────
    static final String CODE_CLIENT_SERVICE = """
            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.ResultSet;
            import java.sql.SQLException;
            import javax.sql.DataSource;

            public class ClientServiceBean implements ClientService {
                private static final Logger log = Logger.getLogger(ClientServiceBean.class);
                private DataSource dataSource;

                public Client findClientByCode(String codeClient) throws ServiceException {
                    Connection conn = null;
                    PreparedStatement ps = null;
                    ResultSet rs = null;
                    try {
                        conn = dataSource.getConnection();
                        ps = conn.prepareStatement(
                            "SELECT CLI_ID, CLI_NOM FROM T_CLIENT WHERE CLI_CODE = ? AND CLI_STATUT != 'S'");
                        ps.setString(1, codeClient);
                        rs = ps.executeQuery();
                        if (rs.next()) {
                            Client client = new Client();
                            client.setId(rs.getLong("CLI_ID"));
                            return client;
                        }
                    } catch (SQLException e) {
                        log.error("Erreur", e);
                        throw new ServiceException("Erreur base de donnees", e);
                    } finally {
                        try { if (rs   != null) rs.close();   } catch (SQLException e) { log.warn(e); }
                        try { if (ps   != null) ps.close();   } catch (SQLException e) { log.warn(e); }
                        try { if (conn != null) conn.close();  } catch (SQLException e) { log.warn(e); }
                    }
                    return null;
                }
            }
            """;

    // Ground truth cas 1
    static final Set<String> GT_DEPS_1 = Set.of(
            "Connection", "PreparedStatement", "ResultSet", "DataSource",
            "Client", "ServiceException", "Logger"
    );
    static final Set<String> GT_RISKS_1 = Set.of(
            "ressource", "finally", "warn", "try-with-resources",
            "fuite", "dette", "SQLException"
    );
    static final Set<String> GT_RESPONSIBILITIES_1 = Set.of(
            "client", "base de données", "connexion", "code"
    );

    // ── Cas de test 2 : Struts Action ────────────────────────────────────────
    static final String CODE_STRUTS_ACTION = """
            import org.apache.struts.action.Action;
            import org.apache.struts.action.ActionForm;
            import org.apache.struts.action.ActionForward;
            import org.apache.struts.action.ActionMapping;
            import javax.servlet.http.HttpServletRequest;
            import javax.servlet.http.HttpServletResponse;

            public class CommandeAction extends Action {
                private CommandeService commandeService;

                public ActionForward execute(ActionMapping mapping, ActionForm form,
                                             HttpServletRequest request, HttpServletResponse response) {
                    CommandeForm commandeForm = (CommandeForm) form;
                    try {
                        Commande commande = commandeService.creerCommande(commandeForm.getClientId());
                        request.setAttribute("commande", commande);
                        return mapping.findForward("success");
                    } catch (Exception e) {
                        request.setAttribute("erreur", e.getMessage());
                        return mapping.findForward("error");
                    }
                }
            }
            """;

    // Ground truth cas 2
    static final Set<String> GT_DEPS_2 = Set.of(
            "Action", "ActionForm", "ActionForward", "ActionMapping",
            "HttpServletRequest", "HttpServletResponse",
            "CommandeService", "Commande"
    );
    static final Set<String> GT_RISKS_2 = Set.of(
            "struts", "couplage", "servlet", "exception", "migration",
            "spring", "mvc"
    );
    static final Set<String> GT_RESPONSIBILITIES_2 = Set.of(
            "commande", "action", "formulaire", "navigation"
    );

    // ── Cas de test 3 : Singleton avec état partagé ───────────────────────────
    static final String CODE_SINGLETON = """
            public class ConfigurationManager {
                private static ConfigurationManager instance;
                private static final Map<String, String> config = new HashMap<>();

                private ConfigurationManager() {}

                public static ConfigurationManager getInstance() {
                    if (instance == null) {
                        instance = new ConfigurationManager();
                    }
                    return instance;
                }

                public String get(String key) { return config.get(key); }
                public void set(String key, String value) { config.put(key, value); }
            }
            """;

    static final Set<String> GT_DEPS_3 = Set.of("Map", "HashMap");
    static final Set<String> GT_RISKS_3 = Set.of(
            "singleton", "thread", "synchroni", "concurren", "état partagé", "instance"
    );
    static final Set<String> GT_RESPONSIBILITIES_3 = Set.of(
            "configuration", "clé", "valeur"
    );

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String ollamaUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

        System.out.println("═".repeat(65));
        System.out.println("  ÉVALUATION F1 — Java Legacy Migration Agents");
        System.out.println("═".repeat(65));

        AgentEvaluator evaluator = new AgentEvaluator();
        DependencyMapperAgent depMapper = new DependencyMapperAgent();
        JavaDocumentationAgent docAgent = new JavaDocumentationAgent(ollamaUrl);

        List<double[]> f1Scores = new ArrayList<>();

        // ── Test 1 ────────────────────────────────────────────────
        System.out.println("\n━━━ Cas 1 : EJB ClientServiceBean ━━━");
        f1Scores.add(runTestCase(evaluator, depMapper, docAgent,
                "cas1", CODE_CLIENT_SERVICE,
                GT_DEPS_1, GT_RISKS_1, GT_RESPONSIBILITIES_1));

        // ── Test 2 ────────────────────────────────────────────────
        System.out.println("\n━━━ Cas 2 : Struts CommandeAction ━━━");
        f1Scores.add(runTestCase(evaluator, depMapper, docAgent,
                "cas2", CODE_STRUTS_ACTION,
                GT_DEPS_2, GT_RISKS_2, GT_RESPONSIBILITIES_2));

        // ── Test 3 ────────────────────────────────────────────────
        System.out.println("\n━━━ Cas 3 : Singleton ConfigurationManager ━━━");
        f1Scores.add(runTestCase(evaluator, depMapper, docAgent,
                "cas3", CODE_SINGLETON,
                GT_DEPS_3, GT_RISKS_3, GT_RESPONSIBILITIES_3));

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
        System.out.printf("  ★ F1 GLOBAL                : %.3f%n", globalF1);
        System.out.println("═".repeat(65));
    }

    private static double[] runTestCase(
            AgentEvaluator evaluator,
            DependencyMapperAgent depMapper,
            JavaDocumentationAgent docAgent,
            String caseName,
            String code,
            Set<String> gtDeps,
            Set<String> gtRisks,
            Set<String> gtResp) {

        // ── DependencyMapper (regex, déterministe) ────────────────
        FileScannerAgent.JavaFile fakeFile = new FileScannerAgent.JavaFile(
                java.nio.file.Path.of(caseName + ".java"), caseName, code);
        DependencyMapperAgent.ClassDependencies deps = depMapper.analyze(fakeFile);

        // Toutes les entités extraites (imports + champs + extends + implements)
        Set<String> extracted = new HashSet<>();
        deps.imports().forEach(i -> extracted.add(i.contains(".") ? i.substring(i.lastIndexOf('.') + 1) : i));
        extracted.addAll(deps.fieldTypes());
        extracted.addAll(deps.extendsList());
        extracted.addAll(deps.implementsList());

        AgentEvaluator.EvalResult depResult = evaluator.evaluateExact(extracted, gtDeps);
        AgentEvaluator.printReport("DependencyMapper", depResult);

        // ── CodeAnalyzer LLM ──────────────────────────────────────
        System.out.println("  → Appel LLM pour l'analyse...");
        String llmOutput = docAgent.analyzeJavaClass(code);

        AgentEvaluator.EvalResult riskResult = evaluator.evaluateKeywords(llmOutput, gtRisks);
        AgentEvaluator.printReport("Risques (LLM keyword recall)", riskResult);

        AgentEvaluator.EvalResult respResult = evaluator.evaluateKeywords(llmOutput, gtResp);
        AgentEvaluator.printReport("Responsabilités (LLM recall)", respResult);

        return new double[]{depResult.f1(), riskResult.f1(), respResult.f1()};
    }
}

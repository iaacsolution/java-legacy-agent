@echo off
color 0A
cls
echo.
echo  java-legacy-agent/
echo  ^|
echo  +-- agent/src/.../audensiel/legacy/agent/
echo  ^|
echo  ^|   [ PIPELINE ORCHESTRATION ]
echo  ^|   +-- Main.java                     Point d'entree (3 modes)
echo  ^|   +-- LegacyMigrationOrchestrator   Orchestrateur pipeline
echo  ^|
echo  ^|   [ AGENTS SPECIALISES ]
echo  ^|   +-- FileScannerAgent.java          Agent 1 - Scan .java
echo  ^|   +-- DependencyMapperAgent.java     Agent 2 - Graphe dependances
echo  ^|   +-- JavaDocumentationAgent.java    Agent 3 - Specs + DAT (LLM)
echo  ^|   +-- MigrationPlannerAgent.java     Agent 4 - Plan migration (LLM)
echo  ^|
echo  ^|   [ OBSERVABILITE ]
echo  ^|   +-- RunMetrics.java                Metriques par etape
echo  ^|   +-- MetricsPusher.java             Push vers Prometheus
echo  ^|
echo  ^|   [ EVALUATION ]
echo  ^|   +-- AgentEvaluator.java            Score F1
echo  ^|   +-- EvalMain.java                  3 cas de test
echo  ^|   +-- OutputValidator.java           Validation sorties LLM
echo  ^|
echo  +-- monitoring/                        Prometheus + Grafana
echo  +-- docker-compose.yml                 7 services Docker
echo.
echo  ============================================================
echo   11 classes Java  ^|  LangChain4j 0.36  ^|  Qwen2.5-Coder 7b
echo   Score F1 global : 0.748  ^|  Taux de succes : 100%%
echo  ============================================================
echo.
pause

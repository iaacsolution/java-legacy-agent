# Java Legacy Migration Agent

Automated analysis and documentation of Java legacy codebases using AST parsing and local LLM (Qwen).

🔗 **Related project:** [RAG Chatbot](https://huggingface.co/spaces/Krebs/claude-courses-assistant)

---

## What it does

Scans Java legacy code via AST analysis (JavaParser), generates technical documentation and migration plans using a local LLM — no cloud dependency, fully on-premise.

```
Java codebase
    │
    ▼
FileScannerAgent       → discovers all .java files
AstParserAgent         → extracts classes, methods, dependencies
CallGraphAgent         → maps call graph between components
DependencyMapperAgent  → resolves inter-module dependencies
    │
    ▼
JavaDocumentationAgent → LLM (Qwen local) generates DAT per class
MigrationPlannerAgent  → produces prioritized migration plan
RefactoringAdvisor     → suggests refactoring actions
    │
    ▼
AgentEvaluator         → F1 scoring on classification quality
RoiLogger              → logs time saved per class (CSV)
MetricsPusher          → Prometheus metrics
```

## Results

| Metric | Value |
|--------|-------|
| **F1 score** (component classification) | **0.748** |
| **Time per class** (automated) | **~9 min** |
| **Time per class** (manual) | 2–4 hours |
| **Productivity gain** | **×15–25** |

## Stack

| Layer | Technology |
|-------|------------|
| AST parsing | JavaParser (Java/Maven) |
| LLM inference | Qwen (local via Ollama) |
| Orchestration | Python agents |
| Observability | Prometheus · Grafana |
| Tracing | Phoenix OTEL |
| Deployment | Docker Compose (8 services) |

## Project structure

```
agent/          Java source — AST parsing, agents, orchestrator (Maven)
airflow/        DAG definitions for pipeline scheduling
monitoring/     Prometheus config + Grafana dashboard
hooks/          Git hooks for CI integration
docker-compose.yml
```

## Quick start

```bash
# Start all services
docker compose up --build

# Run analysis on a Java project
cd agent
mvn compile exec:java -Dexec.mainClass="com.audensiel.legacy.agent.Main" \
  -Dexec.args="--path /path/to/java/project"
```

## Services

| Service | Port | Purpose |
|---------|------|---------|
| Agent API | 8083 | REST API for analysis requests |
| Ollama (Qwen) | 11434 | Local LLM inference |
| Prometheus | 9093 | Metrics scraping |
| Grafana | 3002 | Dashboards |
| Phoenix OTEL | 6006 | LLM traces |

---

Built by **Stéphane Krebs** — Consultant IA & Automatisation

# Java Legacy Migration Agent

Automated analysis and documentation of Java legacy codebases using AST parsing and local LLM (Qwen).

🔗 **Related project:** [RAG Chatbot](https://huggingface.co/spaces/Krebs/claude-courses-assistant)

---

## What it does

Scans Java legacy code via AST analysis (JavaParser), generates technical documentation and migration plans using a local LLM — no cloud dependency, fully on-premise.

LLM backend priority (`LlmModelFactory`): **vLLM local GPU** (continuous batching, real parallelism, sovereign) → Anthropic Claude Haiku (cloud fallback) → Ollama CPU (fallback, serializes requests — set `AGENT_WORKERS=1`).

### Pipeline de migration (`LegacyMigrationOrchestrator`)

```
Java codebase
    │
    ▼
FileScannerAgent       → discovers all .java files
AstParserAgent         → extracts classes, methods, dependencies
    │
    ▼
JavaDocumentationAgent → LLM (Qwen local) generates DAT per class
MigrationPlannerAgent  → produces prioritized migration plan
    │
    ▼
MetricsPusher          → Prometheus metrics (pushed after analyze and report phases)
```

### Modes annexes / CLI (outside the migration pipeline)

```
CallGraphAgent + RefactoringAdvisor + RoiLogger
    → mode `impact` / `serve` (BreakingChangeDetector) : detects breaking changes on a
      single method, suggests a refactoring strategy, logs blocking changes to CSV

DependencyMapperAgent + AgentEvaluator
    → mode `eval` (EvalMain) : F1 scoring against 3 hardcoded test cases
```

## Measured metrics — revision

Measured on `demo-project` (4 classes), cloud backend (Claude Haiku — the only backend where
parallel workers actually help; Ollama serializes requests by design, see `LlmModelFactory`),
`AGENT_WORKERS=4`, after commit `4128e54`.

| Metric | Value | Method |
|--------|-------|--------|
| Parallelization speedup | **×2.50** (median of 3 runs) | 21.2s (1 worker) → 8.5s (4 workers), medians of 3 runs each — 1-worker range 20.9–23.1s, 4-worker range 8.3–10.8s. Identical payload sizes in both configs (288–378 chars); under 4 workers, all 4 requests dispatch at the same timestamp — real parallelism, not measurement noise. |
| F1 (`AgentEvaluator`) | **0.757** | Golden dataset, temperature 0.1 |
| Outbound payload (cloud path) | 288–378 chars | Signatures, field types, cyclomatic complexity, imports — never a method body, never a SQL literal |

**Revision note.** The earlier ×1.9 speedup figure is retracted, not superseded by a bigger
number. `JavaParser` (JavaParser library) was not thread-safe: `AstParserAgent` shared one
instance across parallel workers, and concurrent `.parse()` calls threw
`ConcurrentModificationException` — silently swallowed by a `catch (Exception ignored)` that made
every class fall back to an *empty* analysis, with the pipeline still reporting success (exit 0).
Fixed in `4128e54` (one `JavaParser` instance per call instead of a shared field, and the fallback
is now a logged `WARN`, never silent). F1 is unaffected by this bug: `EvalMain` never instantiates
`AstParserAgent` — verified by tracing the call path, then confirmed by actually re-running the
eval, not by assuming the code read was enough.

**Why the speedup went up after the fix, not down** — worth stating because it's
counterintuitive: before the fix, every worker was processing a ~70-character stub. There was
almost no real work to parallelize, so orchestration overhead dominated and the speedup
collapsed. After the fix, there's real work to parallelize, so the same overhead becomes
marginal — the speedup increases. A real payload parallelizes better than an empty one.

**Methodology, stated plainly**: 4 classes, 3 runs per configuration, median reported alongside
the observed range. This is an order-of-magnitude figure, not a statistically robust benchmark —
small sample, one machine, cloud network variance not controlled for.

## Stack

| Layer | Technology |
|-------|------------|
| AST parsing | JavaParser (Java/Maven) |
| LLM inference | Qwen2.5-Coder-7B via vLLM (GPU, continuous batching), Claude Haiku or Ollama as fallback |
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

Secrets live in `.env` (gitignored, see `.env.example`) — sufficient for this
personal-portfolio/POC scope. A real deployment would move them to a vault instead;
see [SECURITY_PLAN.md](SECURITY_PLAN.md) for the key-rotation policy and the
anti-`logRequests` guard already in place.

## Services

| Service | Port | Purpose |
|---------|------|---------|
| vLLM (Qwen, GPU) | 8000 | Local LLM inference — OpenAI-compatible, continuous batching |
| Open WebUI | 3002 | Chat interface (points at vLLM) |
| Prometheus | 9093 | Metrics scraping |
| Grafana | 3003 | Dashboards |
| Phoenix OTEL | 6006 | LLM traces |
| Airflow | 8083 | Workflow orchestration |

Requires `nvidia-container-toolkit` + a GPU with ≥8 GB VRAM. No GPU available? Comment out the `vllm` service, uncomment `ollama` in `docker-compose.yml`, and set `AGENT_WORKERS=1` (Ollama serializes requests).

---

Built by **Stéphane Krebs** — Consultant IA & Automatisation

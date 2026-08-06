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
    → mode `eval` (EvalMain) : F1 scoring against golden_dataset.json (5 cases, see
      "Golden dataset" below — not hardcoded anymore)
```

## Measured metrics — revision

Measured on `demo-project` (4 classes), cloud backend (Claude Haiku — the only backend where
parallel workers actually help; Ollama serializes requests by design, see `LlmModelFactory`),
`AGENT_WORKERS=4`, after commit `4128e54`.

| Metric | Value | Method |
|--------|-------|--------|
| Parallelization speedup | **×2.50** (median of 3 runs) | 21.2s (1 worker) → 8.5s (4 workers), medians of 3 runs each — 1-worker range 20.9–23.1s, 4-worker range 8.3–10.8s. Identical payload sizes in both configs (288–378 chars); under 4 workers, all 4 requests dispatch at the same timestamp — real parallelism, not measurement noise. |
| F1 (`AgentEvaluator`) | **0.802** (5 cas) | Golden dataset, temperature 0.1 — voir "Golden dataset" ci-dessous, pas comparable au 0.757 historique (3 cas) |
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

**Traçabilité partielle** : le log de confirmation backend par run n'a pas été écrit (crash
d'encodage survenu avant cette écriture sur ce run précis, corrigé depuis en `45b8c1b`). Le
backend cloud actif est confirmé indirectement — le script sort en erreur si la bannière backend
n'est pas "Anthropic Claude Haiku", jamais déclenché sur les 6 runs — mais sans preuve textuelle
littérale conservée pour cette exécution spécifique.

## Golden dataset

Cas de test chargés depuis `golden_dataset.json` (racine du projet) par `EvalMain`, plus codés en
dur dans le source. 5 cas : les 3 originaux (EJB `ClientServiceBean`, Struts `CommandeAction`,
Singleton `ConfigurationManager`) inchangés, + 2 nouveaux (`InvoiceDao` — injection SQL et fuite de
ressource ; `AuditLogServiceImpl` — exception avalée silencieusement).

**F1 = 0.802 sur les 5 cas actuels — pas directement comparable au 0.757 historique (3 cas).** Les
2 nouveaux cas évitent volontairement le bug `FIELD_PATTERN` décrit plus bas, ce qui tire
mécaniquement la moyenne `DependencyMapper` vers le haut (0.502 → 0.701 sur les composantes
mesurées). Ce n'est pas une amélioration du pipeline, seulement un effet de composition du
dataset.

**Non-régression vérifiée séparément, pas seulement supposée.** En ré-exécutant `EvalMain` sur les
3 cas d'origine seuls (dataset isolé, temporaire), le F1 global retombe exactement sur **0.757**,
identique au chiffre historique — confirme que le passage du hardcode au chargement JSON n'a rien
changé au calcul.

**Limite connue — le F1 n'est pas parfaitement reproductible d'un run à l'autre.** La composante
déterministe (`DependencyMapperAgent`, regex, sans LLM) est bit-identique entre deux runs
indépendants sur les mêmes 3 cas (F1 = 0.571 / 0.933 / 0.000, à chaque fois). Les composantes qui
dépendent du LLM (rappel de mots-clés sur risques et responsabilités) varient réellement d'un
appel à l'autre, même à température 0.1 — observé concrètement lors de cette session : le F1
"Risques" du cas 1 est sorti à 0.923 puis 0.833 sur deux runs successifs, mêmes code et mêmes clés
de vérité. Traiter "0.757" ou "0.802" comme une valeur figée serait trompeur ; ce sont des points
de mesure, pas des constantes.

**Bug connu, non corrigé volontairement — `DependencyMapperAgent.FIELD_PATTERN`.** Sur un champ
`private static final Type name`, le pattern capture `"static"` comme si c'était le type, jamais
`Type`. Concrètement : `Logger` (cas1) et `Map`/`HashMap` (cas3) ne sont jamais extraits, malgré
leur présence dans le code — TODO documenté au-dessus de `FIELD_PATTERN` dans
`DependencyMapperAgent.java`. Déjà présent dans le F1 = 0.757 historique, pas une régression
introduite ici. Correctif prévu dans un commit séparé, avec re-mesure du F1 après coup — le
corriger silencieusement dans ce commit aurait invalidé le test de non-régression ci-dessus.

**Taille du dataset — 5 cas, volontairement limité.** Représentatif du style de code legacy visé
(EJB, Struts, Singleton, DAO JDBC brut, exception avalée), mais reste un échantillon réduit pour
un score censé représenter la qualité du pipeline sur du code legacy réel et varié. Étendre le
dataset (plus de cas, davantage de diversité de risques) est une amélioration future, pas traitée
ici faute de temps dans cette session.

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

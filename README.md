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

Chaque chiffre ci-dessous porte **son backend** : ils ne sont pas interchangeables. Le speedup
est mesuré sur Claude Haiku (seul backend où les workers parallèles aident réellement — Ollama
sérialise ses requêtes par conception, voir `LlmModelFactory`), sur `demo-project` (4 classes),
`AGENT_WORKERS=4`. Le F1 est mesuré sur Ollama local. Le surcoût du recorder est un
microbenchmark sans LLM.

| Métrique | Valeur | Backend | Méthode et source |
|---|---|---|---|
| F1 (`AgentEvaluator`) | **0.808** — médiane de 3 passages, étendue 0.763–0.844 | Ollama local `qwen2.5-coder:7b`, température 0.1 | `golden_dataset_3cases.json`, après correction `FIELD_PATTERN`. Source : [`results/f1_ollama.json`](results/f1_ollama.json), sorties brutes dans `results/raw/` |
| Speedup de parallélisation | **×2.88** — médianes 20776 ms (1 worker) vs 7218 ms (4 workers) | Claude Haiku | `scripts/benchmark_speedup.py`, 3 runs par configuration, valeurs brutes dans le README ci-dessous |
| Surcoût du recorder, côté agent | **0.448 µs/span** — étendue 0.391–0.492 | aucun (microbenchmark, sans LLM) | 5 séries de 100 000 spans après chauffe. Source : [`results/flightrec_bench.json`](results/flightrec_bench.json) |
| Débit d'écriture du recorder | **23 145 spans/s** — 50 000 écrits, 0 perdu | aucun (TimescaleDB local) | idem, source : [`results/flightrec_bench.json`](results/flightrec_bench.json) |
| Charge sortante (chemin cloud) | 288–378 caractères | Claude Haiku | Signatures, types de champs, complexité cyclomatique, imports — jamais un corps de méthode, jamais un littéral SQL |

**F1 avant / après la correction `FIELD_PATTERN`**, mêmes modèle, empreinte et température
([`results/f1_ollama.json`](results/f1_ollama.json)) :

| | Composante déterministe (regex, sans LLM) | F1 global — médiane | F1 global — étendue |
|---|---|---|---|
| Avant correction | 0.502 | 0.737 | 0.687 – 0.739 |
| **Après correction** | **0.716** | **0.808** | **0.763 – 0.844** |

**L'effet de la correction se lit sur la composante déterministe, et là seulement** : elle est
exacte et identique aux 3 passages de chaque série. Le F1 global inclut en plus la dérive du LLM,
et ses deux étendues se touchent presque — le présenter comme « +0.07 grâce à la correction »
serait une surinterprétation.

**×2.50 est une mesure antérieure** du même speedup, sur une autre machine, également sur Claude
Haiku. Elle est conservée plus bas pour mémoire, pas comme valeur courante.

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

**Nouvelle mesure (2026-08-06, `scripts/benchmark_speedup.py`).** Médiane **20776 ms** à 1 worker
(valeurs brutes des 3 runs : 21228, 20776, 20365 ms) vs médiane **7218 ms** à 4 workers (valeurs
brutes : 6364, 8779, 7218 ms) → **×2.88**, contre ×2.50 historique ci-dessus.

**Traçabilité partielle.** Le backend cloud actif est confirmé indirectement pour les 6 runs — le
script sort en erreur si la bannière backend n'est pas "Anthropic Claude Haiku", jamais déclenché
— mais la preuve textuelle littérale par run a été perdue à cause d'un bug d'encodage cosmétique
sur cette exécution précise (le crash a eu lieu avant l'écriture du log de confirmation, corrigé
séparément voir commit `45b8c1b`).

**×2.88 vs ×2.50 : même zone, pas identiques.** Deux environnements/machines différents suffisent
à expliquer l'écart — pas de recherche d'une justification plus précise sans données comparatives
supplémentaires (même caveat de méthodologie que ci-dessus : petit échantillon, une seule
machine, variance réseau cloud non contrôlée).

## Golden dataset

Cas de test chargés depuis `golden_dataset.json` (racine du projet) par `EvalMain`, plus codés en
dur dans le source. 5 cas : les 3 originaux (EJB `ClientServiceBean`, Struts `CommandeAction`,
Singleton `ConfigurationManager`) inchangés, + 2 nouveaux (`InvoiceDao` — injection SQL et fuite de
ressource ; `AuditLogServiceImpl` — exception avalée silencieusement).

**0.802 sur les 5 cas est un chiffre HISTORIQUE**, mesuré avant la correction `FIELD_PATTERN` et
donc périmé. Les 2 cas ajoutés évitaient par construction le bug décrit plus bas, ce qui tirait
mécaniquement la moyenne `DependencyMapper` vers le haut : ce n'était pas une amélioration du
pipeline, seulement un effet de composition du dataset. La valeur courante est celle du tableau
de tête, mesurée sur les 3 cas historiques après correction.

**0.757 — chiffre HISTORIQUE, plus comparable à rien d'actuel.** À l'époque de l'externalisation
du dataset, ré-exécuter `EvalMain` sur les 3 cas d'origine seuls redonnait exactement 0.757, ce
qui confirmait que le passage du hardcode au chargement JSON n'avait rien changé au calcul. Mais
ce 0.757 **reposait sur le bug `FIELD_PATTERN`**, corrigé depuis : la comparaison directe avec
toute mesure actuelle n'a plus de sens. Il n'est conservé ici que comme repère historique.

**Limite connue — le F1 n'est pas parfaitement reproductible d'un run à l'autre.** La composante
déterministe (`DependencyMapperAgent`, regex, sans LLM) est bit-identique entre deux runs
indépendants sur les mêmes 3 cas (F1 = 0.571 / 0.933 / 0.000, à chaque fois). Les composantes qui
dépendent du LLM (rappel de mots-clés sur risques et responsabilités) varient réellement d'un
appel à l'autre, même à température 0.1 — observé concrètement lors de cette session : le F1
"Risques" du cas 1 est sorti à 0.923 puis 0.833 sur deux runs successifs, mêmes code et mêmes clés
de vérité. Traiter un F1 comme une valeur figée serait trompeur : ce sont des points de mesure,
pas des constantes. "0.757" et "0.802" sont de surcroît antérieurs à la correction
`FIELD_PATTERN` — purement historiques.

**`DependencyMapperAgent.FIELD_PATTERN` — bug CORRIGÉ (commit dédié).** Sur un champ
`private static final Type name`, l'ancien pattern capturait `"static"` comme si c'était le type,
jamais `Type` : `Logger` (cas1) et `Map` (cas3) n'étaient jamais extraits malgré leur présence
dans le code. Le pattern saute désormais les modificateurs, tolère les génériques, et exige
`;` ou `=` derrière le nom pour ne pas confondre un constructeur avec un champ.

**Conséquence directe : le F1 n'est plus comparable au 0.757 historique**, qui reposait sur ce
bug. Effet mesuré sur la composante déterministe (regex, sans LLM, donc exacte) des 3 cas
historiques :

| | cas1 | cas2 | cas3 | moyenne |
|---|---|---|---|---|
| Avant correction | 0.571 | 0.933 | **0.000** | **0.502** |
| Après correction | 0.714 | 0.933 | **0.500** | **0.716** |

Le `0.000` du cas 3 s'expliquait par un cumul : ce cas est le seul **sans aucun `import`**, donc
le type de champ y était l'unique voie vers `Map` — précisément celle que le bug fermait.
Mesures complètes et sorties brutes : `results/f1_ollama.json` et `results/raw/`.

**Deux limites subsistent, volontairement non corrigées** (à valider sur les cas 4 et 5, qui
n'ont pas servi à concevoir la correction) : `HashMap` n'apparaît que dans `new HashMap<>()` et
aucun motif ne regarde les instanciations ; et une classe peut encore apparaître comme
dépendante d'elle-même (`ConfigurationManager`), ce qui plafonne le cas 3 à 0.500.

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
| Orchestration | Java (`LegacyMigrationOrchestrator`) + LangChain4j `AiServices` |
| Observability | Prometheus · Grafana |
| Tracing | Phoenix OTEL |
| Flight recorder | TimescaleDB (spans run/agent/llm) — désactivé par défaut |
| Deployment | Docker Compose (8 services) |

## Flight recorder (spans → TimescaleDB)

Enregistre chaque étape du pipeline comme un span dans l'hypertable `agent_span`,
pour répondre dans Grafana à des questions que `RunMetrics`, Prometheus et Phoenix
ne couvrent pas sur l'historique : P95 par agent, coût par analyse, taux d'erreur.

Trois natures de span : `run` (une analyse complète), `agent` (une étape d'agent),
`llm` (un appel au modèle).

**Désactivé par défaut.** Sans `FLIGHTREC_ENABLED=true`, aucun thread, aucune
connexion, aucun effet mesurable sur le pipeline.

### Base cible

> ⚠️ La base à utiliser est **`legacyrec`**. Ne jamais pointer le recorder sur
> `flightrec`, qui contient les données du cours TimescaleDB. Les deux bases
> cohabitent dans le même conteneur `timescale-course` — seul le nom de base les
> sépare, c'est le seul garde-fou.

| Cas | `FLIGHTREC_URL` |
|-----|-----------------|
| Base locale existante (conteneur `timescale-course`) | `jdbc:postgresql://localhost:5432/legacyrec` |
| Profil Docker Compose (voir ci-dessous) | `jdbc:postgresql://localhost:5433/legacyrec` |

Le mot de passe n'a **aucune valeur par défaut en dur** : renseigner
`FLIGHTREC_PASSWORD` (ou, à défaut, la variable standard `PGPASSWORD`).

### Initialiser le schéma

Sur la base locale déjà créée :

```powershell
psql "postgresql://postgres@localhost:5432/legacyrec" -v ON_ERROR_STOP=1 -f monitoring/flightrec/init.sql

# si psql n'est pas installé sur l'hôte
Get-Content monitoring/flightrec/init.sql | docker exec -i timescale-course psql -U postgres -d legacyrec -v ON_ERROR_STOP=1
```

Le script est idempotent et peut être rejoué. Il exige **PostgreSQL 18+** :
`uuid_extract_timestamp()`, utilisée par la contrainte `CHECK` de la table,
n'existe pas avant — le script s'arrête avec un message explicite sinon.

Alternative conteneurisée, qui applique `init.sql` toute seule au premier démarrage
et n'interfère pas avec la base locale :

```bash
docker compose --profile flightrec up -d timescaledb   # port hôte 5433
```

### Lancer le pipeline avec le recorder

```bash
FLIGHTREC_ENABLED=true \
FLIGHTREC_URL=jdbc:postgresql://localhost:5432/legacyrec \
FLIGHTREC_PASSWORD=... \
java -jar agent/target/java-legacy-agent-1.0.0.jar demo-project migration-output
```

Les phases `analyze` et `report` sont deux process JVM distincts : partager
`FLIGHTREC_RUN_ID` entre les deux les regroupe sous un même `run_id`. Un run porte
alors **deux racines** (`analyze` et `report`), ce qui est la topologie réelle et
non une anomalie.

### Rejouer un run

```bash
psql "postgresql://postgres@localhost:5432/legacyrec" -f monitoring/flightrec/replay_last_run.sql
```

Arbre `run → agents → appels LLM`, plus les agrégats P95 / coût / taux d'erreur.

### Garanties

- **Ne fait jamais échouer le pipeline** : file bornée, dépôt non bloquant, aucune
  exception ne remonte, même base absente.
- **Ne perd jamais silencieusement** : les spans perdus sont comptés séparément
  (file pleine vs base injoignable) et affichés à la fermeture.
- **Minimisation** : `attributes` ne contient jamais de prompt, de réponse de modèle,
  de code source ni de secret — pas même `exception.message`, seulement le type.
- **Coût illustratif** : `cost_usd` est une estimation à tarif catalogue
  (`ModelPricing.java`), jamais une dépense constatée ; modèles locaux à 0.

## Project structure

```
agent/          Java source — AST parsing, agents, orchestrator (Maven)
airflow/        DAG definitions for pipeline scheduling
monitoring/     Prometheus config + Grafana dashboard
monitoring/flightrec/  Schema TimescaleDB (init.sql) + requete de replay
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

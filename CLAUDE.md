# Pipeline de modernisation Java legacy

Projet **100 % Java / Maven** (module `agent/`, Java 21). Les seuls fichiers Python du dépôt
sont `scripts/benchmark_speedup.py` et un DAG Airflow — ni l'un ni l'autre n'est le pipeline.

## Architecture (ne jamais dévier de ceci)

7 agents, mais **tous ne sont pas dans le pipeline de migration** — la distinction compte
avant de modifier quoi que ce soit :

| Agent | Où il tourne |
|---|---|
| FileScannerAgent, AstParserAgent, JavaDocumentationAgent, MigrationPlannerAgent | pipeline (`LegacyMigrationOrchestrator`) |
| CallGraphAgent | modes CLI `impact` / `serve` (`BreakingChangeDetector`) uniquement |
| DependencyMapperAgent, AgentEvaluator | mode CLI `eval` (`EvalMain`) uniquement |

\+ RefactoringAdvisor, LegacyMigrationOrchestrator.

Stack : JavaParser, **LangChain4j 0.36.2** (`AiServices`), Claude API / Qwen on-prem.
**Il n'y a pas de LangGraph dans ce projet** — aucune dépendance, aucune classe dans le jar.
L'orchestration est du Java impératif écrit à la main plus un `ExecutorService` brut
(`Executors.newFixedThreadPool`, un seul `pool.submit`, `LegacyMigrationOrchestrator`).

Observabilité : Prometheus (Pushgateway) / Grafana / Phoenix OTEL
\+ flight recorder TimescaleDB (`observability/`, désactivé par défaut).

## Commandes

- Build + tests : `mvn -f agent/pom.xml test`
- Packaging : `mvn -f agent/pom.xml package` → `agent/target/java-legacy-agent-1.0.0.jar`
- Éval F1 : `java -cp agent/target/java-legacy-agent-1.0.0.jar com.audensiel.legacy.agent.EvalMain [dataset.json]`
  (ou `java -jar ... eval`). Datasets : `golden_dataset.json` (5 cas),
  `golden_dataset_3cases.json` (les 3 cas historiques).
- Run pipeline (4 workers) : `AGENT_WORKERS=4 java -jar agent/target/java-legacy-agent-1.0.0.jar <projet> [sortie]`
- Benchmark speedup : `python scripts/benchmark_speedup.py`
- Tests d'intégration (base réelle) : `mvn -f agent/pom.xml test -Dgroups=integration -DexcludedGroups=`
- Microbenchmark recorder : `mvn -f agent/pom.xml test -Dgroups=bench -DexcludedGroups=`

Il n'y a **ni pytest, ni ruff, ni `run_pipeline.py`, ni `scripts/eval_golden_dataset.py`**
dans ce dépôt.

## Règles non négociables

- **Ne jamais annoncer un chiffre non mesuré.** Si un run donne un chiffre différent de
  celui du README, c'est CE chiffre qui compte. Points de mesure actuels, avec leurs
  conditions — ce sont des points de mesure, pas des constantes :
  - speedup **×2.88** (médianes 20776 ms à 1 worker vs 7218 ms à 4 workers,
    `scripts/benchmark_speedup.py`, backend Claude Haiku) ; ×2.50 est l'ancienne valeur,
    sur une autre machine.
  - F1 **0.802** sur les 5 cas de `golden_dataset.json`, **0.757** sur les 3 cas historiques
    (valeur du CV). Les deux ne sont pas comparables entre elles.
  - **Le F1 global n'est pas reproductible à l'identique.** Seule la composante
    `DependencyMapper` (regex, sans LLM) l'est, au bit près. Les composantes LLM (risques,
    responsabilités) dérivent d'un run à l'autre même à température 0.1, et dépendent du
    backend actif. Toujours préciser le backend en rapportant un F1.
  - **`FIELD_PATTERN` a été corrigé** : la composante déterministe est passée de
    0.571/0.933/0.000 (moyenne **0.502**) à 0.714/0.933/0.500 (moyenne **0.716**) sur les
    3 cas historiques. **Le F1 n'est donc plus comparable au 0.757**, qui reposait sur ce
    bug. Mesures et sorties brutes : `results/f1_ollama.json`, `results/raw/`.
  - **Deux serveurs Ollama peuvent écouter sur 11434** — le natif Windows sur `127.0.0.1`
    (1 modèle) et le conteneur Docker sur `[::1]` via le relais WSL (3 modèles). `localhost`
    est donc ambigu selon la résolution IPv4/IPv6. Épingler
    `OLLAMA_BASE_URL=http://127.0.0.1:11434` et vérifier dans chaque log quelle instance a
    répondu (`/api/tags` en tête, `/api/ps` en pied) — voir `scripts/f1_passages.sh`.
- **JavaParser n'est pas thread-safe avec AGENT_WORKERS>1.** Le garde-fou n'est pas un
  verrou : c'est `AstParserAgent.newParser()`, qui crée une instance neuve par appel et
  supprime l'état partagé (commit `4128e54`, après un bug où une instance partagée levait des
  `ConcurrentModificationException` silencieusement avalées — chaque classe retombait sur une
  analyse vide, le pipeline rapportant un succès). **Ne jamais faire partager une instance
  `JavaParser` entre workers, et ne pas introduire de verrou partagé autour du parsing non
  plus** — ce serait revenir en arrière sur ce correctif.
  À noter : `CallGraphAgent` porte encore un champ `JavaParser` partagé ; il n'est
  aujourd'hui appelé que depuis des chemins mono-thread, ce qui le rend sûr par accident et
  non par construction.
- Toute modification touchant à `AstParserAgent` ou `CallGraphAgent` doit repasser le
  Golden Dataset avant merge.
- Le flight recorder ne doit jamais faire échouer le pipeline, ni perdre des spans
  silencieusement, ni enregistrer de prompt, de réponse de modèle, de code source ou de
  secret dans `attributes` (pas même `exception.message`). Base cible **`legacyrec`**,
  jamais `flightrec` (données du cours TimescaleDB, même conteneur).
- **Aucune commande Docker ou Docker Compose sans accord explicite de Stéphane.** Jamais
  de suppression de volume, d'élagage, ni d'arrêt de composition avec suppression des
  volumes : ce dernier drapeau ne se limite pas au profil visé et détruit les volumes de
  **tous** les services du projet. C'est arrivé une fois, les données étaient
  irrécupérables. Un hook `PreToolUse` refuse désormais ces commandes
  (`.claude/hooks/block-docker-destructive.py`) — pour ne retirer qu'un service,
  `docker compose rm -sf <service>`.

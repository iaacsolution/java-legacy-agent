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
  (ou `java -jar ... eval`), options `--deterministic-only` (aucun modèle construit) et
  `--json <fichier>` (résultat structuré, insensible à la locale). Datasets : `golden_dataset.json` (5 cas),
  `golden_dataset_3cases.json` (les 3 cas historiques).
- Run pipeline (4 workers) : `AGENT_WORKERS=4 java -jar agent/target/java-legacy-agent-1.0.0.jar <projet> [sortie]`
- Benchmark speedup : `python scripts/benchmark_speedup.py`
- Tests d'intégration (base réelle) : `mvn -f agent/pom.xml test -Dgroups=integration -DexcludedGroups=`
- Microbenchmark recorder : `mvn -f agent/pom.xml test -Dgroups=bench -DexcludedGroups=`
- Éval contre baseline (`eval/baseline.json`) :
  `python scripts/eval_golden_dataset.py --mode deterministic` (sans LLM, **seul contrôle
  bloquant**, TP/FP/FN exacts) ou `--mode full [--passes N]` (backend actif, alerte non
  bloquante). Rapports dans `eval/reports/`. CI : `.github/workflows/eval.yml`.

Il n'y a **ni pytest, ni ruff, ni `run_pipeline.py`** dans ce dépôt.

## Règles non négociables

- **Ne jamais annoncer un chiffre non mesuré.** Si un run donne un chiffre différent de
  celui du README, c'est CE chiffre qui compte : ce sont des points de mesure, pas des
  constantes.

  **VALEURS COURANTES** — chacune avec son backend, elles ne sont pas interchangeables :
  - F1 **0.808**, médiane de 3 passages, étendue **0.763–0.844** — `golden_dataset_3cases.json`,
    **Ollama local `qwen2.5-coder:7b`**, température 0.1, après correction `FIELD_PATTERN`.
    Source : `results/f1_ollama.json`, sorties brutes dans `results/raw/`.
  - composante déterministe **0.716** (`DependencyMapper`, regex, sans LLM) — exacte, identique
    aux 3 passages. Elle valait **0.502** avant la correction : **c'est là, et seulement là, que
    se lit l'effet de `FIELD_PATTERN`**. Le F1 global inclut en plus la dérive LLM, son écart ne
    lui est pas attribuable.
  - speedup **×2.88** (médianes 20776 ms à 1 worker vs 7218 ms à 4 workers,
    `scripts/benchmark_speedup.py`, **backend Claude Haiku**).
  - surcoût du recorder **0.448 µs/span**, étendue 0.391–0.492, et débit **23 145 spans/s** —
    microbenchmark sans LLM. Source : `results/flightrec_bench.json`. **Ne jamais citer ce
    chiffre de mémoire** : il varie d'un run à l'autre, le relire dans le fichier.

  **VALEURS HISTORIQUES** — conservées comme repères, à ne jamais comparer aux courantes :
  - **0.757** (3 cas) et **0.802** (5 cas) : antérieurs à la correction `FIELD_PATTERN` et
    appuyés sur ce bug. 0.757 est la valeur du CV — elle n'est plus reproductible ni comparable.
  - speedup **×2.50** : mesure antérieure, autre machine, également sur Claude Haiku.

  **RÈGLES DE LECTURE :**
  - **Le F1 global n'est pas reproductible à l'identique.** Seule la composante
    `DependencyMapper` (regex, sans LLM) l'est, au bit près. Les composantes LLM (risques,
    responsabilités) dérivent d'un run à l'autre même à température 0.1, et dépendent du
    backend actif. Toujours préciser le backend en rapportant un F1.
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
- **Un tableau de bord n'est validé que lorsqu'il affiche des données DANS Grafana**, pas
  quand ses requêtes fonctionnent en SQL direct. Les deux ont été confondus une fois : les
  requêtes du tableau de bord renvoyaient bien les lignes attendues dans `psql`, et tous les
  panneaux étaient pourtant vides. Deux causes, aucune visible en SQL :
  - la variable `$project` était de type `constant`, déprécié et plus résolu par Grafana 12 —
    elle restait vide, donc chaque panneau filtrait sur un projet inexistant ;
  - la source de données ne se connectait pas, parce que Grafana n'interprète dans ses
    fichiers de provisionnement que `$VAR` et `${VAR}`, **jamais** `${VAR:-défaut}`
    (qu'il résout à vide) ;
  - le nom de base était renseigné au premier niveau (`database:`) et pas dans
    `jsonData.database`, seul emplacement lu par Grafana 12 : la source se provisionne
    sans erreur, puis **toute requête** échoue sur « Aucune base de données par défaut
    n'est configurée pour cette source de données » ;
  - un panneau tracé avec `time_bucket` relie les tranches vides par un segment, donnant
    à lire une activité continue là où il n'y en a aucune. Il faut `time_bucket_gapfill`
    **et** `spanNulls: false` : gapfill seul produit des NULL que Grafana relierait quand
    même ;
  - **`time_bucket_gapfill` avec les macros Grafana en arguments positionnels échoue sur
    « time zone ... not recognized ».** Grafana substitue `$__timeFrom()` par une chaîne
    **non typée** ; or TimescaleDB a une surcharge
    `(interval, timestamptz, timezone text, start, finish)` dont le 3ᵉ paramètre est un
    fuseau horaire, et PostgreSQL la préfère parce qu'un littéral non typé se convertit
    plus volontiers en `text` qu'en `timestamptz`. Écrire les paramètres **nommés** avec
    casts explicites :
    `time_bucket_gapfill('$__interval'::interval, time, start => $__timeFrom()::timestamptz, finish => $__timeTo()::timestamptz)`

  Cinq pièges, cinq symptômes différents, et **aucun n'est visible en exécutant le SQL à
  la main** — il marchait dans les cinq cas. Le dernier a même été masqué par un test
  trop favorable : la requête avait été éprouvée avec `now() - interval '40 minutes'`,
  des expressions **déjà typées**, alors que Grafana envoie des chaînes brutes.
  **Pour tester une requête de panneau, reproduire ce que Grafana envoie réellement** :
  macros remplacées par des chaînes non typées (`'2026-10-01T07:00:00Z'`), jamais par des
  dates typées ni par `now()`.

  Vérifier dans l'interface, ou à défaut par l'API (`/api/ds/query`, qui exécute la
  requête *telle que Grafana la construit*, variables substituées ;
  `/api/datasources/uid/<uid>/health` pour la connexion). Le diagnostic de santé est
  précis et mérite d'être lu en entier : il distinguait `config_database_length` de
  `config_json_data_database_length`, et `config_user_length` de
  `config_password_length` — chacun pointait la vraie cause avant qu'elle soit comprise.
- **Aucune commande Docker ou Docker Compose sans accord explicite de Stéphane.** Jamais
  de suppression de volume, d'élagage, ni d'arrêt de composition avec suppression des
  volumes : ce dernier drapeau ne se limite pas au profil visé et détruit les volumes de
  **tous** les services du projet. C'est arrivé une fois, les données étaient
  irrécupérables. Un hook `PreToolUse` refuse désormais ces commandes
  (`.claude/hooks/block-docker-destructive.py`) — pour ne retirer qu'un service,
  `docker compose rm -sf <service>`.

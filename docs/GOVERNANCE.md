# Gouvernance des agents — matrice des droits, écarts, état des piliers

Lot 3 de [`specs/gouvernance-spec.md`](../specs/gouvernance-spec.md). État relevé sur la branche
`gov/lot3-permissions`. Les numéros de ligne renvoient à cet état du code.

## Méthode

- **Chaque cellule de la matrice porte sa preuve** : soit un `fichier:ligne` lu dans le
  code, soit la mention « aucun », qui renvoie au test `AccessAllowlistTest`, soit la
  mention « non vérifié ».
  - Les chemins sont relatifs à `agent/src/main/java/com/audensiel/legacy/agent/`.
- **« Aucun » est vérifié en CI.** `AccessAllowlistTest` (`agent/src/test/java/.../AccessAllowlistTest.java`)
  analyse le source de toutes les classes. Il échoue dès qu'une classe hors liste utilise
  l'une de ces API :
  - écriture ou lecture disque (`Files.*`, flux de fichiers) ;
  - réseau (`HttpServer`, `HttpClient`, `DriverManager`, sockets, OTLP) ;
  - construction d'un modèle LLM ;
  - `System.getenv` ou `getProperty` ;
  - lancement de processus.

  Il échoue aussi quand une classe listée ne l'utilise plus. **Son allowlist est le miroir
  exact de cette matrice : toute modification de l'une impose de modifier l'autre.**
- **Hors de portée du test** :
  - la réflexion ;
  - ce que font les bibliothèques (LangChain4j, logback) ;
  - un accès fait au travers d'une classe autorisée. C'est pourquoi la colonne « via »
    attribue chaque accès au composant qui le déclenche.
- **Régénérer l'inventaire** (depuis la racine du dépôt) :
  ```bash
  A=agent/src/main/java/com/audensiel/legacy/agent
  grep -nE 'Files\s*(\.|::)\s*(write|writeString|newBufferedWriter|createDirectories|createTempDirectory|deleteIfExists|readString|readAllBytes|readAllLines|walk|walkFileTree)|HttpServer\.create|HttpClient\.new|DriverManager\.getConnection|OtlpGrpcSpanExporter\.builder|ChatModel\.builder|AiServices\.create|System\s*(\.|::)\s*getenv|env\.apply\(|envOr\(' $A/*.java $A/*/*.java
  mvn -f agent/pom.xml test -Dtest=AccessAllowlistTest
  ```

## Matrice des droits — les 9 composants

| Composant | Où il tourne | Lecture fichiers | Écriture fichiers | Appel LLM | TimescaleDB | Variables d'environnement / secrets |
|---|---|---|---|---|---|---|
| **FileScannerAgent** | pipeline (`LegacyMigrationOrchestrator.java:58`, `:115`) | tous les `*.java` sous le projet analysé (`FileScannerAgent.java:23`, `:27`) | aucune (`AccessAllowlistTest`) | non (`AccessAllowlistTest`) | non | aucune (`AccessAllowlistTest`) |
| **AstParserAgent** | pipeline (`LegacyMigrationOrchestrator.java:59`, `:153`, `:273`) | aucune : contenu reçu en mémoire (`AccessAllowlistTest`) | aucune | non : produit le squelette que JavaDocumentationAgent envoie | non | aucune |
| **CallGraphAgent** | modes `impact` / `serve` uniquement, via `BreakingChangeDetector.java:89`, `:100` | tous les `*.java` sous le projet (`CallGraphAgent.java:71`, MD5 `CallGraphCache.java:160`) ; son cache (`CallGraphCache.java:94`) | son cache, sous `AGENT_STATE_DIR/callgraph/` (`CallGraphCache.java:78`, `:137`). Refus si ce dossier est dans le projet analysé (`StateDir.java:49`) | non | indirect : span `run` du mode `impact` (`Main.java:110`) si `FLIGHTREC_ENABLED=true` | `AGENT_STATE_DIR` (`CallGraphAgent.java:37` → `StateDir.java:41`) |
| **DependencyMapperAgent** | mode `eval` uniquement (`EvalMain.java:139`, `:244`) | aucune : contenu en mémoire (`AccessAllowlistTest`) | aucune | non | non | aucune |
| **JavaDocumentationAgent** | pipeline (`LegacyMigrationOrchestrator.java:60`, `:175`, `:330`), `eval` (`EvalMain.java:106`), `plan` / démo (`Main.java`) | aucune (`AccessAllowlistTest`) | aucune | **oui** : 4 proxys (`JavaDocumentationAgent.java:154-157`), appels `:177` (code brut), `:204` (squelette), `:226` (corps de méthode), `:245` (DAT). Modèle construit par `LlmModelFactory.java:129` / `:143` / `:156`, toujours filtré (`RedactingChatModel`) | span `llm` par appel (écouteur `LlmModelFactory.java:113-114`) et span `redaction` (`RedactingChatModel.java:166`), si `FLIGHTREC_ENABLED=true` | `ALLOW_CLOUD_RAW_CODE` (`JavaDocumentationAgent.java:171`), et via la factory : `VLLM_BASE_URL`, **`ANTHROPIC_API_KEY`** (secret), `ALLOW_CLOUD_CODE_ANALYSIS`, `LLM_REDACTION`, `LLM_REDACTION_POLICY` (`LlmModelFactory.java:124`, `:125`, `:43`, `:174`, `:175`) |
| **MigrationPlannerAgent** | pipeline, phase `report` (`LegacyMigrationOrchestrator.java:61`, `:340`), `plan` | aucune | aucune | **oui** : `MigrationPlannerAgent.java:53`, `:58`, via la factory, filtré | span `llm` (même écouteur) | via la factory, les mêmes variables que ci-dessus |
| **AgentEvaluator** | mode `eval` uniquement (`EvalMain.java:138`, `:255`, `:267`) | aucune | aucune : sortie console seulement (`AgentEvaluator.java:82`) | non | span racine `eval` (`EvalMain.java:147`) | aucune |
| **RefactoringAdvisor** | modes `impact` / `serve` (`BreakingChangeDetector.java:90`, `:118`) | aucune | aucune | non : règles statiques | non | aucune |
| **LegacyMigrationOrchestrator** | point d'entrée du pipeline (`Main.java`, modes `analyze` / `report` / complet) | handoff : `LegacyMigrationOrchestrator.java:302` → `HandoffBundle.java:37-41` ; parcours du handoff temporaire pour le supprimer : `LegacyMigrationOrchestrator.java:471` | handoff temporaire : `LegacyMigrationOrchestrator.java:73`, puis `LegacyMigrationOrchestrator.java:275` → `HandoffBundle.java:21-24` ; `metrics_<projet>.json` : `LegacyMigrationOrchestrator.java:278`, `LegacyMigrationOrchestrator.java:355` → `RunMetrics.java:199`, `RunMetrics.java:201` ; rapport `migration_<projet>_<date>.md` : `LegacyMigrationOrchestrator.java:350`, `LegacyMigrationOrchestrator.java:352` ; suppression du handoff : `LegacyMigrationOrchestrator.java:458`, échecs journalisés en WARN | indirect, par JavaDocumentationAgent et MigrationPlannerAgent | spans `run` / `agent` : `LegacyMigrationOrchestrator.java:90`, `LegacyMigrationOrchestrator.java:103`, `LegacyMigrationOrchestrator.java:139`, `LegacyMigrationOrchestrator.java:151`, `LegacyMigrationOrchestrator.java:313`. Réseau, Pushgateway : `LegacyMigrationOrchestrator.java:280`, `LegacyMigrationOrchestrator.java:357` → `MetricsPusher.java:30` | `AGENT_WORKERS` : `LegacyMigrationOrchestrator.java:125` |

### Classes de support (annexe)

| Classe | Rôle et accès | Preuve |
|---|---|---|
| `Main` | point d'entrée. Lit `OLLAMA_BASE_URL`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `PUSHGATEWAY_URL` ; démarre le recorder | `Main.java:76`, `:77`, `:92`, `:84` |
| `EvalMain` | lit le dataset ; écrit `--json` ; lit `OLLAMA_BASE_URL` | `EvalMain.java:225`, `:197-199`, `:90` |
| `HandoffBundle` | lit et écrit `specs.md`, `dependencies.md`, `manifest.txt` | `HandoffBundle.java:21-24`, `:37-41` |
| `RunMetrics` | écrit `metrics_<projet>.json` | `RunMetrics.java:199`, `:201` |
| `RoiLogger` | écrit `impact-roi.csv` dans `AGENT_STATE_DIR` (mode `impact`, changement cassant) | `RoiLogger.java:24`, `:26`, `:42` |
| `CallGraphCache` | lit et écrit le cache du call graph, un fichier par projet | `CallGraphCache.java:64`, `:78`, `:94`, `:137`, `:160` |
| `StateDir` | résout `AGENT_STATE_DIR` et refuse un dossier situé dans le projet analysé ; signale les fichiers orphelins d'avant le Lot 3 | `StateDir.java:41`, `:49` |
| `LlmModelFactory` | **seul** site de construction de modèle ; pose la redaction sur les 3 backends | `LlmModelFactory.java:129`, `:143`, `:156` |
| `governance.RedactingChatModel` | filtre secrets et PII avant tout envoi au LLM ; écrit dans le flight recorder des comptages seulement | `RedactingChatModel.java:166` |
| `MetricsPusher` | HTTP PUT vers le Pushgateway (nombres et libellés) | `MetricsPusher.java:30`, `:44` |
| `PipelineTracer` | export OTLP gRPC vers Phoenix | `PipelineTracer.java:27` |
| `ImpactServer` | serveur HTTP `/impact` et `/health`, **sans authentification**. Écoute en boucle locale par défaut, sur toutes les interfaces si `IMPACT_SERVER_BIND_ALL=true`. Réponse 500 générique | `ImpactServer.java:49`, `:52`, `:53` |
| `observability.SpanRecorder` | connexion JDBC à TimescaleDB (base `legacyrec`). Lit `FLIGHTREC_ENABLED`, `FLIGHTREC_URL`, `FLIGHTREC_USER`, `FLIGHTREC_QUEUE_CAPACITY`, `FLIGHTREC_RUN_ID`, `FLIGHTREC_SESSION_ID`, **`FLIGHTREC_PASSWORD`** puis `PGPASSWORD` (secrets) | `SpanRecorder.java:118`, `:101`, `:107`, `:108`, `:110`, `:112`, `:119`, `:153`, `:154` |
| `observability.SpanWriter` | seule classe qui écrit en base : `COPY agent_span`, connexion fournie par `SpanRecorder` | `SpanWriter.java:51` |

### Accès transverses, hors matrice

- **Journaux (logback)** : toutes les classes écrivent, par logback, dans
  `${AGENT_LOG_DIR:-/app/logs}/agent.json` (`agent/src/main/resources/logback.xml`).
  Configuration lue, mais le comportement de la bibliothèque n'est **pas vérifié**.
- **Clients HTTP des modèles** (LangChain4j) : appels vers vLLM, Anthropic ou Ollama.
  **Non vérifié** dans le code de la bibliothèque. Le payload envoyé, lui, est vérifié
  filtré par les tests du Lot 2.
- **Variables d'environnement** : un processus Java ne cloisonne pas son environnement par
  composant. Voir l'écart E5.

## Écarts au moindre privilège

### Corrigés au Lot 3

| # | Écart | Correction | Preuve |
|---|---|---|---|
| E1 | `CallGraphAgent`, un agent d'analyse, écrivait son cache dans le **grand-parent** du projet analysé (`/home` pour `/home/x/proj`) ; `RoiLogger` aussi | dossier d'état `AGENT_STATE_DIR`, hors du projet (refus explicite sinon, comparé aussi sur le chemin réel : un lien symbolique qui pointe dans le projet est refusé). Les fichiers orphelins sont signalés en WARN, jamais supprimés | `CallGraphCacheCorrectnessTest` |
| E2 | Le cache du call graph pouvait **sous-estimer les appelants**. Un changement cassant passait alors pour sûr :<br>• appel depuis un fichier modifié vers un fichier inchangé perdu ;<br>• entrée entière purgée dès qu'un appelant changeait ;<br>• fichier supprimé conservant ses appels ;<br>• cache partagé entre projets voisins.<br>Sous Windows, le cache n'était jamais valide (antislashs non rétablis à la lecture) | cache par projet, utilisé seulement si le contenu est identique ; sinon scan complet | `CallGraphCacheCorrectnessTest` (8 cas, vérifiés par mutation) |
| E3 | Le nettoyage du handoff temporaire avalait ses erreurs (`catch (IOException ignored)`) : les spécifications produites par le LLM pouvaient rester sur disque sans trace | échecs renvoyés et journalisés en WARN | `HandoffCleanupTest` |
| E4 | `ImpactServer`, sans authentification, renvoyait `e.getMessage()` au client, non échappé (chemins du serveur) | réponse 500 générique ; le détail reste dans les journaux | `ImpactServerErrorTest` |
| E6 | Le hook pre-commit laissait passer le commit en silence quand l'analyse d'impact échouait (Docker absent, démon arrêté, image manquante, analyse plantée) | chaque cas bloque le commit, avec un diagnostic distinct et un rappel de `--no-verify` | `hooks/test-impact-lib.sh` (14 cas, faux docker, en CI). **Constaté en conditions réelles** : l'image `java-legacy-agent-java-agent` n'existait plus (service « java-agent » scindé en `java-analyzer` / `java-reporter`) ; l'ancien hook laissait passer, le nouveau bloque ([preuve](../eval/reports/2026-10-16_hook_image_absente/LISEZMOI.md)). Défaut corrigé : `java-legacy-agent-java-analyzer`, surchargeable par `AGENT_IMAGE` ; bout en bout vérifié sur cette image |
| E7 | Le garde-fou anti-Docker de Claude Code était appelé par un chemin relatif : il tombait dès que le shell quittait la racine du dépôt | appel par `$CLAUDE_PROJECT_DIR` ; le hook Stop se place à la racine | testé depuis `agent/src` (voir le rapport du Lot 3) |

### Restants, documentés

| # | Écart | Pourquoi il reste | Piste |
|---|---|---|---|
| E5 | **Environnement non cloisonné.** Tout composant d'un processus peut lire `ANTHROPIC_API_KEY` ou `FLIGHTREC_PASSWORD` | Java n'offre pas de cloisonnement par classe (`SecurityManager` désactivé depuis Java 18). Seule barrière : la séparation des conteneurs. `java-analyzer` ne reçoit pas la clé (`docker-compose.yml:46-54`) ; `java-reporter` la reçoit (`:77`) | l'allowlist `ENV` limite **qui lit** l'environnement dans le code ; garder la séparation des conteneurs |
| E8 | Un **scan échoué est rapporté comme « aucun impact »** : `BreakingChangeDetector.java:103-107` renvoie la sévérité `NONE`, l'API répond 200 et `impact` sort en 0 | changer cette sémantique n'a pas été validé. Les hooks la traitent désormais comme un échec et bloquent, mais un appelant direct de l'API `/impact` ou de `impact` n'est pas protégé | **premier point du prochain lot** (`specs/gouvernance-spec.md`) : renvoyer une erreur explicite (code de sortie ≠ 0, HTTP 500) |
| E9 | **Mot de passe par défaut de la base** : `FLIGHTREC_PASSWORD:-flightrec-dev-local` (`docker-compose.yml:218`) | décision : documenter seulement, pas de modification Docker sans accord | exiger la variable (`${FLIGHTREC_PASSWORD:?}`) |
| E10 | `ImpactServer` n'a **aucune authentification** | il écoute en boucle locale par défaut (`ImpactServer.java:52`) ; l'exposition réseau exige `IMPACT_SERVER_BIND_ALL=true` | jeton partagé si une exposition devenait nécessaire |
| E11 | `analyzeJavaClass` peut envoyer du **code brut** au cloud avec `ALLOW_CLOUD_RAW_CODE=true` (job CI complet, dataset synthétique) | décision du Lot 2 ; garde bruyante sinon | envoyer le squelette AST en cloud, quand un budget permettra de remesurer la baseline Haiku |
| E12 | Les **écritures de la phase `analyze`** (handoff, métriques) vont dans des dossiers fournis par l'appelant, sans contrôle de leur emplacement | dossiers explicites en argument ; risque faible | les valider comme `StateDir` si l'outil devenait multi-utilisateur |

## État des 4 piliers

| Pilier | État | Justification |
|---|---|---|
| **Cycle de vie** | 🟡 | ✅ Une évaluation déterministe **bloquante** tourne en CI à chaque push (TP/FP/FN exacts), et les baselines sont versionnées avec leurs preuves (Lot 1).<br>🟡 L'évaluation LLM n'est mesurée **qu'en local** : le job complet en CI est prêt mais pas activé, faute de budget API. Le F1 global dérive d'un passage à l'autre et n'est qu'une alerte. |
| **Gestion des risques** | 🟡 | ✅ Secrets et PII sont filtrés avant **tout** LLM, sur les 3 backends (Lot 2), avec des tests sans LLM et une garde cloud bruyante.<br>🟡 Le filtrage repose sur des expressions régulières : des faux négatifs restent possibles, et ses bornes anti-ReDoS sont des faux négatifs assumés. Son effet sur la qualité d'analyse d'un code contenant des secrets n'a pas été mesuré. |
| **Sécurité** | 🟡 | ✅ Cette matrice est vérifiée dans le code et appliquée en CI (`AccessAllowlistTest`). Les écarts E1 à E4, E6 et E7 sont corrigés. Le hook pre-commit ne laisse plus passer un échec d'analyse.<br>❌ Pas de cloisonnement des secrets entre composants (E5), pas d'authentification sur `ImpactServer` (E10), mot de passe par défaut (E9), scan échoué rapporté comme « aucun impact » (E8). |
| **Observabilité** | 🟡 | ✅ Flight recorder TimescaleDB (spans `run` / `agent` / `llm` / `redaction`, jamais de contenu), tableaux de bord Grafana validés dans Grafana, Prometheus, Phoenix.<br>🟡 Le recorder est **désactivé par défaut** (`FLIGHTREC_ENABLED`). OpenTelemetry ne couvre pas les appels LLM eux-mêmes. Les journaux logback ne sont pas couverts par la matrice. |

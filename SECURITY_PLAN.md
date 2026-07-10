# Plan de sécurisation — SPIFFE/SPIRE + OWASP

Statut : recon fait le 2026-07-09, plan rédigé le 2026-07-10. **Étapes 1 à 4 (secrets, segmentation réseau, casser l'exfiltration/injection, séparation analyzer/reporter) implémentées le 2026-07-10.**

Contexte : préparation d'un entretien technique (2h, dans ~1 semaine ou plus) où il faut présenter un stack et un produit crédibles niveau production — chaque étape doit rester dans un état qui fonctionne et se raconte bien, pas de travail à moitié fait.

## Cadrage : les 3 fenêtres d'attaque (lethal trifecta)

Un agent LLM devient dangereux quand il cumule ces trois propriétés. `java-legacy-agent` les a toutes les trois :

1. **Accès à des données privées** — clé Anthropic, code source client (potentiellement sensible), dashboards internes.
2. **Connexions vers l'extérieur** — appels sortants vers l'API Anthropic, push/pull vers Hugging Face.
3. **Ingestion de contenu non fiable** — le pipeline lit et injecte dans des prompts LLM des fichiers `.java` arbitraires (code legacy d'un client), sans savoir ce qu'ils contiennent.

Le risque n'est pas chaque fenêtre isolément, c'est leur combinaison : du code legacy malveillant (fenêtre 3) pourrait manipuler le LLM pour exfiltrer un secret (fenêtre 1) via un appel réseau (fenêtre 2).

---

## Fenêtre 1 — Données privées

**État actuel (recon confirmé)**
- `docker-compose.yml:87` `GF_SECURITY_ADMIN_PASSWORD=<valeur faible en dur>` (corrigé depuis, voir historique git)
- `docker-compose.yml:140` `_AIRFLOW_WWW_USER_PASSWORD=<même valeur réutilisée>` (corrigé depuis, voir historique git)
- `docker-compose.yml:142` `AIRFLOW__WEBSERVER__SECRET_KEY=java-legacy-demo-secret` (clé de signature de session non aléatoire)
- `LlmModelFactory.java:41` `.apiKey("EMPTY")` en dur pour vLLM (pas grave en soi, mais révèle l'absence totale d'auth sur ce chemin)
- Phoenix (port 6006, traces LLM prompts+réponses) exposé sans auth — si du code client réel y transite, fuite potentielle
- `hooks/pre-commit` : aucun secret-scanning

**Actions**
1. **[Fait]** Sortir tous les secrets en dur → `docker-compose.yml` lit `${GF_ADMIN_PASSWORD:?...}` / `${AIRFLOW_ADMIN_PASSWORD:?...}` / `${AIRFLOW_SECRET_KEY:?...}` (fail-fast si absent), valeurs réelles dans `.env` (gitignored, 3 valeurs distinctes générées aléatoirement), template `.env.example` committable. L'ancien mot de passe reste dans l'historique git (repo public) — purge d'historique (BFG/filter-repo) volontairement non faite, en attente d'une décision explicite car destructive sur remote partagé.
2. **[Fait]** Scan de secrets ajouté dans `hooks/pre-commit` — testé positif (bloque un secret réintroduit) et négatif (aucun faux positif sur un diff propre). `gitleaks` aussi ajouté à `.pre-commit-config.yaml`, mais **ce fichier était mort** : le hook réellement actif est le script brut dans `.git/hooks/pre-commit` (framework `pre-commit` pas installé) — c'est celui-là qui a été corrigé pour protéger vraiment.
3. SPIFFE/SPIRE comme identité de workload : chaque service reçoit un SVID X.509 émis par SPIRE Server, attesté par SPIRE Agent via des sélecteurs Docker (label ou cgroup). Ça permet une autorisation par identité plutôt que par "qui connaît le secret partagé" — seul le workload `java-agent` est autorisé à porter la charge liée à `ANTHROPIC_API_KEY`.
4. OWASP LLM06 (Sensitive Information Disclosure) : avant l'ingestion, scanner le code legacy analysé pour des patterns de secrets (creds JDBC en dur dans de vieux EJB, typique du legacy) afin d'éviter qu'ils remontent tels quels dans les traces Phoenix / dashboards Grafana.

---

## Fenêtre 2 — Connexions extérieures

**État actuel (recon confirmé)**
- Aucune section `networks:` dans `docker-compose.yml` → tous les services sur le bridge par défaut, joignables entre eux par nom de service sans aucune auth (`vllm:8000`, `pushgateway:9091`, `phoenix:4317`...).
- Seul egress légitime vers Internet : `api.anthropic.com` (client Anthropic dans `java-agent`) et `huggingface.co` (pull de modèle vLLM, push/pull HF Space). Aucun autre service n'a de raison de sortir sur Internet.

**Actions**
1. **[Fait]** Segmentation par tier au lieu du bridge plat unique : 3 réseaux Docker — `compute` (vllm, java-agent, open-webui), `observability` (pushgateway, prometheus, grafana, phoenix — java-agent y est aussi car il pousse métriques+traces), `orchestration` (airflow, isolé). Chaque service ne rejoint que les réseaux dont il a réellement besoin.
   - **Tentative initiale abandonnée** : `internal: true` sur le réseau semblait la solution évidente pour bloquer tout egress Internet en plus de l'isolation est-ouest. Testé empiriquement (`docker compose up` + `docker compose ps`) : avec `internal: true`, Docker Compose ne publie plus les ports côté host (`3000/tcp` au lieu de `0.0.0.0:3003->3000/tcp`) — inutilisable pour une démo où chaque UI doit rester accessible en `localhost`. D'où le choix de réseaux bridge normaux segmentés par tier (isolation est-ouest réelle) plutôt qu'un blocage d'egress illusoire.
   - **Validé par test** : `grafana` (tier observability) atteint `prometheus:9090` (même tier) ; `grafana` ne peut même pas résoudre `open-webui` (tier compute, DNS Docker ne résout que dans le même réseau) — isolation confirmée, pas juste déclarée.
2. **[Pas fait]** Le vrai blocage d'egress Internet (seuls vllm + java-agent devraient sortir) nécessite un mécanisme que docker-compose seul ne fournit pas proprement : proxy sortant (Squid/Envoy) en allowlist devant `java-agent`, ou en production une NetworkPolicy Kubernetes. Ce n'est pas juste "docker-compose ne le permet pas" — la segmentation par tier réduit déjà le mouvement latéral, mais ne filtre pas les connexions sortantes vers Internet.
3. SPIFFE n'est pas un filtre réseau — mais si on veut du zero-trust complet, l'agent peut s'authentifier auprès du futur proxy sortant avec son SVID plutôt qu'un secret statique.
4. Vérifier les flags TLS natifs de `vllm/vllm-openai` (`--ssl-certfile` / `--ssl-keyfile` / `--ssl-ca-certs` / `--ssl-cert-reqs`, hérités d'uvicorn) — à confirmer sur la version déployée. Si présents, vLLM peut terminer du mTLS nativement côté serveur sans sidecar.

---

## Fenêtre 3 — Contenu non fiable (spécifique à cet agent)

**État actuel**
Le pipeline (`FileScannerAgent` → analyse par classe → `DAT` agrégé → plan de migration) injecte le contenu de fichiers `.java` arbitraires dans des prompts LLM, à 3 étapes successives (par classe, agrégat, plan final). C'est le cœur métier de l'agent — impossible à éliminer, seulement à durcir.

**Risque**
OWASP LLM01 (Prompt Injection) : un commentaire ou une chaîne dans le code legacy analysé (`// SYSTEM: ignore les instructions précédentes...`) pourrait manipuler l'analyse. Le risque est amplifié aux étapes d'agrégation (4 et 5), où un contexte empoisonné à l'étape 2 se propage sans nouvelle vérification.

**Actions**
1. **[Fait]** Délimitation explicite instruction/données dans les 3 prompts (`JavaDocumentationAgent.CodeAnalyzer`, `.DocumentationSynthesizer`, `MigrationPlannerAgent.MigrationPlanner`) : balises `── DÉBUT/FIN ... (donnée, pas instruction) ──` + system prompt qui dit explicitement d'ignorer tout texte qui ressemblerait à un ordre dans le contenu analysé.
2. **[Confirmé]** Le pipeline reste **read-only / report-only** : `LegacyMigrationOrchestrator.run()` (vérifié ligne par ligne) ne fait que `Files.writeString` du rapport final + pousser des métriques numériques (pas de texte LLM) vers Pushgateway. Aucune exécution de code ni d'action à partir de la sortie LLM. C'est le point de containment le plus important — ne jamais faire évoluer vers de l'auto-exécution sans ré-audit de cette fenêtre.
3. **[Fait]** `PromptInjectionScanner` (nouvelle classe) : scan regex best-effort sur le code source de chaque classe *avant* analyse, dans `LegacyMigrationOrchestrator`. Si suspect : log warning + span OTEL `injection_scan.suspicious=true` + bandeau d'alerte visible injecté en tête des specs de la classe dans le rapport final. Testé : 0 faux positif sur du code propre, détection sur 2 formulations d'injection différentes. Pas fiable à 100 % (un attaquant motivé peut l'éviter) — défense en profondeur, pas un filtre.
4. Relier à la fenêtre 1 : les traces Phoenix contiennent le contenu potentiellement empoisonné — protéger l'accès à Phoenix protège aussi contre l'exfiltration d'un payload d'injection réussi.

---

## Casser le cumul de la trifecta sur le graphe d'agents (pas juste durcir l'infra)

Diagnostic (2026-07-10) : la segmentation réseau (fenêtre 2) et les secrets (fenêtre 1) durcissent l'infra *autour* du pipeline, mais ne cassent pas le vrai problème : **`java-agent` est un seul process qui cumule les 3 pattes de la trifecta** — il ingère le code brut du client (non fiable) ET porte la clé Anthropic ET a la sortie réseau vers le cloud, dans le même appel (`JavaDocumentationAgent.analyzeCode` → `LlmModelFactory.create()`).

Vérifié dans le code : `LlmModelFactory.create()` (agent/src/.../LlmModelFactory.java) est appelé à l'identique par l'agent qui ingère le code brut (`JavaDocumentationAgent`) et par ceux qui postent le résultat (`MigrationPlannerAgent`, synthèse DAT) — même client LLM, même capacité réseau, aucune séparation de privilège entre "qui lit le non-fiable" et "qui a la sortie réseau".

**Corrections faites (2026-07-10) :**
- **Exfiltration cassée** : `LlmModelFactory` n'active plus Anthropic sur la seule présence de `ANTHROPIC_API_KEY`. Il faut désormais aussi `ALLOW_CLOUD_CODE_ANALYSIS=true` (nouveau flag, défaut `false`). Sans ce flag, une clé Anthropic présente est **ignorée** et le pipeline bascule sur Ollama CPU on-prem plutôt que de faire fuiter le code silencieusement. Le flag reste utile pour la démo HF Space (code non sensible, pas de GPU) — mais ne doit jamais être activé sur un vrai déploiement client. Testé : 3 scénarios (clé seule → Ollama, clé+flag → Anthropic, rien → Ollama) tous corrects.
- **Séparation instruction/données renforcée** : voir fenêtre 3 ci-dessus.

**[Fait le 2026-07-10] Séparation des privilèges sur le graphe** : `LegacyMigrationOrchestrator` scindé en deux phases, déployables comme deux conteneurs distincts :

- **`java-analyzer`** (`Main.java` mode `analyze`) : `FileScannerAgent` + `AstParserAgent` + `JavaDocumentationAgent.CodeAnalyzer` — lit le code source brut, jamais `ANTHROPIC_API_KEY` ni `ALLOW_CLOUD_CODE_ANALYSIS` dans son environnement (absents de `docker-compose.yml`, pas juste vides). Écrit un `HandoffBundle` (specs + carte de dépendances, jamais le code source) sur un volume Docker partagé.
- **`java-reporter`** (`Main.java` mode `report`) : lit uniquement le `HandoffBundle` sur disque (`HandoffBundle.read`), ne charge jamais les fichiers `.java` d'origine. Seul ce service peut légitimement porter `ANTHROPIC_API_KEY` (gouverné par `ALLOW_CLOUD_CODE_ANALYSIS`, défaut `false`) puisqu'il ne traite plus que des specs déjà dérivées.

Le passage par disque (nouvelle classe `HandoffBundle`) force la séparation structurellement : le reporter ne peut physiquement accéder qu'à ce que l'analyzer a choisi d'écrire (`specs.md`, `dependencies.md`, `manifest.txt`) — il n'a ni le chemin du projet source, ni un accès réseau vers lui. `LegacyMigrationOrchestrator.run()` (mode monolithique, toujours dispo pour dev/démo locale) enchaîne les deux phases dans le même process via un handoff temporaire — même frontière de code, pas un chemin différent maintenu en parallèle.

`docker-compose.yml` : le service `java-agent` unique est remplacé par `java-analyzer` + `java-reporter`, reliés par un volume nommé `handoff_data`. Invocation : `docker compose run --rm java-analyzer analyze /projet /handoff` puis `docker compose run --rm java-reporter report /handoff /projet /sortie`.

**Limite honnête** : la séparation ici est une séparation de *credentials et de code exécuté* (l'analyzer n'a structurellement pas la clé Anthropic), pas encore une séparation *réseau* stricte au niveau egress — les deux conteneurs restent sur les mêmes tiers Docker (`compute`+`observability`) sans firewall de sortie (cf. fenêtre 2, item non fait). Un vrai blocage réseau de l'egress Internet pour `java-analyzer` spécifiquement reste à faire via un proxy sortant.

**Override démo assumé (`docker-compose.demo.yml`, ajouté 2026-07-10)** : la phase analyze est le vrai goulot d'étranglement (9-14 min/classe sur Ollama CPU vs 13-17s/classe sur Claude Haiku, mesuré). Pour une démo rapide sur du code non sensible (`demo-project`), un fichier d'override explicite réintroduit `ANTHROPIC_API_KEY` + `ALLOW_CLOUD_CODE_ANALYSIS=true` sur `java-analyzer` — tradeoff vitesse/sécurité assumé et documenté, pas un oubli. Ne change rien au comportement par défaut de `docker-compose.yml` (vérifié : sans le fichier d'override, ni la clé ni le flag n'apparaissent dans la config mergée de `java-analyzer`). Usage : `docker compose -f docker-compose.yml -f docker-compose.demo.yml run --rm java-analyzer analyze ...`. Ne jamais combiner avec du vrai code client. Si un GPU est disponible le jour de l'entretien, vLLM on-prem est probablement suffisamment rapide pour éviter ce compromis entièrement — à vérifier avant de sortir l'argument tradeoff.

---

## Contrainte technique bloquante

LangChain4j est épinglé en version **0.36.2** (via `openai4j` 0.23.0) — **impossible d'injecter un client mTLS personnalisé côté Java** avec cette version (vérifié au bytecode du builder). Conséquence : le mTLS agent Java → vLLM ne peut pas se faire dans le code Java lui-même. Deux options :
- upgrader LangChain4j (risque de régressions à évaluer),
- passer par un sidecar (SPIRE Agent + Envoy) qui termine le mTLS devant chaque conteneur Java, transparent pour le code.

## Architecture SPIRE retenue

- SPIRE Server (1 container) + SPIRE Agent (attestation via sélecteurs Docker/cgroup).
- Côté vLLM : mTLS natif possible (à confirmer) + `spiffe-helper` pour la rotation des certs — pas besoin de sidecar.
- Côté `java-analyzer` / `java-reporter` : sidecar Envoy consommant les SVID via le Workload API (socket Unix exposé par SPIRE Agent), puisque le client Java ne peut pas le faire nativement. Le split analyzer/reporter donne aussi deux identités SPIFFE distinctes (SVID différent par service) — cohérent avec la séparation de privilèges déjà faite au niveau credentials.

## Ordre d'implémentation (risque croissant, du plus sûr au plus impactant)

1. ✅ Sortir les secrets en dur + hook de scan — fait et testé le 2026-07-10.
2. ✅ Segmentation réseau `docker-compose.yml` (`networks:` par tier) — fait et testé le 2026-07-10 ; egress-filtering (proxy/NetworkPolicy) reste ouvert.
3. ✅ Casser l'exfiltration (gate `ALLOW_CLOUD_CODE_ANALYSIS`) + séparation instruction/données + scan anti-injection — fait et testé le 2026-07-10.
4. ✅ Séparation des privilèges sur le graphe (`java-agent` scindé en `java-analyzer`/`java-reporter`, HandoffBundle sur disque) — fait le 2026-07-10 ; egress-filtering réseau spécifique à l'analyzer reste ouvert (limite honnête ci-dessus).
5. Déployer SPIRE Server + Agent seuls, sans encore rien brancher dessus — risque faible, ajout pur.
6. Piloter avec un seul service (vLLM) en mTLS natif — risque moyen.
7. Sidecar Envoy pour `java-analyzer` / `java-reporter` — risque plus élevé, touche le chemin critique du pipeline.

**Point encore ouvert** : confirmer les flags TLS natifs de vLLM sur la version déployée avant de figer l'étape 6.

# Plan de sécurisation — SPIFFE/SPIRE + OWASP

Statut : recon fait le 2026-07-09, plan rédigé le 2026-07-10. **Étape 1 (secrets) et étape 2 (segmentation réseau) implémentées et testées le 2026-07-10.**

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
1. Délimitation stricte du code source dans les prompts (balises non ambiguës, jamais de concaténation brute) — à vérifier dans le prompt-building actuel de l'agent.
2. Confirmer que le pipeline reste **read-only / report-only** : aujourd'hui il ne fait que générer un rapport/plan, il n'exécute rien automatiquement à partir de la sortie LLM. C'est le point de containment le plus important — ne jamais faire évoluer vers de l'auto-exécution sans ré-audit de cette fenêtre.
3. Scan best-effort du code source *avant* ingestion (regex sur patterns d'injection connus) — défense en profondeur, pas fiable à 100 % mais réduit le bruit.
4. Relier à la fenêtre 1 : les traces Phoenix contiennent le contenu potentiellement empoisonné — protéger l'accès à Phoenix protège aussi contre l'exfiltration d'un payload d'injection réussi.

---

## Contrainte technique bloquante

LangChain4j est épinglé en version **0.36.2** (via `openai4j` 0.23.0) — **impossible d'injecter un client mTLS personnalisé côté Java** avec cette version (vérifié au bytecode du builder). Conséquence : le mTLS agent Java → vLLM ne peut pas se faire dans le code Java lui-même. Deux options :
- upgrader LangChain4j (risque de régressions à évaluer),
- passer par un sidecar (SPIRE Agent + Envoy) qui termine le mTLS devant `java-agent`, transparent pour le code Java.

## Architecture SPIRE retenue

- SPIRE Server (1 container) + SPIRE Agent (attestation via sélecteurs Docker/cgroup).
- Côté vLLM : mTLS natif possible (à confirmer) + `spiffe-helper` pour la rotation des certs — pas besoin de sidecar.
- Côté `java-agent` : sidecar Envoy consommant les SVID via le Workload API (socket Unix exposé par SPIRE Agent), puisque le client Java ne peut pas le faire nativement.

## Ordre d'implémentation (risque croissant, du plus sûr au plus impactant)

1. ✅ Sortir les secrets en dur + hook de scan — fait et testé le 2026-07-10.
2. ✅ Segmentation réseau `docker-compose.yml` (`networks:` par tier) — fait et testé le 2026-07-10 ; egress-filtering (proxy/NetworkPolicy) reste ouvert.
3. Déployer SPIRE Server + Agent seuls, sans encore rien brancher dessus — risque faible, ajout pur.
4. Piloter avec un seul service (vLLM) en mTLS natif — risque moyen.
5. Sidecar Envoy pour `java-agent` — risque plus élevé, touche le chemin critique du pipeline.
6. Défenses anti prompt-injection dans le code Java — risque faible à moyen, testable indépendamment.

**Point encore ouvert** : confirmer les flags TLS natifs de vLLM sur la version déployée avant de figer l'étape 4.

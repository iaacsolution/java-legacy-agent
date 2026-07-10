# Plan de sécurisation — SPIFFE/SPIRE + OWASP

Statut : **plan, rien n'est encore implémenté**. Recon fait le 2026-07-09, plan rédigé le 2026-07-10.

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
1. Sortir tous les secrets en dur → fichier `.env` non versionné + template `.env.example`, ou Docker secrets pour les déploiements plus sérieux.
2. Ajouter `gitleaks` (ou `detect-secrets`) au hook `pre-commit` existant, en plus du breaking-change detector déjà en place.
3. SPIFFE/SPIRE comme identité de workload : chaque service reçoit un SVID X.509 émis par SPIRE Server, attesté par SPIRE Agent via des sélecteurs Docker (label ou cgroup). Ça permet une autorisation par identité plutôt que par "qui connaît le secret partagé" — seul le workload `java-agent` est autorisé à porter la charge liée à `ANTHROPIC_API_KEY`.
4. OWASP LLM06 (Sensitive Information Disclosure) : avant l'ingestion, scanner le code legacy analysé pour des patterns de secrets (creds JDBC en dur dans de vieux EJB, typique du legacy) afin d'éviter qu'ils remontent tels quels dans les traces Phoenix / dashboards Grafana.

---

## Fenêtre 2 — Connexions extérieures

**État actuel (recon confirmé)**
- Aucune section `networks:` dans `docker-compose.yml` → tous les services sur le bridge par défaut, joignables entre eux par nom de service sans aucune auth (`vllm:8000`, `pushgateway:9091`, `phoenix:4317`...).
- Seul egress légitime vers Internet : `api.anthropic.com` (client Anthropic dans `java-agent`) et `huggingface.co` (pull de modèle vLLM, push/pull HF Space). Aucun autre service n'a de raison de sortir sur Internet.

**Actions**
1. Segmenter le réseau Docker en au moins deux réseaux : un réseau *interne* (vllm, pushgateway, prometheus, phoenix — zéro accès Internet) et un réseau *egress* pour `java-agent` uniquement.
2. Ajouter un proxy sortant (Squid ou Envoy) qui n'autorise que `api.anthropic.com:443` en sortie de `java-agent`, tout le reste refusé par défaut. Grafana/Airflow n'ont structurellement aucun besoin de sortir sur Internet.
3. SPIFFE n'est pas un filtre réseau — mais si on veut du zero-trust complet, l'agent peut s'authentifier auprès du proxy sortant avec son SVID plutôt qu'un secret statique.
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

## Ordre d'implémentation proposé (risque croissant, du plus sûr au plus impactant)

1. Sortir les secrets en dur + hook gitleaks — faible risque, réversible.
2. Segmentation réseau `docker-compose.yml` (`networks:`) — risque moyen, testable en local avant tout déploiement.
3. Déployer SPIRE Server + Agent seuls, sans encore rien brancher dessus — risque faible, ajout pur.
4. Piloter avec un seul service (vLLM) en mTLS natif — risque moyen.
5. Sidecar Envoy pour `java-agent` — risque plus élevé, touche le chemin critique du pipeline.
6. Défenses anti prompt-injection dans le code Java — risque faible à moyen, testable indépendamment.

**Point encore ouvert** : confirmer les flags TLS natifs de vLLM sur la version déployée avant de figer l'étape 4.

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

## Gouvernance — état des lots

Chantier décrit dans [`specs/gouvernance-spec.md`](specs/gouvernance-spec.md) : trois lots, livrés
dans l'ordre 1 → 2 → 3. Matrice des droits par composant, écarts au moindre privilège et état
honnête des 4 piliers : **[`docs/GOVERNANCE.md`](docs/GOVERNANCE.md)**.

| Lot | Pilier | État | Où le lire |
|---|---|---|---|
| 1 | Cycle de vie : évaluation automatisée et CI | **Fusionné** dans `master` (PR #1) | section « Évaluation automatisée (CI) » |
| 2 | Gestion des risques : redaction des secrets et PII avant tout LLM | **Fusionné** dans `master` (`gov/lot2-redaction`, fusion `--no-ff`) | section « Redaction des secrets et PII » |
| 3 | Sécurité : matrice des droits par agent | **Livré sur `gov/lot3-permissions`**, pas encore fusionné | [`docs/GOVERNANCE.md`](docs/GOVERNANCE.md) : matrice, écarts, état des 4 piliers |

## Measured metrics — revision

Chaque chiffre ci-dessous porte **son backend** : ils ne sont pas interchangeables. Le speedup
est mesuré sur Claude Haiku (seul backend où les workers parallèles aident réellement — Ollama
sérialise ses requêtes par conception, voir `LlmModelFactory`), sur `demo-project` (4 classes),
`AGENT_WORKERS=4`. Le F1 est mesuré sur Ollama local. Le surcoût du recorder est un
microbenchmark sans LLM.

| Métrique | Valeur | Backend | Méthode et source |
|---|---|---|---|
| F1 global (`AgentEvaluator`) — **référence** | **0.808** — médiane de **3 passages**, étendue 0.763–0.844 | Ollama local `qwen2.5-coder:7b`, température 0.1 | **3 cas** (`golden_dataset_3cases.json` : cas1–cas3), mesurés le 2026-09-30 au commit `901ba03` (après correction `FIELD_PATTERN`). Source : [`results/f1_ollama.json`](results/f1_ollama.json), sorties brutes dans `results/raw/` |
| Speedup de parallélisation | **×2.88** — médianes 20776 ms (1 worker) vs 7218 ms (4 workers) | Claude Haiku | `scripts/benchmark_speedup.py`, 3 runs par configuration, valeurs brutes dans le README ci-dessous |
| Surcoût du recorder, côté agent | **0.448 µs/span** — étendue 0.391–0.492 | aucun (microbenchmark, sans LLM) | 5 séries de 100 000 spans après chauffe. Source : [`results/flightrec_bench.json`](results/flightrec_bench.json) |
| Débit d'écriture du recorder | **23 145 spans/s** — 50 000 écrits, 0 perdu | aucun (TimescaleDB local) | idem, source : [`results/flightrec_bench.json`](results/flightrec_bench.json) |
| Charge sortante (chemin cloud) — **squelette AST de la phase `analyze` uniquement** | 288–378 caractères | Claude Haiku | Vrai **seulement** pour `analyzeJavaClassWithAst` (`JavaDocumentationAgent.java:168`, charge choisie en `:173` via `toAnonymizedPromptContext`), appelé par le pipeline (`LegacyMigrationOrchestrator.java:175`) : signatures, types de champs, complexité cyclomatique, imports — pas de corps de méthode, pas de littéral SQL. **Faux pour `analyzeJavaClass` (`JavaDocumentationAgent.java:154-157`), qui envoie le code source brut, sans garde `isCloudActive()`**, sur les modes `plan` (`Main.java:152`), démo sans argument (`Main.java:214`) et `eval` (`EvalMain.java:265`). La phase `report` envoie en outre les spécifications produites par le LLM, sans anonymisation (`LegacyMigrationOrchestrator.java:330`, `:340`). Correction prévue au Lot 2 de `specs/gouvernance-spec.md`. |

**F1 avant / après la correction `FIELD_PATTERN`**, sur **3 cas** (`golden_dataset_3cases.json`),
**3 passages par série**, mêmes modèle, empreinte et température
([`results/f1_ollama.json`](results/f1_ollama.json)) :

| | Composante déterministe (regex, sans LLM) | F1 global — médiane | F1 global — étendue |
|---|---|---|---|
| Avant correction | 0.502 | 0.737 | 0.687 – 0.739 |
| **Après correction** | **0.716** | **0.808** | **0.763 – 0.844** |

**L'effet de la correction se lit sur la composante déterministe, et là seulement** : elle est
exacte et identique aux 3 passages de chaque série. Le F1 global inclut en plus la dérive du LLM,
et ses deux étendues se touchent presque — le présenter comme « +0.07 grâce à la correction »
serait une surinterprétation.

**Toutes les mesures du F1 global, chacune avec son jeu de données.** Un F1 n'est comparable
qu'à un F1 du même jeu de données, du même backend et de la même méthode (médiane de N passages).

| F1 global | Jeu de données | Passages | Backend | Date, commit | Source |
|---|---|---|---|---|---|
| **0.808** (référence Ollama) | 3 cas — `golden_dataset_3cases.json` | 3, médiane | Ollama `qwen2.5-coder:7b` | 2026-09-30, `901ba03` | `results/f1_ollama.json` |
| 0.737 (avant `FIELD_PATTERN`) | 3 cas — `golden_dataset_3cases.json` | 3, médiane | Ollama | 2026-09-30, `9b98b6b` | `results/f1_ollama.json` |
| 0.792 (contrôle du Lot 1) | 3 cas — `golden_dataset_3cases.json` | 1 | Ollama | 2026-10-09 | `eval/reports/2026-10-09T100546Z_full_3704.json` |
| 0.840 (référence Haiku, 3 cas) | cas1–cas3 extraits des passages sur `golden_dataset.json` | 5, médiane | Claude Haiku | 2026-10-09 | `eval/reports/2026-10-09T100546Z_full_15820.json` |
| 0.882 (référence Haiku, 5 cas) | 5 cas — `golden_dataset.json` | 5, médiane | Claude Haiku | 2026-10-09 | idem |
| 0.759 (Lot 2, avec redaction) | 3 cas — `golden_dataset_3cases.json` | 3, médiane | Ollama | 2026-10-09 | `eval/reports/2026-10-09T141528Z_full_17736.json` |
| 0.759 (Lot 2, `LLM_REDACTION=off`) | 3 cas — `golden_dataset_3cases.json` | 3, médiane | Ollama | 2026-10-09 | `eval/reports/2026-10-09T143257Z_full_10160.json` |
| 0.773 (Lot 3, avant) | 3 cas — `golden_dataset_3cases.json` | 3, médiane | Ollama | 2026-10-16, `4212b0e` | `eval/reports/2026-10-16T130140Z_full_55436.json` |
| 0.778 (Lot 3, après) | 3 cas — `golden_dataset_3cases.json` | 3, médiane | Ollama | 2026-10-16, `fce3f9d` | `eval/reports/2026-10-16T132348Z_full_55500.json` |
| 0.757 — **historique** | 3 cas d'origine (aujourd'hui `golden_dataset_3cases.json`) | non consigné | non consigné | avant `901ba03` | — (non reproductible) |
| 0.802 — **historique** | 5 cas — `golden_dataset.json` | non consigné | non consigné | avant `901ba03` | — |

**×2.50 est une mesure antérieure** du même speedup, sur une autre machine, également sur Claude
Haiku. Elle est conservée plus bas pour mémoire, pas comme valeur courante. **Ni ×2.88 ni ×2.50
n'ont été remesurés depuis** : les lots de gouvernance n'ont pas lancé le benchmark (backend
cloud exclu, faute de budget API). Chacun reste attribué à sa mesure d'origine.

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

**0.802 sur les 5 cas (`golden_dataset.json`) est un chiffre HISTORIQUE**, mesuré avant la correction `FIELD_PATTERN` et
donc périmé. Les 2 cas ajoutés évitaient par construction le bug décrit plus bas, ce qui tirait
mécaniquement la moyenne `DependencyMapper` vers le haut : ce n'était pas une amélioration du
pipeline, seulement un effet de composition du dataset. La valeur courante est celle du tableau
de tête, mesurée sur les 3 cas historiques (`golden_dataset_3cases.json`) après correction.

**0.757 — chiffre HISTORIQUE, plus comparable à rien d'actuel.** À l'époque de l'externalisation
du dataset, ré-exécuter `EvalMain` sur les 3 cas d'origine seuls (aujourd'hui
`golden_dataset_3cases.json` ; nombre de passages non consigné) redonnait exactement 0.757, ce
qui confirmait que le passage du hardcode au chargement JSON n'avait rien changé au calcul. Mais
ce 0.757 **reposait sur le bug `FIELD_PATTERN`**, corrigé depuis : la comparaison directe avec
toute mesure actuelle n'a plus de sens. Il n'est conservé ici que comme repère historique.

**Limite connue — le F1 n'est pas parfaitement reproductible d'un run à l'autre.** La composante
déterministe (`DependencyMapperAgent`, regex, sans LLM) est bit-identique entre deux runs
indépendants sur les mêmes 3 cas (`golden_dataset_3cases.json` ; F1 = 0.571 / 0.933 / 0.000, à
chaque fois, avant correction `FIELD_PATTERN`). Les composantes qui
dépendent du LLM (rappel de mots-clés sur risques et responsabilités) varient réellement d'un
appel à l'autre, même à température 0.1 — observé concrètement lors de cette session : le F1
"Risques" du cas 1 (même jeu) est sorti à 0.923 puis 0.833 sur deux runs successifs, mêmes code et mêmes clés
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

## Évaluation automatisée (CI)

`scripts/eval_golden_dataset.py` lance `EvalMain --json`, sans recalculer le F1, et compare son
résultat à la baseline versionnée [`eval/baseline.json`](eval/baseline.json). Il écrit un
rapport JSON et la sortie brute dans `eval/reports/`.

```bash
mvn -f agent/pom.xml package
python scripts/eval_golden_dataset.py --mode deterministic          # sans LLM
python scripts/eval_golden_dataset.py --mode full --passes 5        # backend actif, 5 passages
```

Le script fait deux contrôles, de nature différente :

| Contrôle | Comparaison | Statut | Quand (`.github/workflows/eval.yml`) |
|---|---|---|---|
| Composante déterministe (`DependencyMapper`, regex) | TP/FP/FN **exacts** par cas, tolérance 0 | **Bloquant — le seul** | chaque `push` et `pull_request` |
| F1 global LLM | médiane ≥ médiane de la baseline **du même backend** − tolérance | **Alerte, jamais bloquant** | job `full`, `workflow_dispatch` uniquement, sur Claude Haiku — **prêt mais non activé** (voir ci-dessous), jamais sur une PR |

La composante LLM n'est pas bloquante parce qu'elle dérive d'un passage à l'autre, même à
température 0.1 (voir plus haut). Un écart signale une dérive à examiner ; il ne juge pas un
changement de code. Une baseline Ollama ne juge jamais un passage Haiku, ni l'inverse. Tant
qu'aucune tolérance n'est validée pour un backend, le contrôle LLM rend `NON_EVALUE`.

Le contrôle déterministe est exact dans les deux sens : un F1 qui **monte** sans explication
est aussi une régression à examiner. Par exemple, neutraliser l'extraction `implements` fait
passer le cas 1 de 0.714 à 0.769 (composante déterministe, `golden_dataset.json`), parce qu'un
faux positif disparaît.

**État réel :**
- **En CI, à chaque push** : seul le contrôle déterministe tourne, et il est bloquant.
- **Évaluation LLM** : mesurée **en local** sur Claude Haiku, 5 passages. Les rapports
  et les sorties brutes sont versionnés dans
  [`eval/reports/`](eval/reports/LISEZMOI.md) ; ce sont eux qui fondent les seuils de
  `eval/baseline.json`.
- **Job `full` en CI** : prêt, mais **non activé faute de budget API**. Il n'a pas de
  déclencheur planifié, et aucun run CI réussi ne l'a encore validé (le seul essai a
  échoué sur `invalid x-api-key`).
- **Pour le réactiver** : poser le secret Actions `ANTHROPIC_API_KEY` du dépôt, puis
  lancer le workflow `eval` par `workflow_dispatch`.

Codes de sortie : `0` PASS (alerte LLM éventuelle signalée), `1` régression déterministe,
`2` erreur d'exécution ou incohérence.

Le verdict du rapport vaut `PASS`, `PASS_AVEC_ALERTE_LLM` ou `FAIL`. Le hook Stop de Claude
Code (`.claude/hooks/check-golden-dataset.sh`) accepte `PASS_AVEC_ALERTE_LLM` comme un PASS,
puisque l'alerte LLM ne bloque jamais. Il affiche l'alerte avec le chemin du rapport.

## Redaction des secrets et PII (avant tout LLM)

Chaque message envoyé à un LLM est filtré, **quel que soit le backend** (vLLM, Claude Haiku,
Ollama), message système compris. Le filtre est un décorateur `RedactingChatModel`, posé par
`LlmModelFactory.create()`, l'unique site de construction de modèle : il couvre ainsi les
5 proxys `AiServices` du projet. Code : `agent/src/main/java/com/audensiel/legacy/agent/governance/`.

| Variable | Valeurs | Effet |
|---|---|---|
| `LLM_REDACTION` | `on` (défaut) / `off` | `off` désactive le filtre et le signale par un WARN au démarrage |
| `LLM_REDACTION_POLICY` | `redact` (défaut) / `block` | `block` refuse l'envoi (exception) dès qu'une valeur est détectée ; rien ne part |
| `ALLOW_CLOUD_RAW_CODE` | absent (défaut) / `true` | sans lui, `analyzeJavaClass` refuse d'envoyer du code **brut** vers le cloud (exception + log ERROR) |

Une valeur inconnue pour `LLM_REDACTION` ou `LLM_REDACTION_POLICY` est une erreur explicite,
jamais un retour silencieux au défaut.

**Types détectés**, appliqués dans cet ordre, chacun sur le texte déjà filtré par les
précédents :
1. clé privée (bloc `BEGIN … END`) ;
2. identifiants d'URL JDBC (seuls les identifiants sont remplacés, pas l'URL entière) ;
3. clés d'API (préfixes connus, puis affectation), mots de passe et jetons affectés à un littéral ;
4. e-mails ;
5. IP internes (10/8, 172.16/12, 192.168/16, 127/8).

JDBC passe avant les e-mails parce que `user:pass@hôte` ressemble à une adresse e-mail. Les
IP passent en dernier parce qu'une IP peut être l'hôte d'une URL JDBC.

**Marqueur** : `<REDACTED:TYPE:h>`, où `h` est le HMAC-SHA256 tronqué du secret, calculé
avec une clé aléatoire tirée au démarrage et gardée en mémoire seulement. Un même secret
donne le même marqueur sur toute l'exécution. Il n'existe **aucune table de
correspondance** : les documents produits (DAT, plan) contiennent des marqueurs, jamais les
valeurs.

**Flight recorder** : un span `redaction` par appel, fils du span `agent`, qui porte la
classe analysée. Il ne contient que des nombres (`redaction.<TYPE>`, `redaction.total`) et
la politique appliquée, jamais une valeur.

**Limites — ce n'est pas une garantie absolue :**
- Le filtre repose sur des expressions régulières : des **faux négatifs** restent possibles.
  Exemples : secret construit dynamiquement, nom de variable neutre (`String x = "…"`),
  format de clé inconnu, `getConnection(url, user, pass)` avec des variables.
- **Non couverts** par les types ci-dessus : noms propres (`@author Jean Dupont`), noms
  d'hôtes internes, URL de tickets.
- **Faux positifs possibles** : un identifiant qui contient un mot-clé (`tokenizerName = "…"`)
  est filtré.
- **Quantificateurs bornés (anti-ReDoS)** : au-delà de ces longueurs, un motif n'est pas
  reconnu (faux négatif). C'est la contrepartie d'un temps de traitement borné.

  | Règle | Bornes |
  |---|---|
  | Affectation (`x = "…"`, `setX("…")`, `put("x", "…")`) | identifiant : 64 caractères avant et après le mot-clé ; valeur : 4 096 |
  | Forme `x=…` dans un littéral | 512 caractères entre le guillemet ouvrant et l'identifiant ; valeur : 4 096 |
  | Clé privée | corps : 16 384 caractères |
  | URL JDBC | sous-protocole : 32 caractères, 4 segments `:x` au plus ; utilisateur et mot de passe : 256 chacun ; sous-protocole Oracle : 16 |
  | E-mail | partie locale : 256 ; chaque label de domaine : 63 ; 10 labels au plus ; TLD : 2 à 24 lettres |

  Sans ces bornes, `RedactorReDoSTest` (19 entrées piégées de 100 000 caractères, 1 s
  maximum) échouait : **7 entrées dépassaient la seconde et 2 faisaient déborder la pile**.
  Preuve : [`eval/reports/2026-10-12_redos_avant_correctif/`](eval/reports/2026-10-12_redos_avant_correctif/LISEZMOI.md).
- **Effet sur la qualité d'analyse non mesuré.** Le golden dataset ne contient aucun secret :
  la redaction n'y change rien, ce que prouvent `GoldenDatasetNoOpTest` (zéro détection) et
  `DecoratorTransparencyTest` (requête identique avec et sans décorateur, sans LLM). Son F1
  ne dit donc rien de l'analyse d'un code qui contient des secrets.
- **Baisse du F1 global observée le jour de la livraison, cause non établie.** Les 6 passages
  Ollama du 2026-10-09 (`golden_dataset_3cases.json`, 3 passages par série) sont **sous la
  médiane de la baseline** (0.808, même jeu), avec comme sans filtrage : médiane 0.759115 dans
  les deux séries (rapports dans
  [`eval/reports/`](eval/reports/LISEZMOI.md)). Le chemin `LLM_REDACTION=off` retire le
  décorateur : il équivaut au chemin d'avant le Lot 2. La baisse n'est donc pas imputable
  au filtrage. Hypothèse : dérive de l'environnement, **non démontrée**. L'empreinte du
  modèle servi a été relevée par `/api/tags` pendant la session, identique à celle de la
  baseline, mais elle n'est pas consignée dans les rapports du jour : ce point n'est pas
  vérifiable depuis le dépôt. Action proposée, pas encore faite : rejouer la baseline avec une graine fixe
  et une température à 0, si Ollama le permet.
- **Marqueurs différents d'une phase à l'autre.** `analyze` et `report` tournent dans deux
  JVM, donc avec deux clés HMAC. Conséquence concrète : **un même secret ne porte pas le même
  marqueur dans les deux phases**, et l'on ne peut pas recouper un marqueur de la phase
  `report` avec un marqueur de la phase `analyze`.
  - **Ce n'est pas un problème pour le rapport.** La phase `report` ne relit pas le code
    source et ne reçoit que la sortie LLM d'`analyze`, déjà filtrée (`LegacyMigrationOrchestrator.java:302`,
    `:330`, `:340`). Les marqueurs qu'elle transmet ont été posés par `analyze` ; elle n'en
    pose pas de nouveaux, sauf dans les cas dégénérés ci-dessous.
  - Cas dégénérés : un handoff produit avec `LLM_REDACTION=off` ou avant le Lot 2. Ses
    valeurs seraient alors marquées avec la clé de `report`.

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

### Compte Grafana en lecture seule

Grafana **lit** les spans ; il ne doit pas détenir les identifiants d'écriture de
l'enregistreur. Deux comptes distincts, donc : `FLIGHTREC_*` pour écrire,
`GRAFANA_PG_*` pour lire.

Créer le compte de lecture, une fois, sur `legacyrec` :

```sql
-- Remplacer <mot_de_passe> par une valeur choisie, à ne reporter que dans .env
CREATE ROLE grafana_ro LOGIN PASSWORD '<mot_de_passe>';

GRANT CONNECT ON DATABASE legacyrec TO grafana_ro;
GRANT USAGE   ON SCHEMA public      TO grafana_ro;
GRANT SELECT  ON ALL TABLES IN SCHEMA public TO grafana_ro;

-- Pour que les tables créées PLUS TARD soient lisibles elles aussi : sans cette
-- ligne, une future table (une agrégation continue TimescaleDB, par exemple)
-- serait invisible de Grafana et les panneaux tomberaient en erreur.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana_ro;
```

TimescaleDB expose ses vues internes dans d'autres schémas ; si un panneau en a besoin :

```sql
GRANT USAGE ON SCHEMA timescaledb_information TO grafana_ro;
```

Puis renseigner dans `.env` (jamais dans un fichier versionné) :

```
GRAFANA_PG_USER=grafana_ro
GRAFANA_PG_PASSWORD=<mot_de_passe>
```

> ⚠️ Dans les fichiers de provisionnement Grafana, n'écrire **que** `${VAR}` —
> jamais `${VAR:-défaut}`. Grafana ne comprend pas cette seconde forme et la résout
> à vide, ce qui produit l'erreur `no PostgreSQL user name specified` alors même que
> la variable est bien présente dans l'environnement du conteneur.

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

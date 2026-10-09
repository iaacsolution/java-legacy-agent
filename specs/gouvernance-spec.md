# Spec — Gouvernance du pipeline java-legacy-agent

> À placer dans le repo (`specs/gouvernance-spec.md`), puis dans Claude Code en **mode plan** :
> « Lis `specs/gouvernance-spec.md`. Explore le repo, puis propose un plan pour le Lot 1 uniquement. N'écris aucun code avant ma validation. »
>
> **Version 2 (09/10/2026)** — remplace la v1. Changements : la référence 0,757 et l'interdiction de corriger `FIELD_PATTERN` sont retirées (le bug est corrigé, commit `901ba03`) ; baseline séparée déterministe / LLM ; langage du module de redaction aligné sur le code Java.

---

## 0. Contexte et règles non négociables

**Projet** : pipeline de modernisation de code Java legacy (Java / Maven) — 7 agents (FileScannerAgent, AstParserAgent, CallGraphAgent, DependencyMapperAgent, JavaDocumentationAgent, MigrationPlannerAgent, AgentEvaluator) + RefactoringAdvisor + LegacyMigrationOrchestrator. JavaParser pour l'AST, orchestration Java via `LegacyMigrationOrchestrator` + LangChain4j `AiServices` (à confirmer en exploration). Backends LLM par priorité (`LlmModelFactory`) : vLLM local GPU → Claude Haiku (cloud) → Ollama CPU. Observabilité Prometheus / Grafana / Phoenix OTEL + flight recorder TimescaleDB (base `legacyrec`).

**Objectif** : appliquer 3 piliers de gouvernance des agents — cycle de vie, gestion des risques, sécurité — sans casser l'existant.

### Valeurs de référence actuelles

Source : README et `results/f1_ollama.json` (après correction `FIELD_PATTERN`, commit `901ba03`). Dataset : `golden_dataset_3cases.json`.

| Mesure | Valeur | Nature | Backend |
|---|---|---|---|
| Composante déterministe (`DependencyMapperAgent`, regex, sans LLM) | cas1 0,714 · cas2 0,933 · cas3 0,500 · **moyenne 0,716** | Exacte, identique à chaque passage | aucun |
| F1 global | **médiane 0,808**, étendue 0,763–0,844 (3 passages) | Varie d'un passage à l'autre (composantes LLM) | Ollama `qwen2.5-coder:7b`, température 0,1 |
| F1 global avant correction | médiane 0,737, étendue 0,687–0,739 | Historique | idem |
| 0,757 (3 cas) et 0,802 (5 cas) | — | **Historiques, non comparables** : mesurés avec le bug `FIELD_PATTERN` | — |

Variance observée sur les composantes LLM : jusqu'à 0,09 sur une composante (ex. F1 « Risques » du cas 1 : 0,923 puis 0,833).

Limites connues, volontairement non corrigées : `HashMap` n'apparaît que via `new HashMap<>()` (aucun motif ne regarde les instanciations) ; `ConfigurationManager` apparaît comme dépendant de lui-même, ce qui plafonne le cas 3 à 0,500.

### Règles

1. **Non-régression**, en deux niveaux :
   - **Composante déterministe** : doit rester **exactement** à cas1 0,714 / cas2 0,933 / cas3 0,500. Tolérance 0. Toute différence est bloquante.
   - **F1 global** : comparé à la baseline mesurée **sur le même backend**, avec le seuil de tolérance mesuré au Lot 1. Une baisse au-delà du seuil est bloquante.
   - 0,757 n'est jamais utilisé comme seuil. Il peut être affiché comme repère historique.
2. **Aucun chiffre non mesuré** (skill `no-fake-metrics`) : chaque valeur citée dans le code, les docs ou ton rapport doit venir d'une exécution réelle, avec la commande utilisée.
3. **Ne pas modifier la méthode de calcul du F1** ni le contenu des golden datasets. Les deux limites connues ci-dessus ne sont pas à corriger dans ce chantier.
4. **Windows** : tout I/O fichier en `encoding="utf-8"` explicite (Python) ou `StandardCharsets.UTF_8` (Java) ; aucun formatage de nombre dépendant de la locale (bugs cp1252 et virgule décimale fr_FR déjà rencontrés).
5. **Une branche par lot** : `gov/lot1-eval-ci`, `gov/lot2-redaction`, `gov/lot3-permissions`. Ordre imposé : 1 → 2 → 3 (le Lot 1 sert de filet de sécurité aux suivants).
6. **Après chaque lot** : lancer les sous-agents `golden-dataset-evaluator` puis `cold-diff-reviewer`, et me présenter leurs conclusions.
7. **En cas d'ambiguïté** : t'arrêter et me poser la question plutôt que supposer.

---

## Phase d'exploration (avant tout plan)

Réponds précisément, en citant fichiers et lignes :
- Comment le pipeline est lancé (point d'entrée, arguments, backend sélectionné comment).
- Comment le golden dataset est évalué aujourd'hui (`EvalMain`, calcul du F1, format de `golden_dataset.json` et `golden_dataset_3cases.json`).
- **Tous** les points où un prompt part vers un LLM (vLLM, Claude Haiku, Ollama). Existe-t-il un point de passage unique (ex. `LlmModelFactory`) ?
- Pour chaque agent : ce qu'il lit, ce qu'il écrit, s'il appelle le réseau, la base TimescaleDB, des variables d'environnement.
- Confirme le framework d'orchestration réellement utilisé (LangChain4j, LangGraph ou autre).

---

## Lot 1 — Cycle de vie : évaluation automatisée + CI

**Livrables**
- `scripts/eval_golden_dataset.py`
  - Lance l'évaluation existante (`EvalMain`) sur `golden_dataset_3cases.json`, et sur `golden_dataset.json` (5 cas) en option. **Ne réimplémente pas le F1 en Python** : il exploite la sortie de `EvalMain`.
  - Rapporte séparément la composante déterministe (par cas) et le F1 global.
  - Compare à `eval/baseline.json` (structure ci-dessous).
  - Sort un rapport JSON (`eval/reports/<date>.json`) et un résumé lisible.
  - Code de sortie ≠ 0 en cas de régression.
- `eval/baseline.json` — structure réelle (clés en français), extrait abrégé ; le fichier versionné fait foi :

```json
{
  "schema": 1,
  "lecture": "Seule la composante déterministe est bloquante (tolérance 0, TP/FP/FN exacts)...",
  "cas_historiques": ["cas1", "cas2", "cas3"],
  "deterministe": {
    "tolerance": 0,
    "par_cas": {
      "cas1": {"tp": 5, "fp": 2, "fn": 2, "f1": 0.714},
      "cas2": {"tp": 7, "fp": 0, "fn": 1, "f1": 0.933},
      "cas3": {"tp": 1, "fp": 1, "fn": 1, "f1": 0.5},
      "cas4": {"tp": 5, "fp": 0, "fn": 0, "f1": 1.0},
      "cas5": {"tp": 3, "fp": 0, "fn": 0, "f1": 1.0}
    },
    "source": "<rapports et commit de la mesure>",
    "note": "<garde-fou FIELD_PATTERN, avec sa preuve>"
  },
  "llm": {
    "<backend>/<modèle>": {
      "cas_historiques": {
        "f1_global": {"valeurs": ["<mesuré>"], "mediane": "<mesuré>", "minimum": "<mesuré>",
                      "maximum": "<mesuré>", "ecart_type": "<mesuré>"},
        "composantes": {"dependency_mapper": {}, "risques": {}, "responsabilites": {}},
        "tolerance": "<validé>",
        "calcul_tolerance": {"methode": "max(max − min, 3σ) ; seuil = médiane − tolérance",
                             "etendue": "...", "trois_sigma": "...", "tolerance": "...", "seuil": "..."},
        "source": "<rapports, backend, réserves>"
      },
      "golden_dataset.json": {"...": "même structure, sur les 5 cas"}
    }
  },
  "llm_note": "Aucun seuil LLM n'est le garde-fou de FIELD_PATTERN : c'est la comparaison déterministe.",
  "speedup": {"valeur": 2.88, "seuil_minimum": 1.8, "backend": "anthropic/claude-haiku-4-5-20251001",
              "mesure": "...", "source": "...", "historique": {"2.50": "..."}},
  "historique": {
    "0.757": {"perimetre": "3 cas", "note": "avant correction FIELD_PATTERN, non reproductible — jamais un seuil"},
    "0.802": {"perimetre": "5 cas", "note": "avant correction FIELD_PATTERN — jamais un seuil"}
  }
}
```

  Le commit de chaque mesure figure dans les rapports cités (`eval/reports/`), pas au premier niveau : la baseline agrège des mesures de commits différents.

- **Une baseline LLM par backend.** La valeur Ollama (médiane 0,808) existe déjà. Pour le backend utilisé en CI, exécute l'évaluation complète 3 fois, rapporte la médiane, l'étendue et la variance par composante, puis propose un seuil justifié. Je valide le seuil.
- `.github/workflows/eval.yml` avec deux niveaux :
  - **À chaque push / PR** : uniquement ce qui est déterministe — `mvn test` + composante déterministe du golden dataset, comparée **exactement** à la baseline. Sans LLM, rapide, gratuit, reproductible.
  - **Manuel (`workflow_dispatch`) ou hebdomadaire** : évaluation complète avec LLM, clé via `secrets.ANTHROPIC_API_KEY`. La clé ne doit jamais apparaître dans les logs.

**Critères d'acceptation**
- Exécution locale réussie : composante déterministe identique à la baseline, F1 global affiché et comparé à la baseline du backend utilisé.
- Preuve que la CI échoue sur une régression : sur une branche jetable, dégrader volontairement la composante déterministe, montrer l'échec, puis supprimer la branche.

---

## Lot 2 — Gestion des risques : filtrage des secrets et PII avant le LLM

**Livrables**
- **Un module unique côté Java** (ex. package `governance`, classe `Redactor`), branché au point de passage unique vers les LLM identifié en exploration (cloud et on-prem), activable par config, actif par défaut. Les appels LLM partent du code Java : un module Python ne pourrait pas les intercepter.
- Détection au minimum : mots de passe et tokens en dur, clés API, clés privées, URL JDBC avec identifiants, emails, IP internes. Bibliothèque existante si pertinente, sinon regex — justifie le choix.
- Remplacement par des marqueurs stables `<REDACTED:TYPE:n>` (même secret → même marqueur) pour préserver la structure du code analysé.
- La table de correspondance n'est **jamais** loggée, persistée ni envoyée.
- Journalisation dans le flight recorder : **nombre** de détections par type et par fichier, jamais les valeurs.
- Option de politique : `redact` (défaut) ou `block` (refuser l'envoi si détection).

**Tests**
- Fixtures Java contenant de faux secrets de chaque type.
- Test qui intercepte l'appel client LLM et vérifie qu'aucune valeur sensible n'est dans le payload sortant.
- Non-régression : relancer `eval_golden_dataset.py` ; composante déterministe inchangée, écart de F1 global mesuré et rapporté.

**À documenter** : limites (faux négatifs possibles des regex, ce n'est pas une garantie absolue).

---

## Lot 3 — Sécurité : matrice des droits par agent

**Livrables**
- `docs/GOVERNANCE.md` contenant :
  - Une matrice : pour chacun des 9 composants → lecture fichiers (quels chemins), écriture fichiers, appel LLM, accès TimescaleDB, variables d'environnement / secrets lus. **Chaque cellule marquée « vérifié dans le code » (fichier:ligne) ou « non vérifié ».**
  - Les écarts au moindre privilège identifiés (ex. un agent qui n'a besoin que de lire mais peut écrire).
  - Un état honnête des 4 piliers (cycle de vie, risques, sécurité, observabilité) : ✅ / 🟡 / ❌ avec justification.
- Un lien vers `GOVERNANCE.md` depuis le README.
- **Application minimale** (à proposer dans le plan, pas à implémenter sans validation) : par exemple un accès fichiers en lecture seule injecté aux agents qui n'écrivent pas, ou un test qui échoue si un agent non autorisé écrit sur disque. Pas de sandboxing lourd.

---

## Rapport final attendu (après chaque lot)

1. Ce qui a été fait (fichiers créés / modifiés).
2. Chiffres mesurés, avec la commande exacte qui les a produits et le backend utilisé.
3. Conclusions de `golden-dataset-evaluator` et `cold-diff-reviewer`.
4. Ce qui n'a **pas** été fait ou reste incertain.
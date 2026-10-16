# Rapports d'évaluation versionnés (preuves)

`eval/reports/` est ignoré par git ; seuls les rapports cités comme preuves sont ajoutés
explicitement (`git add -f`). Chaque `.json` est accompagné de la sortie brute d'EvalMain (`.log`).

| Rapport | Ce qu'il prouve | Cité par |
|---|---|---|
| `2026-10-09T100546Z_full_15820.json` + `_passage1..5.log` | 5 passages Claude Haiku, 5 cas : tolérances et seuils Haiku | `eval/baseline.json` |
| `2026-10-09T100546Z_full_3704.json` + `_passage1.log` | 1 passage Ollama de contrôle, 3 cas : F1 global 0.792 | rapport du Lot 1 |
| `2026-10-09T100322Z_deterministic.json` + `_passage1.log` | composante déterministe cas1–cas5, dont cas4 5/0/0 et cas5 3/0/0 | `eval/baseline.json` |
| `2026-10-09T100344Z_deterministic.json` + `_passage1.log` | **dégradation VOLONTAIRE** (extraction `implements` neutralisée, non commitée) : FAIL, code 1 ; cas1 monte à 0.769 | `README.md` |
| `2026-10-09_field_pattern_avant_correctif/` | composante déterministe avec le `DependencyMapperAgent` d'avant correctif | `eval/baseline.json` (note « deterministe ») |
| `2026-10-12_redos_avant_correctif/` | `RedactorReDoSTest` rejoué contre le Redactor d'avant correction : 7 entrées au-delà d'une seconde, 2 StackOverflowError sur 19 | `README.md` (Redaction, Limites) |
| `2026-10-16T130140Z_full_55436.json` + `_passage1..3.log`, `2026-10-16_lot3_avant_api_{tags,ps}.json` | Lot 3, **avant** (code de master `4212b0e`) : 3 passages Ollama, 3 cas — médiane 0.773256 ; empreinte du modèle consignée (`dae161e2…`) | rapport du Lot 3 |
| `2026-10-16T132348Z_full_55500.json` + `_passage1..3.log`, `2026-10-16_lot3_apres_api_{tags,ps}.json` | Lot 3, **après** (`fce3f9d`) : médiane 0.777633 ; composante déterministe identique ; écart +0.004 (< 0.09 : pas de conclusion) ; même empreinte | rapport du Lot 3 |
| `2026-10-16_hook_image_absente/` | hook face à l'image absente (avant / après), puis bout en bout sur `java-legacy-agent-java-analyzer` | `docs/GOVERNANCE.md` (E6) |
| `2026-10-09T141528Z_full_17736.json` + `_passage1..3.log` | Lot 2 : 3 passages Ollama, 3 cas, **redaction active** — médiane 0.759115 (alerte PASS, seuil 0.686250) | rapport du Lot 2 |
| `2026-10-09T143257Z_full_10160.json` + `_passage1..3.log` | Lot 2, contre-épreuve : 3 passages Ollama, **`LLM_REDACTION=off`** — médiane 0.759115 ; écart avec la série précédente non concluant statistiquement (seuil de détection ≈ 0.066) | rapport du Lot 2 |

## Où se mesure l'évaluation LLM

- **En CI, à chaque push** : seul le contrôle déterministe tourne, et il est bloquant.
- **Évaluation LLM** : mesurée **en local** (5 passages Claude Haiku, plus 1 passage
  Ollama de contrôle) ; ce sont les rapports versionnés ci-dessus.
- **Job `full` du workflow** : prêt, mais **non activé faute de budget API**. Il se
  déclenche uniquement par `workflow_dispatch`, sans déclencheur planifié.
- **Pour le réactiver** : poser le secret Actions `ANTHROPIC_API_KEY` du dépôt, puis
  relancer par `workflow_dispatch`.

## Baisse du F1 global observée au Lot 2 (2026-10-09)

Les 6 passages Ollama du 2026-10-09 (`141528Z_full_17736` avec filtrage, `143257Z_full_10160`
sans) sont **sous la médiane de la baseline Ollama** (0.808) : médiane 0.759115 dans les deux
séries.
- Le chemin `LLM_REDACTION=off` retire le décorateur : il équivaut au chemin d'avant le Lot 2.
  La baisse est présente sans filtrage, elle n'est donc pas imputable à la redaction. La
  neutralité du décorateur est établie par `DecoratorTransparencyTest`, pas par ces mesures.
- Hypothèse : dérive de l'environnement. **Non démontrée.** L'empreinte du modèle servi a été
  relevée par `/api/tags` pendant la session, identique à la baseline (`dae161e2…`), mais elle
  n'est consignée ni dans ces rapports ni dans leurs sorties brutes : non vérifiable depuis le dépôt.
- Action proposée, pas encore faite : rejouer la baseline avec une graine fixe et une
  température à 0, si Ollama le permet.

Réserve sur les deux rapports `100546Z_full_*` : passages démarrés sur le commit 026674c,
rapports marqués a29ca36 (le script lisait le commit en fin de run, corrigé en ba32434) ;
aucun fichier Java ne diffère entre ces deux commits.

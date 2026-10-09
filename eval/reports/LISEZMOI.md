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

## Où se mesure l'évaluation LLM

- **En CI, à chaque push** : seul le contrôle déterministe tourne, et il est bloquant.
- **Évaluation LLM** : mesurée **en local** (5 passages Claude Haiku, plus 1 passage
  Ollama de contrôle) ; ce sont les rapports versionnés ci-dessus.
- **Job `full` du workflow** : prêt, mais **non activé faute de budget API**. Il se
  déclenche uniquement par `workflow_dispatch`, sans déclencheur planifié.
- **Pour le réactiver** : poser le secret Actions `ANTHROPIC_API_KEY` du dépôt, puis
  relancer par `workflow_dispatch`.

Réserve sur les deux rapports `100546Z_full_*` : passages démarrés sur le commit 026674c,
rapports marqués a29ca36 (le script lisait le commit en fin de run, corrigé en ba32434) ;
aucun fichier Java ne diffère entre ces deux commits.

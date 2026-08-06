#!/usr/bin/env bash
set -euo pipefail

PIPELINE_PATTERN='(agents/|orchestrator|parsing/)'

# 1. Rien à vérifier si aucun fichier du pipeline n'a changé depuis l'avant-dernier commit
if ! git diff --name-only HEAD~1 2>/dev/null | grep -qE "$PIPELINE_PATTERN"; then
  exit 0
fi

# 2. Un rapport ne peut être fiable que pour un état entièrement commité.
#    Si des fichiers du pipeline sont modifiés sans être commités, bloque
#    directement -- aucun rapport existant ne peut correspondre à cet état
#    (un rapport validé pour HEAD ne dit rien des modifications non
#    commitées faites après coup ; sans ce garde-fou, git rev-parse HEAD
#    à l'étape 3 renverrait le SHA d'avant ces modifications et un vieux
#    rapport PASS laisserait passer un état jamais évalué).
if git diff --name-only HEAD 2>/dev/null | grep -qE "$PIPELINE_PATTERN"; then
  echo "⚠️  Modifications du pipeline non commitées -- aucun rapport ne peut valider cet état." >&2
  echo "Commite d'abord, puis invoque le subagent golden-dataset-evaluator." >&2
  exit 2
fi

# 3. Arbre propre sur le pipeline : un rapport PASS existe-t-il pour ce commit exact ?
CURRENT_SHA=$(git rev-parse HEAD)
LATEST_REPORT=$(ls -t eval_reports/*.json 2>/dev/null | head -n1)

if [[ -n "$LATEST_REPORT" ]] && grep -q "\"commit\": \"$CURRENT_SHA\"" "$LATEST_REPORT" \
   && grep -q '"verdict": "PASS"' "$LATEST_REPORT"; then
  exit 0   # déjà validé pour ce commit exact, pas besoin de relancer
fi

# 4. Pas de rapport frais → bloque et force l'appel au subagent (qui, lui, relance l'éval)
echo "⚠️  Fichiers du pipeline modifiés sans rapport Golden Dataset PASS pour ce commit." >&2
echo "Invoque le subagent golden-dataset-evaluator avant de terminer." >&2
exit 2

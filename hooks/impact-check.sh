#!/bin/sh
# Hook pre-commit — Breaking Change Detector avec CSV ROI
#
# Installation :
#   pip install pre-commit && pre-commit install
#
# CSV ROI généré dans : .java-legacy-agent/impact-roi.csv (AGENT_STATE_DIR, depuis le Lot 3)
# Format : timestamp,class,method,severity,callers_count,callers,blocked,strategy,duration_ms
#
# Tout échec de l'analyse (Docker absent, démon arrêté, image manquante, analyse en échec)
# BLOQUE le commit avec un diagnostic : voir hooks/impact-lib.sh.

. "$(git rev-parse --show-toplevel)/hooks/impact-lib.sh"

SRC_PATH="agent/src"
BREAKING=0
PREFLIGHT_DONE=0

echo "=== Java Breaking Change Detector ==="

for FILE in "$@"; do
    case "$FILE" in *.java) ;; *) continue ;; esac

    CLASS=$(basename "$FILE" .java)

    METHODS=$(git diff --cached "$FILE" | grep "^-.*\(public\|protected\)" \
        | grep -oP '(?<=\s)\w+(?=\()' | sort -u)

    [ -z "$METHODS" ] && continue

    for METHOD in $METHODS; do
        if [ "$PREFLIGHT_DONE" -eq 0 ]; then
            impact_preflight || exit 1
            PREFLIGHT_DONE=1
        fi

        echo "  Analyse : $CLASS.$METHOD()"
        impact_run "$CLASS" "$METHOD" "$SRC_PATH" || exit 1

        case "$SEVERITY" in
            CRITICAL|HIGH|MEDIUM)
                BREAKING=1
                echo "  BLOQUÉ : $CALLERS appelant(s) — $SEVERITY — stratégie : $STRATEGY"

                # Affiche la suggestion de refactoring
                CODE=$(echo "$RESULT" | grep -oP '(?<="code_template": ")[^"]+' \
                    | sed 's/\\n/\n/g')
                if [ -n "$CODE" ]; then
                    echo ""
                    echo "  --- Suggestion de refactoring ($STRATEGY) ---"
                    echo "$CODE" | head -10
                    echo "  ---------------------------------------------"
                fi
                ;;
            LOW)
                echo "  AVERTISSEMENT : $CALLERS appelant(s) — LOW"
                ;;
            NONE)
                echo "  OK — aucun appelant ($DURATION ms)"
                ;;
        esac
    done
done

if [ "$BREAKING" -eq 1 ]; then
    echo ""
    echo "Commit bloqué — voir .java-legacy-agent/impact-roi.csv pour le rapport ROI."
    echo "Corrigez les appelants ou annotez @Deprecated en transition."
    impact_rappel_no_verify
    exit 1
fi

exit 0

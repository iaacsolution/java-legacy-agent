#!/bin/sh
# Hook pre-commit — Breaking Change Detector avec CSV ROI
#
# Installation :
#   pip install pre-commit && pre-commit install
#
# CSV ROI généré dans : impact-roi.csv
# Format : timestamp,class,method,severity,callers_count,callers,blocked,strategy,duration_ms

AGENT_IMAGE="java-legacy-agent-java-agent"
SRC_PATH="agent/src"
CSV_FILE="impact-roi.csv"
BREAKING=0

# Entête CSV si nouveau fichier
if [ ! -f "$CSV_FILE" ]; then
    echo "timestamp,class,method,severity,callers_count,callers,blocked,strategy,duration_ms" > "$CSV_FILE"
fi

echo "=== Java Breaking Change Detector ==="

for FILE in "$@"; do
    case "$FILE" in *.java) ;; *) continue ;; esac

    CLASS=$(basename "$FILE" .java)

    METHODS=$(git diff --cached "$FILE" | grep "^-.*\(public\|protected\)" \
        | grep -oP '(?<=\s)\w+(?=\()' | sort -u)

    [ -z "$METHODS" ] && continue

    for METHOD in $METHODS; do
        echo "  Analyse : $CLASS.$METHOD()"

        WIN_PATH=$(powershell.exe -Command "(Get-Location).Path" 2>/dev/null | tr -d '\r\n')

        RESULT=$(MSYS_NO_PATHCONV=1 docker run --rm \
            -v "${WIN_PATH}:/project" \
            "$AGENT_IMAGE" \
            impact "/project/$SRC_PATH" "$CLASS" "$METHOD" --json 2>&1)

        SEVERITY=$(echo "$RESULT"  | grep -oP '(?<="severity": ")[^"]+')
        CALLERS=$(echo "$RESULT"   | grep -oP '(?<="callers_count": )\d+')
        STRATEGY=$(echo "$RESULT"  | grep -oP '(?<="strategy": ")[^"]+')
        DURATION=$(echo "$RESULT"  | grep -oP '(?<="duration_ms": )\d+')
        CALLERS_LIST=$(echo "$RESULT" | grep -oP '"[A-Za-z]+\.[a-zA-Z]+:\d+"' \
            | tr '\n' '|' | sed 's/|$//')

        TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

        case "$SEVERITY" in
            CRITICAL|HIGH|MEDIUM)
                BLOCKED="true"
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

                # Log CSV ROI
                echo "${TIMESTAMP},${CLASS},${METHOD},${SEVERITY},${CALLERS},\"${CALLERS_LIST}\",${BLOCKED},${STRATEGY},${DURATION}" \
                    >> "$CSV_FILE"
                ;;
            LOW)
                BLOCKED="false"
                echo "  AVERTISSEMENT : $CALLERS appelant(s) — LOW"
                echo "${TIMESTAMP},${CLASS},${METHOD},LOW,${CALLERS},\"${CALLERS_LIST}\",false,DIRECT_UPDATE,${DURATION}" \
                    >> "$CSV_FILE"
                ;;
            NONE)
                echo "  OK — aucun appelant ($DURATION ms)"
                ;;
        esac
    done
done

if [ "$BREAKING" -eq 1 ]; then
    echo ""
    echo "Commit bloqué — voir impact-roi.csv pour le rapport ROI."
    echo "Corrigez les appelants ou annotez @Deprecated en transition."
    exit 1
fi

exit 0

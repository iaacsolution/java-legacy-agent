#!/bin/sh
# Tests des hooks d'impact SANS Docker : un faux exécutable docker simule chaque cas.
#   sh hooks/test-impact-lib.sh
# Code de sortie 0 si tous les cas passent.

RACINE=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
ECHECS=0

# ── Faux docker : comportement piloté par variables d'environnement ─────────
FAKE="$TMP/fake-docker"
cat > "$FAKE" <<'EOF'
#!/bin/sh
echo "$*" >> "${FAKE_LOG:-/dev/null}"
case "$1" in
  image)
    case "$FAKE_INSPECT" in
      ok) exit 0 ;;
      daemon) echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?" >&2; exit 1 ;;
      *) echo "Error: No such image: $3" >&2; exit 1 ;;
    esac ;;
  run)
    cat "$FAKE_RUN_OUT"
    exit "${FAKE_RUN_RC:-0}" ;;
esac
EOF
chmod +x "$FAKE"

verifie() {  # nom, code attendu, code obtenu, motif attendu dans la sortie, sortie
    if [ "$2" = "$3" ] && echo "$5" | grep -q -- "$4"; then
        echo "  ok    $1"
    else
        echo "  ÉCHEC $1 (code attendu $2, obtenu $3 ; motif « $4 »)"
        echo "$5" | sed 's/^/        /'
        ECHECS=$((ECHECS + 1))
    fi
}

lib() {  # exécute une fonction de la bibliothèque dans un sous-shell
    ( . "$RACINE/hooks/impact-lib.sh"; "$@" )
}

json() {  # sévérité, recommandation
    printf '{\n  "severity": "%s",\n  "callers_count": 2,\n  "recommendation": "%s",\n  "strategy": "DEPRECATE",\n  "duration_ms": 12\n}\n' "$1" "$2"
}

echo "=== Diagnostic de pré-vol ==="
OUT=$(DOCKER_BIN="$TMP/absent" lib impact_preflight 2>&1); RC=$?
verifie "Docker absent" 10 $RC "Docker est absent" "$OUT"
verifie "Docker absent : rappel --no-verify" 10 $RC "no-verify" "$OUT"

OUT=$(DOCKER_BIN="$FAKE" FAKE_INSPECT=daemon lib impact_preflight 2>&1); RC=$?
verifie "démon injoignable" 11 $RC "démon Docker ne répond pas" "$OUT"

OUT=$(DOCKER_BIN="$FAKE" FAKE_INSPECT=noimage lib impact_preflight 2>&1); RC=$?
verifie "image manquante" 12 $RC "est absente localement" "$OUT"
verifie "image manquante : nom par défaut affiché" 12 $RC "java-legacy-agent-java-analyzer" "$OUT"
verifie "image manquante : rappel --no-verify" 12 $RC "no-verify" "$OUT"

: > "$TMP/log-image"
OUT=$(DOCKER_BIN="$FAKE" FAKE_LOG="$TMP/log-image" FAKE_INSPECT=noimage AGENT_IMAGE=mon-image:test lib impact_preflight 2>&1); RC=$?
verifie "AGENT_IMAGE surchargé : image demandée et nommée" 12 $RC "mon-image:test" "$OUT"
verifie "AGENT_IMAGE surchargé : c'est elle qui est inspectée" 12 $RC "image inspect mon-image:test" "$(cat "$TMP/log-image")"

OUT=$(DOCKER_BIN="$FAKE" FAKE_INSPECT=ok lib impact_preflight 2>&1); RC=$?
verifie "prêt" 0 $RC "" "$OUT"

echo "=== Analyse ==="
printf 'Exception in thread "main" java.lang.IllegalStateException: boum\n' > "$TMP/out-plante"
OUT=$(DOCKER_BIN="$FAKE" FAKE_RUN_OUT="$TMP/out-plante" FAKE_RUN_RC=1 PROJECT_HOST_PATH=/x lib impact_run A b agent/src 2>&1); RC=$?
verifie "analyse plantée (pas de JSON)" 13 $RC "a échoué (code 1)" "$OUT"
verifie "analyse plantée : sortie montrée" 13 $RC "IllegalStateException" "$OUT"

json NONE "Scan AST échoué" > "$TMP/out-scan"
OUT=$(DOCKER_BIN="$FAKE" FAKE_RUN_OUT="$TMP/out-scan" PROJECT_HOST_PATH=/x lib impact_run A b agent/src 2>&1); RC=$?
verifie "scan échoué rapporté NONE = échec" 13 $RC "a échoué" "$OUT"

printf '{\n  "error": "scan_failed",\n  "reason": "1 fichier(s) non parsé(s) : graphe d appels incomplet",\n  "unparsed_files": ["main/java/A.java"],\n  "excluded_files": 0\n}\n' > "$TMP/out-code3"
OUT=$(DOCKER_BIN="$FAKE" FAKE_RUN_OUT="$TMP/out-code3" FAKE_RUN_RC=3 PROJECT_HOST_PATH=/x lib impact_run A b agent/src 2>&1); RC=$?
verifie "code 3 + JSON scan_failed : bloqué, diagnostic distinct" 14 $RC "n'a pas pu se faire (code 3)" "$OUT"
verifie "code 3 : fichiers non parsés affichés" 14 $RC "main/java/A.java" "$OUT"
verifie "code 3 : exclusions et recours rappelés" 14 $RC "impact-exclusions.txt" "$OUT"
verifie "code 3 : rappel --no-verify" 14 $RC "no-verify" "$OUT"
printf 'sortie tronquée\n' > "$TMP/out-code3-nu"
OUT=$(DOCKER_BIN="$FAKE" FAKE_RUN_OUT="$TMP/out-code3-nu" FAKE_RUN_RC=3 PROJECT_HOST_PATH=/x lib impact_run A b agent/src 2>&1); RC=$?
verifie "code 3 sans JSON : bloqué quand même" 14 $RC "n'a pas pu se faire (code 3)" "$OUT"

: > "$TMP/log-excl"; printf '# vide\n' > "$TMP/exclusions.txt"
DOCKER_BIN="$FAKE" FAKE_LOG="$TMP/log-excl" FAKE_RUN_OUT="$TMP/out-code3" FAKE_RUN_RC=3 PROJECT_HOST_PATH=/x \
    IMPACT_EXCLUSIONS_HOST="$TMP/exclusions.txt" lib impact_run A b agent/src >/dev/null 2>&1
verifie "liste d'exclusions présente : transmise au conteneur" 0 0 "IMPACT_EXCLUSIONS_FILE=/project/impact-exclusions.txt" "$(cat "$TMP/log-excl")"
: > "$TMP/log-excl"
DOCKER_BIN="$FAKE" FAKE_LOG="$TMP/log-excl" FAKE_RUN_OUT="$TMP/out-code3" FAKE_RUN_RC=3 PROJECT_HOST_PATH=/x \
    IMPACT_EXCLUSIONS_HOST="$TMP/absente.txt" lib impact_run A b agent/src >/dev/null 2>&1
if grep -q "IMPACT_EXCLUSIONS_FILE" "$TMP/log-excl"; then
    echo "  ÉCHEC liste absente : la variable ne doit pas être transmise"; ECHECS=$((ECHECS + 1))
else
    echo "  ok    liste d'exclusions absente : rien n'est transmis"
fi

json CRITICAL "2 appelants" > "$TMP/out-critique"
OUT=$(DOCKER_BIN="$FAKE" FAKE_RUN_OUT="$TMP/out-critique" FAKE_RUN_RC=1 PROJECT_HOST_PATH=/x \
      lib sh -c '. "$0"; impact_run A b agent/src; echo "SEV=$SEVERITY"' "$RACINE/hooks/impact-lib.sh" 2>&1); RC=$?
verifie "verdict CRITICAL = analyse aboutie" 0 $RC "SEV=CRITICAL" "$OUT"

: > "$TMP/log"
json NONE "aucun appelant" > "$TMP/out-ok"
DOCKER_BIN="$FAKE" FAKE_LOG="$TMP/log" FAKE_RUN_OUT="$TMP/out-ok" PROJECT_HOST_PATH=/x lib impact_run A b agent/src >/dev/null 2>&1
verifie "AGENT_STATE_DIR transmis au conteneur" 0 0 "AGENT_STATE_DIR=/project/.java-legacy-agent" "$(cat "$TMP/log")"

echo "=== pre-commit complet, dans un dépôt git jetable ==="
REPO="$TMP/repo"
mkdir -p "$REPO/hooks" "$REPO/agent/src"
cp "$RACINE/hooks/pre-commit" "$RACINE/hooks/impact-lib.sh" "$REPO/hooks/"
(
  cd "$REPO" && git init -q && git config user.email t@example.com && git config user.name test &&
  printf 'public class Svc {\n    public void ancienne() {}\n}\n' > agent/src/Svc.java &&
  git add -A && git commit -q -m init --no-verify &&
  printf 'public class Svc {\n}\n' > agent/src/Svc.java && git add agent/src/Svc.java
)
OUT=$(cd "$REPO" && DOCKER_BIN="$TMP/absent" sh hooks/pre-commit 2>&1); RC=$?
verifie "pre-commit, Docker absent : commit bloqué" 1 $RC "Docker est absent" "$OUT"
OUT=$(cd "$REPO" && DOCKER_BIN="$FAKE" FAKE_INSPECT=noimage sh hooks/pre-commit 2>&1); RC=$?
verifie "pre-commit, image manquante : commit bloqué" 1 $RC "est absente localement" "$OUT"
OUT=$(cd "$REPO" && DOCKER_BIN="$FAKE" FAKE_INSPECT=ok FAKE_RUN_OUT="$TMP/out-plante" FAKE_RUN_RC=1 PROJECT_HOST_PATH=/x sh hooks/pre-commit 2>&1); RC=$?
verifie "pre-commit, analyse plantée : commit bloqué" 1 $RC "a échoué" "$OUT"
OUT=$(cd "$REPO" && DOCKER_BIN="$FAKE" FAKE_INSPECT=ok FAKE_RUN_OUT="$TMP/out-code3" FAKE_RUN_RC=3 PROJECT_HOST_PATH=/x sh hooks/pre-commit 2>&1); RC=$?
verifie "pre-commit, code 3 (analyse impossible) : commit bloqué" 1 $RC "n'a pas pu se faire" "$OUT"
OUT=$(cd "$REPO" && DOCKER_BIN="$FAKE" FAKE_INSPECT=ok FAKE_RUN_OUT="$TMP/out-ok" PROJECT_HOST_PATH=/x sh hooks/pre-commit 2>&1); RC=$?
verifie "pre-commit, aucun impact : commit accepté" 0 $RC "aucun changement cassant" "$OUT"

echo
if [ "$ECHECS" -eq 0 ]; then echo "Tous les cas passent."; else echo "$ECHECS cas en échec."; fi
exit "$ECHECS"

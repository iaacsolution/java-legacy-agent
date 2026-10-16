#!/bin/sh
# Bibliothèque commune aux hooks d'analyse d'impact (hooks/pre-commit, hooks/impact-check.sh).
#
# Avant le Lot 3, un docker run en échec (Docker absent, démon arrêté, image manquante,
# analyse plantée) produisait une sortie sans « severity » : le hook n'affichait rien et
# laissait passer le commit. Désormais tout échec BLOQUE le commit, avec un diagnostic
# qui distingue la cause, et rappelle --no-verify.
#
# Surcharges (tests) : DOCKER_BIN (défaut : docker), AGENT_IMAGE, PROJECT_HOST_PATH.

DOCKER_BIN="${DOCKER_BIN:-docker}"
# Image produite par le service compose java-analyzer (build: ./agent, projet
# java-legacy-agent). L'ancien défaut, java-legacy-agent-java-agent, désignait un service
# « java-agent » scindé depuis en java-analyzer / java-reporter : l'image n'existait plus.
# Surchargeable : AGENT_IMAGE=<image> git commit …
AGENT_IMAGE="${AGENT_IMAGE:-java-legacy-agent-java-analyzer}"

# Rappel commun à tous les blocages.
impact_rappel_no_verify() {
    echo "  Pour committer quand même, en connaissance de cause : git commit --no-verify"
    echo "  (attention : --no-verify saute AUSSI le scan de secrets et la garde logRequests)."
}

# Vérifie que l'analyse peut tourner. Code 0 si oui ; sinon affiche la cause et renvoie
# 10 (Docker absent), 11 (démon injoignable) ou 12 (image manquante).
impact_preflight() {
    if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
        echo "BLOQUÉ : analyse d'impact impossible — Docker est absent (commande « $DOCKER_BIN » introuvable)."
        impact_rappel_no_verify
        return 10
    fi
    INSPECT_ERR=$("$DOCKER_BIN" image inspect "$AGENT_IMAGE" 2>&1 >/dev/null)
    if [ $? -ne 0 ]; then
        if echo "$INSPECT_ERR" | grep -qiE "cannot connect|daemon|error during connect|pipe|is the docker daemon running"; then
            echo "BLOQUÉ : analyse d'impact impossible — le démon Docker ne répond pas (Docker Desktop arrêté ?)."
            impact_rappel_no_verify
            return 11
        fi
        echo "BLOQUÉ : analyse d'impact impossible — l'image « $AGENT_IMAGE » est absente localement."
        impact_rappel_no_verify
        return 12
    fi
    return 0
}

# Lance l'analyse d'une méthode. Renseigne RESULT, SEVERITY, CALLERS, STRATEGY, DURATION.
# Code 0 si l'analyse a abouti (quel que soit son verdict) ; 13 si elle a échoué.
impact_run() {
    _class="$1"; _method="$2"; _src="$3"
    _host="${PROJECT_HOST_PATH:-}"
    if [ -z "$_host" ]; then
        # Chemin Windows pour Docker Desktop (Git Bash) ; sinon le répertoire courant.
        _host=$(powershell.exe -Command "(Get-Location).Path" 2>/dev/null | tr -d '\r\n')
        [ -z "$_host" ] && _host=$(pwd)
    fi
    # Le dossier d'état est sur le volume du dépôt : le cache et impact-roi.csv survivent
    # au conteneur (AGENT_STATE_DIR, hors du chemin analysé /project/$_src).
    RESULT=$(MSYS_NO_PATHCONV=1 "$DOCKER_BIN" run --rm \
        -v "${_host}:/project" \
        -e AGENT_STATE_DIR=/project/.java-legacy-agent \
        "$AGENT_IMAGE" \
        impact "/project/$_src" "$_class" "$_method" --json 2>&1)
    _rc=$?
    SEVERITY=$(echo "$RESULT" | grep -oP '(?<="severity": ")[^"]+' | head -1)
    CALLERS=$(echo "$RESULT"  | grep -oP '(?<="callers_count": )\d+' | head -1)
    STRATEGY=$(echo "$RESULT" | grep -oP '(?<="strategy": ")[^"]+' | head -1)
    DURATION=$(echo "$RESULT" | grep -oP '(?<="duration_ms": )\d+' | head -1)

    # Un scan échoué est rapporté par l'outil avec la sévérité NONE (écart documenté dans
    # docs/GOVERNANCE.md) : c'est un échec d'analyse, pas une absence d'impact.
    if [ -z "$SEVERITY" ] || echo "$RESULT" | grep -q "Scan AST échoué"; then
        echo "BLOQUÉ : l'analyse d'impact de $_class.$_method() a échoué (code $_rc) — dernières lignes :"
        echo "$RESULT" | tail -5 | sed 's/^/    /'
        impact_rappel_no_verify
        return 13
    fi
    return 0
}

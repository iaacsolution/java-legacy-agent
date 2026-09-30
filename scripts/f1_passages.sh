#!/bin/sh
# Execute N passages de l'eval F1 sur Ollama, en conservant les sorties brutes.
#
# Chaque log brut porte la provenance du backend, parce qu'epingler l'URL ne
# prouve pas a soi seul quelle instance a servi la requete : DEUX serveurs
# Ollama ecoutent sur 11434 (natif Windows sur 127.0.0.1, conteneur Docker sur
# ::1 via le relais WSL).
#   - en-tete : /api/tags sur la cible -> le NOMBRE de modeles discrimine
#               (1 = natif Windows, 3 = conteneur Docker)
#   - pied    : /api/ps sur les DEUX points d'acces -> l'instance qui a
#               travaille a le modele resident
#
# Usage : sh scripts/f1_passages.sh <etiquette> <nb_passages>

set -e
ETIQUETTE="$1"
NB="${2:-3}"
[ -n "$ETIQUETTE" ] || { echo "usage: $0 <etiquette> [nb_passages]"; exit 1; }

RACINE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RACINE"

JAR="agent/target/java-legacy-agent-1.0.0.jar"
DATASET="golden_dataset_3cases.json"
CIBLE="http://127.0.0.1:11434"
SORTIE="results/raw"

[ -f "$JAR" ]     || { echo "jar absent : $JAR"; exit 1; }
[ -f "$DATASET" ] || { echo "dataset absent : $DATASET"; exit 1; }
mkdir -p "$SORTIE"

COMMIT=$(git rev-parse HEAD)
echo "=== serie '$ETIQUETTE' — $NB passages — commit $(git rev-parse --short HEAD) ==="

i=1
while [ "$i" -le "$NB" ]; do
  LOG="$SORTIE/f1_${ETIQUETTE}_${i}.log"

  {
    echo "########## PROVENANCE (en-tete) ##########"
    echo "etiquette      : $ETIQUETTE"
    echo "passage        : $i / $NB"
    echo "date_utc       : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit         : $COMMIT"
    echo "dataset        : $DATASET"
    echo "OLLAMA_BASE_URL: $CIBLE   (epingle : 'localhost' serait ambigu IPv4/IPv6)"
    echo "FLIGHTREC_ENABLED: false"
    echo "--- GET $CIBLE/api/tags ---"
    curl -s -m 10 "$CIBLE/api/tags" || echo "(injoignable)"
    echo
    echo "########## SORTIE EVALMAIN ##########"
  } > "$LOG"

  OLLAMA_BASE_URL="$CIBLE" \
  FLIGHTREC_ENABLED=false \
  java -Dstdout.encoding=UTF-8 -cp "$JAR" \
       com.audensiel.legacy.agent.EvalMain "$DATASET" >> "$LOG" 2>&1

  {
    echo
    echo "########## PROVENANCE (pied) ##########"
    echo "fin_utc        : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "--- GET $CIBLE/api/ps  (instance visee) ---"
    curl -s -m 10 "$CIBLE/api/ps" || echo "(injoignable)"
    echo
    echo "--- GET http://[::1]:11434/api/ps  (autre instance, pour comparaison) ---"
    curl -s -m 10 "http://[::1]:11434/api/ps" || echo "(injoignable)"
    echo
  } >> "$LOG"

  echo "  passage $i termine -> $LOG"
  i=$((i + 1))
done

echo "=== serie '$ETIQUETTE' terminee ==="

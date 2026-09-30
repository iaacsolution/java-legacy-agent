#!/usr/bin/env python
"""
Garde-fou PreToolUse : refuse les commandes Docker destructives.

Motivation concrete : un « docker compose --profile X down -v » lance dans ce
depot a detruit les volumes de TOUS les services du projet (grafana, phoenix,
airflow, open-webui...), pas seulement ceux du profil vise. Le drapeau -v ne
se limite pas au profil, et la suppression d un volume est irreversible.

Commandes refusees :
  - docker compose down -v / --volumes  (et docker-compose)
  - docker volume rm
  - docker volume prune
  - docker system prune

Lit le JSON du hook sur stdin, ecrit une decision JSON sur stdout.
Ne bloque jamais autre chose : en cas de doute il laisse passer (le refus doit
etre precis, sinon il sera contourne).
"""

import json
import re
import sys

# Un segment = une commande simple, isolee des enchainements ; && || | et retours ligne.
SEPARATEURS = re.compile(r"(?:\|\||&&|;|\||\n)")

MOTIFS = [
    (
        re.compile(r"\bdocker(?:-compose\b|\s+compose\b)(?=.*\bdown\b)(?=.*(?:\s-v\b|\s--volumes\b))", re.IGNORECASE),
        "docker compose down -v",
        "-v supprime les volumes de TOUS les services du projet, pas seulement ceux du profil vise.",
    ),
    (
        re.compile(r"\bdocker\s+volume\s+rm\b", re.IGNORECASE),
        "docker volume rm",
        "Suppression irreversible d un volume.",
    ),
    (
        re.compile(r"\bdocker\s+volume\s+prune\b", re.IGNORECASE),
        "docker volume prune",
        "Supprime tous les volumes non utilises, y compris ceux d autres projets.",
    ),
    (
        re.compile(r"\bdocker\s+system\s+prune\b", re.IGNORECASE),
        "docker system prune",
        "Supprime conteneurs, reseaux, images et potentiellement les volumes.",
    ),
]


def analyse(commande):
    for segment in SEPARATEURS.split(commande or ""):
        for motif, nom, pourquoi in MOTIFS:
            if motif.search(segment):
                return nom, pourquoi, segment.strip()
    return None


def main():
    try:
        donnees = json.load(sys.stdin)
    except Exception:
        return 0  # entree illisible : on ne bloque pas

    commande = (donnees.get("tool_input") or {}).get("command", "")
    trouve = analyse(commande)
    if not trouve:
        return 0

    nom, pourquoi, segment = trouve
    raison = (
        "BLOQUE : commande Docker destructive (%s).\n"
        "%s\n"
        "Segment : %s\n"
        "Cette commande necessite l accord explicite de l utilisateur. "
        "Pour ne viser qu un seul service, utiliser « docker compose rm -sf <service> », "
        "qui ne touche pas aux volumes."
    ) % (nom, pourquoi, segment)

    json.dump(
        {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": raison,
            }
        },
        sys.stdout,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

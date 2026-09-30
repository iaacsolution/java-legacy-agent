#!/usr/bin/env python
"""
Agrege les sorties brutes de l'eval F1 en results/f1_ollama.json.

Ne recalcule rien : chaque chiffre du JSON est lu dans un log brut, qui reste
commite a cote. Le JSON est un index de sorties brutes, pas une source
independante — c'est ce qui rend les chiffres verifiables.

Verifie aussi la provenance de chaque passage : l'en-tete doit montrer
exactement UN modele servi (Ollama natif Windows). Deux serveurs ecoutent sur
11434, un passage servi par le conteneur Docker serait ecarte.

Usage : python scripts/f1_collecte.py <etiquette1> [<etiquette2> ...]
"""

import glob
import json
import os
import re
import statistics
import sys
from datetime import UTC, datetime

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SCORE = re.compile(
    r"Precision=([\d.,]+)\s+Recall=([\d.,]+)\s+F1=([\d.,]+)\s*"
    r"\(TP=(\d+)\s+FP=(\d+)\s+FN=(\d+)\)"
)
CAS = re.compile(r"(cas\d+)\s*:")
MOYENNE = re.compile(r"(DependencyMapper|Risques|Responsabilit\w*)[^:]*moyen[^:]*:\s*([\d.,]+)")
GLOBAL = re.compile(r"F1 GLOBAL[^:]*:\s*([\d.,]+)")
COMMIT = re.compile(r"^commit\s*:\s*([0-9a-f]{40})", re.MULTILINE)
DATE = re.compile(r"^date_utc\s*:\s*(\S+)", re.MULTILINE)


def nombre(s):
    return float(s.replace(",", "."))


def composante(ligne):
    """Rend le nom de composante d'une ligne de score, ou None."""
    if "DependencyMapper" in ligne:
        return "dependency_mapper"
    if "Risques" in ligne:
        return "risques"
    if "Responsabilit" in ligne:
        return "responsabilites"
    return None


def provenance(texte):
    """Nb de modeles servis par la cible, lu dans l'en-tete. -1 si illisible."""
    tete = texte.split("########## SORTIE EVALMAIN ##########")[0]
    m = re.search(r"\{.*\}", tete, re.DOTALL)
    if not m:
        return -1, []
    try:
        d = json.loads(m.group(0))
        ms = d.get("models", [])
        return len(ms), ms
    except Exception:
        return -1, []


def lit_passage(chemin):
    texte = open(chemin, encoding="utf-8", errors="replace").read()

    nb_modeles, modeles = provenance(texte)
    mc, md = COMMIT.search(texte), DATE.search(texte)

    par_cas, courant = [], None
    for ligne in texte.splitlines():
        mcas = CAS.search(ligne)
        if mcas and "━" in ligne:
            courant = {"cas": mcas.group(1)}
            par_cas.append(courant)
            continue
        ms = SCORE.search(ligne)
        if ms and courant is not None:
            comp = composante(ligne)
            if comp:
                courant[comp] = {
                    "precision": nombre(ms.group(1)),
                    "recall": nombre(ms.group(2)),
                    "f1": nombre(ms.group(3)),
                    "tp": int(ms.group(4)),
                    "fp": int(ms.group(5)),
                    "fn": int(ms.group(6)),
                }

    moyennes = {}
    for m in MOYENNE.finditer(texte):
        cle = composante(m.group(1))
        if cle:
            moyennes[cle] = nombre(m.group(2))

    mg = GLOBAL.search(texte)

    return {
        "sortie_brute": os.path.relpath(chemin, RACINE).replace(os.sep, "/"),
        "date_utc": md.group(1) if md else None,
        "commit": mc.group(1) if mc else None,
        "backend_verifie": {
            "modeles_servis_par_la_cible": nb_modeles,
            "instance": ("Ollama natif Windows" if nb_modeles == 1
                         else "NON CONFORME — passage a ecarter"),
            "empreinte_modele": (modeles[0]["digest"] if modeles else None),
        },
        "f1_global": nombre(mg.group(1)) if mg else None,
        "composantes_moyennes": moyennes,
        "par_cas": par_cas,
    }


def stats(valeurs):
    v = [x for x in valeurs if x is not None]
    if not v:
        return None
    return {
        "mediane": round(statistics.median(v), 4),
        "minimum": min(v),
        "maximum": max(v),
        "valeurs": v,
    }


def main(etiquettes):
    series = []
    for et in etiquettes:
        motif = os.path.join(RACINE, "results", "raw", "f1_%s_*.log" % et)
        fichiers = sorted(glob.glob(motif))
        if not fichiers:
            print("aucun log pour la serie '%s' (%s)" % (et, motif), file=sys.stderr)
            return 1
        passages = []
        for n, f in enumerate(fichiers, 1):
            p = lit_passage(f)
            p["n"] = n
            passages.append(p)
        commits = {p["commit"] for p in passages if p["commit"]}
        series.append({
            "etiquette": et,
            "commit": commits.pop() if len(commits) == 1 else sorted(commits),
            "passages": passages,
        })

    # ── Synthese : le deterministe et le global ne se lisent pas pareil ──
    synthese = {"par_serie": {}}
    for s in series:
        det = [p["composantes_moyennes"].get("dependency_mapper") for p in s["passages"]]
        glo = [p["f1_global"] for p in s["passages"]]
        det_v = [x for x in det if x is not None]
        synthese["par_serie"][s["etiquette"]] = {
            "composante_deterministe": {
                "valeurs": det_v,
                "constante": len(set(det_v)) == 1,
                "valeur": det_v[0] if len(set(det_v)) == 1 else None,
            },
            "f1_global": stats(glo),
        }

    synthese["lecture"] = {
        "composante_deterministe":
            "DependencyMapper — regex, sans LLM. Exacte et sans derive : c'est LA que se lit "
            "l'effet de la correction FIELD_PATTERN. Une variation entre passages signalerait "
            "un probleme, pas du bruit.",
        "f1_global":
            "Moyenne des trois composantes, dont deux passent par le LLM. Son ecart entre "
            "series inclut EN PLUS la derive LLM : il n'est donc PAS attribuable a la seule "
            "correction. Citer la mediane avec son etendue, jamais une valeur isolee.",
    }

    modeles = [p["backend_verifie"] for s in series for p in s["passages"]]
    empreintes = {m["empreinte_modele"] for m in modeles if m["empreinte_modele"]}
    non_conformes = [m for m in modeles if m["modeles_servis_par_la_cible"] != 1]

    sortie = {
        "genere_le": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dataset": "golden_dataset_3cases.json",
        "backend": {
            "type": "ollama",
            "endpoint": "http://127.0.0.1:11434",
            "instance": "Ollama natif Windows",
            "avertissement": (
                "Deux serveurs Ollama ecoutent sur 11434 : le natif Windows sur 127.0.0.1 "
                "(1 modele) et un conteneur Docker sur [::1] via le relais WSL (3 modeles). "
                "'localhost' est donc ambigu selon la resolution IPv4/IPv6 — l'URL est "
                "epinglee, et chaque log brut porte la verification de l'instance ayant repondu."
            ),
            "modele": "qwen2.5-coder:7b",
            "empreinte": sorted(empreintes)[0] if len(empreintes) == 1 else sorted(empreintes),
            "temperature": 0.1,
            "temperature_source": "JavaDocumentationAgent.java — analyzeJavaClass passe par coderModel (0.1)",
            "flightrec_enabled": False,
        },
        "provenance_conforme": not non_conformes,
        "series": series,
        "synthese": synthese,
    }

    chemin = os.path.join(RACINE, "results", "f1_ollama.json")
    open(chemin, "w", encoding="utf-8", newline="\n").write(
        json.dumps(sortie, indent=2, ensure_ascii=False) + "\n")
    print("ecrit :", os.path.relpath(chemin, RACINE))
    if non_conformes:
        print("ATTENTION : %d passage(s) non conforme(s)" % len(non_conformes), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["avant_correction", "apres_correction_B"]))

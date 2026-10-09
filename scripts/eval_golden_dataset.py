"""Évaluation du golden dataset contre une baseline versionnée (eval/baseline.json).

Ce script NE CALCULE PAS le F1 : il lance EvalMain (Java) avec --json et lit son résultat.
La seule arithmétique faite ici est la moyenne par composante sur le sous-ensemble des
cas historiques (cas1-cas3), avec la même formule qu'EvalMain.evaluate ; la cohérence de
cette formule est vérifiée à chaque passage sur l'ensemble des cas (écart < 1e-9 avec le
global produit par Java, sinon code 2).

Deux contrôles, de nature différente :
  - composante déterministe (DependencyMapper, regex, sans LLM) : TP/FP/FN comparés
    EXACTEMENT par cas. C'est le seul contrôle bloquant en CI.
  - F1 global LLM : comparé à la baseline DU BACKEND ACTIF (médiane - tolérance). Les
    composantes LLM dérivent d'un passage à l'autre ; ce contrôle est une alerte.
    Sans baseline ou sans tolérance validée pour ce backend, il est « NON_EVALUE ».

Codes de sortie : 0 = PASS (ou LLM non évalué), 1 = régression, 2 = erreur d'exécution
ou incohérence.

Usage :
  python scripts/eval_golden_dataset.py --mode deterministic
  python scripts/eval_golden_dataset.py --mode full --dataset golden_dataset_3cases.json
  python scripts/eval_golden_dataset.py --mode full --passes 5
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
JAR_DEFAUT = RACINE / "agent" / "target" / "java-legacy-agent-1.0.0.jar"
CLASSE = "com.audensiel.legacy.agent.EvalMain"
COMPOSANTES_LLM = ("risques", "responsabilites")
COMPOSANTES = ("dependency_mapper",) + COMPOSANTES_LLM

PASS, FAIL, NON_EVALUE = "PASS", "FAIL", "NON_EVALUE"


class ErreurEval(Exception):
    """Erreur d'exécution ou incohérence : code de sortie 2."""


def lire_json(chemin: Path) -> dict:
    with open(chemin, encoding="utf-8") as f:
        return json.load(f)


def ecrire_json(chemin: Path, donnees: dict) -> None:
    chemin.parent.mkdir(parents=True, exist_ok=True)
    with open(chemin, "w", encoding="utf-8", newline="\n") as f:
        json.dump(donnees, f, ensure_ascii=False, indent=2)
        f.write("\n")


def git(*args: str) -> str:
    try:
        r = subprocess.run(["git", *args], cwd=RACINE, capture_output=True,
                           encoding="utf-8", errors="replace", check=True)
        return r.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def relatif(chemin: Path) -> str:
    """Jamais de chemin absolu dans un rapport commitable (nom d'utilisateur, arborescence)."""
    try:
        return chemin.resolve().relative_to(RACINE).as_posix()
    except ValueError:
        return chemin.name


# ── Exécution d'EvalMain ──────────────────────────────────────────────────────

def lancer_evalmain(jar: Path, dataset: Path, mode: str, log: Path) -> dict:
    env = dict(os.environ)
    env.setdefault("FLIGHTREC_ENABLED", "false")
    if mode == "full":
        # Deux serveurs Ollama peuvent écouter sur 11434 (natif IPv4, Docker IPv6) :
        # « localhost » est ambigu. Épinglé sauf choix explicite de l'appelant.
        env.setdefault("OLLAMA_BASE_URL", "http://127.0.0.1:11434")

    with tempfile.TemporaryDirectory() as tmp:
        sortie_json = Path(tmp) / "resultat.json"
        cmd = ["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
               "-cp", str(jar), CLASSE, str(dataset), "--json", str(sortie_json)]
        if mode == "deterministic":
            cmd.append("--deterministic-only")

        # cwd = racine : EvalMain affiche le dataset en relatif à son répertoire de travail.
        r = subprocess.run(cmd, cwd=RACINE, env=env, capture_output=True,
                           encoding="utf-8", errors="replace")
        log.parent.mkdir(parents=True, exist_ok=True)
        with open(log, "w", encoding="utf-8", newline="\n") as f:
            f.write(r.stdout)
            if r.stderr:
                f.write("\n── stderr ──\n")
                f.write(r.stderr)

        if r.returncode != 0:
            raise ErreurEval(f"EvalMain a échoué (code {r.returncode}), voir {relatif(log)}")
        if not sortie_json.exists():
            raise ErreurEval(f"EvalMain n'a pas produit de JSON, voir {relatif(log)}")
        return lire_json(sortie_json)


# ── Arithmétique : uniquement la moyenne d'EvalMain.evaluate ─────────────────

def moyennes(par_cas: list[dict], noms: set[str] | None = None) -> dict:
    """Même formule qu'EvalMain.evaluate : moyenne simple par composante, global = moyenne des 3."""
    cas = [c for c in par_cas if noms is None or c["cas"] in noms]
    if not cas:
        return {}
    res = {}
    for comp in COMPOSANTES:
        valeurs = [c[comp]["f1"] for c in cas if c.get(comp) is not None]
        res[comp] = sum(valeurs) / len(valeurs) if len(valeurs) == len(cas) else None
    res["f1_global"] = (None if any(res[c] is None for c in COMPOSANTES)
                        else sum(res[c] for c in COMPOSANTES) / 3.0)
    return res


def verifier_coherence(resultat: dict) -> None:
    """Preuve à chaque passage que la moyenne Python est celle de Java (même méthode)."""
    m = moyennes(resultat["par_cas"])
    java = dict(resultat["composantes_moyennes"], f1_global=resultat["f1_global"])
    for cle, val in m.items():
        if (val is None) != (java[cle] is None) or (val is not None and abs(val - java[cle]) > 1e-9):
            raise ErreurEval(f"incohérence Python/Java sur {cle} : {val} vs {java[cle]}")


def stats(valeurs: list[float]) -> dict:
    return {
        "valeurs": [round(v, 6) for v in valeurs],
        "mediane": round(statistics.median(valeurs), 6),
        "minimum": round(min(valeurs), 6),
        "maximum": round(max(valeurs), 6),
        # écart-type d'échantillon (n-1) ; null avec un seul passage
        "ecart_type": round(statistics.stdev(valeurs), 6) if len(valeurs) > 1 else None,
    }


# ── Comparaisons à la baseline ────────────────────────────────────────────────
# Les sous-contrôles portent un « statut », jamais un « verdict » : le hook Stop
# (.claude/hooks/check-golden-dataset.sh) cherche '"verdict": "PASS"' n'importe où dans
# le fichier ; un sous-contrôle PASS ne doit pas pouvoir valider un rapport FAIL.

def controle_deterministe(passages: list[dict], baseline: dict) -> dict:
    attendu = baseline["deterministe"]["par_cas"]
    ecarts = []
    non_couverts = set()
    for i, p in enumerate(passages, 1):
        for c in p["par_cas"]:
            ref = attendu.get(c["cas"])
            if ref is None:
                non_couverts.add(c["cas"])
                continue
            obs = c["dependency_mapper"]
            for k in ("tp", "fp", "fn"):
                if obs[k] != ref[k]:
                    ecarts.append(f"passage {i}, {c['cas']} : {k.upper()}={obs[k]} attendu {ref[k]}")
        manquants = set(attendu) - {c["cas"] for c in p["par_cas"]}
        if manquants and p["dataset"].endswith("golden_dataset.json"):
            ecarts.append(f"passage {i} : cas de la baseline absents du dataset : {sorted(manquants)}")
    return {
        "statut": FAIL if ecarts else PASS,
        "bloquant": True,
        "ecarts": ecarts,
        "cas_sans_reference": sorted(non_couverts),
    }


def controle_llm(passages: list[dict], baseline: dict, historiques: set[str]) -> dict:
    backend = passages[0]["backend"]
    if any(p["backend"] != backend for p in passages):
        raise ErreurEval("le backend a changé entre deux passages")

    observe = {
        "cas_historiques": stats([moyennes(p["par_cas"], historiques)["f1_global"] for p in passages]),
    }
    dataset = passages[0]["dataset"]
    if {c["cas"] for c in passages[0]["par_cas"]} != historiques:
        observe[dataset] = stats([p["f1_global"] for p in passages])

    ref_backend = baseline.get("llm", {}).get(backend)
    resultat = {"backend": backend, "bloquant": False, "observe": observe, "comparaisons": []}
    if not ref_backend:
        resultat["statut"] = NON_EVALUE
        resultat["raison"] = f"aucune baseline pour le backend {backend}"
        return resultat

    verdicts = []
    for perimetre, obs in observe.items():
        ref = ref_backend.get(perimetre)
        if not ref:
            continue
        tol = ref.get("tolerance")
        if tol is None:
            resultat["comparaisons"].append(
                {"perimetre": perimetre, "statut": NON_EVALUE, "raison": "tolérance non encore validée"})
            verdicts.append(NON_EVALUE)
            continue
        seuil = ref["f1_global"]["mediane"] - tol
        v = PASS if obs["mediane"] >= seuil - 1e-9 else FAIL
        resultat["comparaisons"].append({
            "perimetre": perimetre, "statut": v, "observe_mediane": obs["mediane"],
            "baseline_mediane": ref["f1_global"]["mediane"], "tolerance": tol, "seuil": round(seuil, 6)})
        verdicts.append(v)

    if FAIL in verdicts:
        resultat["statut"] = FAIL
    elif verdicts and all(v == PASS for v in verdicts):
        resultat["statut"] = PASS
    else:
        resultat["statut"] = NON_EVALUE
        resultat.setdefault("raison", "aucune comparaison possible avec un seuil validé")
    return resultat


# ── Résumé lisible ────────────────────────────────────────────────────────────

def f3(x) -> str:
    # Format Python : point décimal quelle que soit la locale (jamais « 0,714 »).
    return "—" if x is None else f"{x:.3f}"


def resume(rapport: dict, baseline: dict) -> str:
    lignes = ["=" * 65, f"  ÉVALUATION GOLDEN DATASET — mode {rapport['mode']}",
              f"  Dataset : {rapport['dataset']}   Passages : {rapport['passages']}",
              f"  Commit  : {rapport['commit'][:12]}{' (arbre modifié)' if rapport['arbre_modifie'] else ''}",
              "=" * 65]
    det = rapport["controles"]["deterministe"]
    lignes.append(f"  [BLOQUANT] Composante déterministe (TP/FP/FN exacts) : {det['statut']}")
    for c in rapport["par_cas_dernier_passage"]:
        d = c["dependency_mapper"]
        lignes.append(f"      {c['cas']:<6} F1={f3(d['f1'])}  TP={d['tp']} FP={d['fp']} FN={d['fn']}")
    for e in det["ecarts"]:
        lignes.append(f"      ✗ {e}")
    if det["cas_sans_reference"]:
        lignes.append(f"      (sans référence dans la baseline : {', '.join(det['cas_sans_reference'])})")

    llm = rapport["controles"].get("llm")
    if llm:
        lignes.append(f"  [ALERTE]   F1 global LLM — backend {llm['backend']} : {llm['statut']}")
        for perimetre, s in llm["observe"].items():
            lignes.append(f"      {perimetre:<26} médiane={f3(s['mediane'])}  min={f3(s['minimum'])}  "
                          f"max={f3(s['maximum'])}  σ={f3(s['ecart_type'])}")
        for comp in rapport.get("composantes_stats", {}):
            s = rapport["composantes_stats"][comp]
            lignes.append(f"      composante {comp:<16} médiane={f3(s['mediane'])}  min={f3(s['minimum'])}  "
                          f"max={f3(s['maximum'])}  σ={f3(s['ecart_type'])}")
        for cmp in llm["comparaisons"]:
            if "seuil" in cmp:
                lignes.append(f"      {cmp['perimetre']} : médiane {f3(cmp['observe_mediane'])} vs seuil "
                              f"{f3(cmp['seuil'])} (baseline {f3(cmp['baseline_mediane'])} − {f3(cmp['tolerance'])})")
        if llm.get("raison"):
            lignes.append(f"      ({llm['raison']})")
        hist = baseline.get("historique", {})
        if hist:
            lignes.append("      Repères historiques, NON comparables : "
                          + ", ".join(f"{k} ({v['perimetre']})" for k, v in hist.items()))
    lignes.append("-" * 65)
    lignes.append(f"  VERDICT : {rapport['verdict']}")
    lignes.append("=" * 65)
    return "\n".join(lignes)


# ── Programme principal ───────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=("deterministic", "full"), default="deterministic")
    ap.add_argument("--dataset", type=Path, default=RACINE / "golden_dataset.json")
    ap.add_argument("--baseline", type=Path, default=RACINE / "eval" / "baseline.json")
    ap.add_argument("--jar", type=Path, default=JAR_DEFAUT)
    ap.add_argument("--reports", type=Path, default=RACINE / "eval" / "reports")
    ap.add_argument("--passes", type=int, default=1, help="nombre de passages (statistiques de dérive LLM)")
    args = ap.parse_args()

    # Sortie console en UTF-8 même sous une console Windows cp1252.
    for flux in (sys.stdout, sys.stderr):
        if hasattr(flux, "reconfigure"):
            flux.reconfigure(encoding="utf-8", errors="replace")

    horodatage = dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H%M%SZ")
    base_nom = f"{horodatage}_{args.mode}"
    try:
        if not args.jar.exists():
            raise ErreurEval(f"jar introuvable : {relatif(args.jar)} — lancer `mvn -f agent/pom.xml package`")
        if args.passes < 1:
            raise ErreurEval("--passes doit être >= 1")
        baseline = lire_json(args.baseline)
        historiques = set(baseline["cas_historiques"])

        passages = []
        logs = []
        for i in range(1, args.passes + 1):
            log = args.reports / f"{base_nom}_passage{i}.log"
            print(f"  passage {i}/{args.passes} … (sortie brute : {relatif(log)})", flush=True)
            r = lancer_evalmain(args.jar, args.dataset, args.mode, log)
            verifier_coherence(r)
            passages.append(r)
            logs.append(relatif(log))

        controles = {"deterministe": controle_deterministe(passages, baseline)}
        composantes_stats = {}
        if args.mode == "full":
            controles["llm"] = controle_llm(passages, baseline, historiques)
            for comp in COMPOSANTES:
                composantes_stats[comp] = stats([p["composantes_moyennes"][comp] for p in passages])

        # Seul le déterministe bloque ; le LLM est une alerte (dérive non reproductible).
        verdict = controles["deterministe"]["statut"]
        if verdict == PASS and controles.get("llm", {}).get("statut") == FAIL:
            verdict = "PASS_AVEC_ALERTE_LLM"

        rapport = {
            "commit": git("rev-parse", "HEAD"),
            "arbre_modifie": bool(git("status", "--porcelain", "--untracked-files=no")),
            "date_utc": horodatage,
            "mode": args.mode,
            "dataset": relatif(args.dataset),
            "baseline": relatif(args.baseline),
            "passages": args.passes,
            "sorties_brutes": logs,
            "commande": "python scripts/eval_golden_dataset.py " + " ".join(sys.argv[1:]),
            "verdict": verdict,
            "controles": controles,
            "composantes_stats": composantes_stats,
            "par_cas_dernier_passage": passages[-1]["par_cas"],
            "resultats_bruts": passages,
        }
        chemin_rapport = args.reports / f"{base_nom}.json"
        ecrire_json(chemin_rapport, rapport)
        print(resume(rapport, baseline))
        print(f"  Rapport : {relatif(chemin_rapport)}")
        return 1 if controles["deterministe"]["statut"] == FAIL else 0

    except ErreurEval as e:
        print(f"ERREUR : {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

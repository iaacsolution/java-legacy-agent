"""
Benchmark de speedup — AGENT_WORKERS=1 vs AGENT_WORKERS=4, backend cloud (Claude Haiku).

Invoque le VRAI pipeline (java -jar ... analyze demo-project <handoff>), pas un mock — lit la
durée mesurée par RunMetrics (duration_total_ms) dans metrics_demo-project.json exporté par
chaque run, plutôt que de mesurer le wall-clock du sous-processus Python (qui inclurait le
démarrage JVM, non pertinent pour comparer le parallélisme du batch d'analyse).

⚠️ ALLOW_CLOUD_CODE_ANALYSIS=true — active le backend cloud Claude Haiku pour la durée de ce
script uniquement (passé en variable d'environnement à chaque sous-processus, jamais exporté
globalement). Classes envoyées : les 4 classes de demo-project/ (ClientServiceBean,
InvoiceGeneratorBean, OrderServiceBean, PaymentProcessorBean) — code de démo synthétique
uniquement, vérifié avant activation (voir commit qui introduit ce script). Chaque appel LLM
envoie le squelette anonymisé de la classe (jamais le corps des méthodes, voir
JavaDocumentationAgent.analyzeJavaClassWithAst) — même politique que le pipeline en production.

Chaque run consomme de vrais appels API payants (1 par classe, donc 4 par run — 24 au total pour
un run complet à 3 répétitions × 2 configs). Ne pas relancer en boucle pour "vérifier" un chiffre
surprenant : relire les logs bruts de ce script d'abord (chemin affiché en fin d'exécution).

Usage : python scripts/benchmark_speedup.py   (depuis la racine du repo)
"""

from __future__ import annotations

import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
JAR_PATH = REPO_ROOT / "agent" / "target" / "java-legacy-agent-1.0.0.jar"
DEMO_PROJECT = REPO_ROOT / "demo-project"
ENV_FILE = REPO_ROOT / ".env"

WORKER_CONFIGS = [1, 4]
RUNS_PER_CONFIG = 3


ANTHROPIC_ENV_VAR_NAME = "ANTHROPIC" + "_API_KEY"  # évite le littéral "KEY=" collé (faux positif gitleaks)


def load_anthropic_api_key() -> str:
    """Lit la clé Anthropic depuis .env sans jamais l'afficher."""
    if not ENV_FILE.exists():
        sys.exit(f"ERREUR : {ENV_FILE} introuvable — clé API Anthropic requise pour ce benchmark.")
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        name, sep, value = line.strip().partition("=")
        if sep and name.strip() == ANTHROPIC_ENV_VAR_NAME:
            value = value.strip()
            if value:
                return value
    sys.exit("ERREUR : clé API Anthropic absente ou vide dans .env — abandon avant tout appel payant.")


def run_once(workers: int, anthropic_key: str, run_index: int) -> tuple[float, str]:
    """Lance un run 'analyze' sur demo-project avec AGENT_WORKERS=<workers>.
    Retourne (duration_total_ms lu depuis RunMetrics, ligne de bannière backend LLM capturée).
    """
    handoff_dir = Path(tempfile.mkdtemp(prefix="benchmark-speedup-"))
    env = dict(os.environ)
    env["AGENT_WORKERS"] = str(workers)
    env["ALLOW_CLOUD_CODE_ANALYSIS"] = "true"
    env[ANTHROPIC_ENV_VAR_NAME] = anthropic_key

    # -Dstdout.encoding=UTF-8 : sans ça, la JVM écrit sur stdout dans l'encodage console natif
    # (souvent cp1252/cp850 sur Windows, pas UTF-8, malgré JEP 400) -- les caractères accentués
    # des bannières/logs Java deviennent alors des séquences d'octets invalides une fois relues
    # ici avec encoding="utf-8", errors="replace" ci-dessous, produisant des U+FFFD (REPLACEMENT
    # CHARACTER) que la console Python (cp1252 par défaut) ne peut ensuite pas réafficher --
    # c'est ce qui a fait planter la section traçabilité lors du premier run complet.
    cmd = ["java", "-Dstdout.encoding=UTF-8", "-jar", str(JAR_PATH),
           "analyze", str(DEMO_PROJECT), str(handoff_dir)]
    print(f"  [run {run_index}] AGENT_WORKERS={workers} — lancement...")

    try:
        result = subprocess.run(
            cmd, cwd=str(REPO_ROOT), env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=600,
        )
    finally:
        pass  # handoff_dir nettoyé après lecture des métriques, plus bas

    backend_line = next(
        (line for line in result.stdout.splitlines() if "LLM backend" in line), "(bannière backend introuvable)"
    )

    if result.returncode != 0:
        print(result.stdout[-3000:])
        print(result.stderr[-3000:])
        shutil.rmtree(handoff_dir, ignore_errors=True)
        sys.exit(f"ERREUR : run AGENT_WORKERS={workers} run {run_index} a échoué (code {result.returncode})."
                 " Arrêt — ne pas relancer en boucle sans avoir lu la sortie ci-dessus.")

    if "Anthropic Claude Haiku" not in backend_line:
        print(backend_line)
        shutil.rmtree(handoff_dir, ignore_errors=True)
        sys.exit("ERREUR : le backend actif n'est PAS le cloud Claude Haiku pour ce run "
                 "(ALLOW_CLOUD_CODE_ANALYSIS mal appliqué ?). Arrêt avant de fausser la mesure.")

    metrics_path = handoff_dir / "demo-project" / "metrics_demo-project.json"
    if not metrics_path.exists():
        shutil.rmtree(handoff_dir, ignore_errors=True)
        sys.exit(f"ERREUR : {metrics_path} absent — le run a peut-être échoué silencieusement.")

    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    duration_ms = float(metrics["duration_total_ms"])

    shutil.rmtree(handoff_dir, ignore_errors=True)
    print(f"  [run {run_index}] AGENT_WORKERS={workers} — {duration_ms:.0f} ms")
    return duration_ms, backend_line


def main() -> None:
    # Sans ceci, print() sur stdout redirigé utilise l'encodage console Windows par défaut
    # (cp1252), incapable d'afficher les U+FFFD produits par le décodage errors="replace" des
    # bannières Java côté run_once() -- provoquait un crash en toute fin de script.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if not JAR_PATH.exists():
        sys.exit(f"ERREUR : {JAR_PATH} introuvable — lancer 'mvn package' dans agent/ d'abord.")
    if not DEMO_PROJECT.exists():
        sys.exit(f"ERREUR : {DEMO_PROJECT} introuvable.")

    anthropic_key = load_anthropic_api_key()
    classes = sorted(p.stem for p in DEMO_PROJECT.rglob("*.java"))

    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    log_lines: list[str] = []

    def log(msg: str) -> None:
        print(msg)
        log_lines.append(msg)

    log("=" * 70)
    log("  BENCHMARK SPEEDUP — AGENT_WORKERS=1 vs AGENT_WORKERS=4")
    log("=" * 70)
    log(f"  Horodatage (UTC)         : {timestamp}")
    log("  ALLOW_CLOUD_CODE_ANALYSIS: true (Anthropic Claude Haiku, ce run uniquement)")
    log(f"  Classes envoyées (cloud) : {', '.join(classes)}")
    log(f"  Répétitions par config   : {RUNS_PER_CONFIG}")
    log("=" * 70)

    raw_times: dict[int, list[float]] = {}
    backend_confirmations: list[str] = []

    for workers in WORKER_CONFIGS:
        log(f"\n--- AGENT_WORKERS={workers} ---")
        times = []
        for i in range(1, RUNS_PER_CONFIG + 1):
            duration_ms, backend_line = run_once(workers, anthropic_key, i)
            times.append(duration_ms)
            backend_confirmations.append(f"AGENT_WORKERS={workers} run{i}: {backend_line.strip()}")
        raw_times[workers] = times

    log("\n" + "=" * 70)
    log("  TEMPS BRUTS (ms)")
    log("=" * 70)
    for workers in WORKER_CONFIGS:
        vals = raw_times[workers]
        log(f"  AGENT_WORKERS={workers} : {[f'{v:.0f}' for v in vals]}  (médiane={statistics.median(vals):.0f})")

    median_1 = statistics.median(raw_times[1])
    median_4 = statistics.median(raw_times[4])
    speedup = median_1 / median_4 if median_4 > 0 else float("inf")

    log("\n" + "=" * 70)
    log("  RÉSULTAT")
    log("=" * 70)
    log(f"  Médiane 1 worker  : {median_1:.0f} ms")
    log(f"  Médiane 4 workers : {median_4:.0f} ms")
    log(f"  Speedup           : ×{speedup:.2f}")
    log("=" * 70)

    log("\n--- Traçabilité ALLOW_CLOUD_CODE_ANALYSIS (bannière backend par run) ---")
    for line in backend_confirmations:
        log(f"  {line}")

    log_dir = REPO_ROOT / "logs"
    log_dir.mkdir(exist_ok=True)
    log_file = log_dir / f"benchmark_speedup_{timestamp.replace(':', '').replace('-', '')}.log"
    log_file.write_text("\n".join(log_lines) + "\n", encoding="utf-8")
    print(f"\nLog complet écrit : {log_file}")


if __name__ == "__main__":
    main()

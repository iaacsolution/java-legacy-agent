# Hook pre-commit face à une image Docker absente — constat réel (2026-10-16)

Unique `docker run` autorisé pour le test de bout en bout du Lot 3 :

    docker run --rm -v "<dépôt>:/project" -e AGENT_STATE_DIR=/project/.java-legacy-agent \
      --entrypoint java java-legacy-agent-java-agent \
      -jar /project/agent/target/java-legacy-agent-1.0.0.jar impact /project/agent/src RunMetrics track --json

Résultat : code 125, l'image `java-legacy-agent-java-agent` n'existe pas localement
(`docker_run_sortie.log`). Aucun conteneur n'a tourné, aucun fichier n'a été écrit.

Cette sortie réelle, rejouée sans Docker (faux exécutable qui la renvoie) :
- **ancienne logique du hook** : aucune sévérité trouvée, rien d'affiché, commit accepté
  (laisser-passer silencieux) ;
- **nouvelle logique** (`hooks/impact-lib.sh`) : bloqué au pré-vol, « image absente
  localement » (code 12) ; à l'analyse, « a échoué (code 125) » (code 13).

## Bout en bout, refait sur l'image existante (2026-10-16)

Aucun service compose ne produit `java-legacy-agent-java-agent` : l'ancien service
« java-agent » a été scindé en `java-analyzer` / `java-reporter`. Même `docker run`, sur
l'image existante `java-legacy-agent-java-analyzer` (aucune construction) :

    docker run --rm -v "<dépôt>:/project" -e AGENT_STATE_DIR=/project/.java-legacy-agent       --entrypoint java java-legacy-agent-java-analyzer       -jar /project/agent/target/java-legacy-agent-1.0.0.jar impact /project/agent/src RunMetrics track --json

Résultat (`docker_run_java_analyzer.log`), code 1 = changement cassant détecté :
- `RunMetrics.track` : 10 appelants, sévérité HIGH ;
- cache écrit dans `/project/.java-legacy-agent/callgraph/<sha>.json` (clés relatives au projet) ;
- `impact-roi.csv` écrit dans `/project/.java-legacy-agent/` ;
- les deux anciens fichiers du grand-parent (`/project/.callgraph-cache.json`,
  `/project/impact-roi.csv`) signalés en WARN et **intacts** (SHA-1 identiques avant/après).

Le défaut du hook est désormais `java-legacy-agent-java-analyzer`, surchargeable par
`AGENT_IMAGE`.

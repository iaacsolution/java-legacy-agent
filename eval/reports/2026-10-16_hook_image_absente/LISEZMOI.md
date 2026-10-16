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

Non fait : le chemin hook → conteneur → `AGENT_STATE_DIR` n'a pas pu être vérifié de bout
en bout, faute d'image. Couvert sans conteneur par CallGraphCacheCorrectnessTest
(emplacement, refus, orphelins) et hooks/test-impact-lib.sh (transmission de la variable).

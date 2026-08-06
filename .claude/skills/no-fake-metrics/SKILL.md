---
name: no-fake-metrics
description: À utiliser dès qu'un chiffre de performance (F1, speedup, latence) est mentionné dans un rapport, un CV, un README ou une slide. Vérifie que le chiffre correspond à une mesure réelle avant de l'écrire.
---

Avant d'écrire tout chiffre de performance :
1. Cherche s'il existe un rapport d'éval récent (`eval_reports/*.json`, trié par date)
2. Si le chiffre demandé ne correspond à aucun rapport mesuré, refuse de l'écrire
   tel quel et propose de relancer l'éval d'abord
3. Ne jamais interpoler ou arrondir "à la hausse" un chiffre entre deux runs
4. Si aucune baseline n'existe encore, le dire explicitement plutôt que de proposer
   un seuil par défaut non mesuré — la baseline est fixée par le premier rapport
   golden-dataset-evaluator réel, jamais par une valeur devinée.

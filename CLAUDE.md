# Pipeline de modernisation Java legacy

## Architecture (ne jamais dévier de ceci)
7 agents : FileScannerAgent, AstParserAgent, CallGraphAgent, DependencyMapperAgent,
JavaDocumentationAgent, MigrationPlannerAgent, AgentEvaluator
+ RefactoringAdvisor, LegacyMigrationOrchestrator
Stack : JavaParser, LangGraph, Claude API / Qwen on-prem
Observabilité : Prometheus / Grafana / Phoenix OTEL

## Commandes
- Test complet : `pytest tests/golden_dataset/ -v`
- Éval F1 : `python scripts/eval_golden_dataset.py --report`
- Lint : `ruff check . --fix`
- Run pipeline (4 workers) : `AGENT_WORKERS=4 python run_pipeline.py`

## Règles non négociables
- Ne jamais annoncer un chiffre non mesuré. F1 de référence = 0.757, speedup = ×2.50
  (médiane sur 3 runs). Si un run donne un chiffre différent, c'est CE chiffre qui compte,
  pas celui du README.
- JavaParser n'est pas thread-safe avec AGENT_WORKERS>1 sans le lock déjà en place dans
  `parsing/safe_parser.py` — ne jamais retirer ce lock, même si un agent "optimise".
- Toute modification touchant à AstParserAgent ou CallGraphAgent doit repasser le
  Golden Dataset avant merge.

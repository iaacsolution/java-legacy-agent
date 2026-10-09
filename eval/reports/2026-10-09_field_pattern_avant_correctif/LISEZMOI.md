# Composante déterministe AVANT correction FIELD_PATTERN (mesure du 2026-10-09)

Procédure (sans LLM) :

    git worktree add --detach <tmp> d5e7f51
    git -C <tmp> checkout 9b98b6b -- agent/src/main/java/com/audensiel/legacy/agent/DependencyMapperAgent.java
    mvn -f <tmp>/agent/pom.xml package -DskipTests
    cd <tmp> && java -cp agent/target/java-legacy-agent-1.0.0.jar com.audensiel.legacy.agent.EvalMain <dataset> --deterministic-only --json <sortie>

Seul DependencyMapperAgent est restauré à 9b98b6b (avant 901ba03) ; tout le reste est d5e7f51.
Sert de preuve à la note « deterministe » de eval/baseline.json.

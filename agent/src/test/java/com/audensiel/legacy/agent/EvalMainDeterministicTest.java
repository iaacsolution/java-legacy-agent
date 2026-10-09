package com.audensiel.legacy.agent;

import com.audensiel.legacy.agent.observability.SpanRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verrou de non-régression de la composante déterministe du F1 (DependencyMapper, regex,
 * sans LLM) sur les 3 cas historiques.
 *
 * <p>Valeurs attendues : série « apres_correction_B » de {@code results/f1_ollama.json}
 * (commit 901ba03), identiques au bit près sur les 3 passages mesurés. La comparaison porte
 * sur les entiers TP/FP/FN, pas sur le F1 arrondi : aucune tolérance, une seule unité de
 * différence est une régression (ou une amélioration à reporter dans eval/baseline.json).
 */
class EvalMainDeterministicTest {

    // Le répertoire de travail de surefire est agent/ ; les datasets sont à la racine du dépôt.
    private static final Path DATASET_3_CAS = Path.of("..", "golden_dataset_3cases.json");

    @Test
    @DisplayName("mode déterministe : TP/FP/FN exacts par cas, aucune composante LLM")
    void composanteDeterministeExacte() throws Exception {
        List<EvalMain.GoldenCase> cases = EvalMain.loadDataset(DATASET_3_CAS);

        EvalMain.EvalReport report = EvalMain.evaluate(
                cases, null, SpanRecorder.disabled(), "golden_dataset_3cases.json");

        Map<String, AgentEvaluator.EvalResult> parCas = report.cases().stream()
                .collect(Collectors.toMap(EvalMain.CaseResult::name, EvalMain.CaseResult::dependencyMapper));

        assertComptes(parCas.get("cas1"), 5, 2, 2);
        assertComptes(parCas.get("cas2"), 7, 0, 1);
        assertComptes(parCas.get("cas3"), 1, 1, 1);

        assertNull(report.global(), "le F1 global n'existe pas sans les composantes LLM");
        report.cases().forEach(c -> {
            assertNull(c.risques(), c.name() + " : composante risques calculée sans LLM");
            assertNull(c.responsabilites(), c.name() + " : composante responsabilités calculée sans LLM");
        });
    }

    private static void assertComptes(AgentEvaluator.EvalResult r, int tp, int fp, int fn) {
        assertNotNull(r);
        assertAll(
                () -> assertEquals(tp, r.tp(), "TP"),
                () -> assertEquals(fp, r.fp(), "FP"),
                () -> assertEquals(fn, r.fn(), "FN"));
    }
}

package com.audensiel.legacy.agent;

import com.audensiel.legacy.agent.governance.CapturingChatModel;
import com.audensiel.legacy.agent.governance.Redactor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preuve que la redaction ne biaise pas l'évaluation : sur les 5 cas du golden dataset, le
 * payload envoyé au modèle est IDENTIQUE avec et sans redaction, et aucune détection n'a
 * lieu. Tout écart de F1 global après le Lot 2 relève donc de la dérive du LLM, pas de la
 * redaction.
 *
 * <p>Corollaire assumé (limite documentée) : ce dataset ne mesure pas l'effet de la
 * redaction sur l'analyse d'un code qui contient des secrets.
 */
class GoldenDatasetNoOpTest {

    // Le répertoire de travail de surefire est agent/ ; les datasets sont à la racine du dépôt.
    private static final Path DATASET = Path.of("..", "golden_dataset.json");

    @Test
    @DisplayName("golden dataset : payload identique avec et sans redaction, zéro détection")
    void redactionNeutre() throws Exception {
        List<EvalMain.GoldenCase> cases = EvalMain.loadDataset(DATASET);
        assertEquals(5, cases.size());

        Redactor redactor = Redactor.withRandomKey();
        for (EvalMain.GoldenCase gc : cases) {
            assertEquals(0, redactor.redact(gc.javaCode()).total(), gc.name() + " : détection inattendue");

            // Même appel qu'EvalMain (analyzeJavaClass), avec et sans le décorateur de la factory.
            CapturingChatModel brut = new CapturingChatModel();
            CapturingChatModel filtre = new CapturingChatModel();
            ChatLanguageModel avec = LlmModelFactory.wrap(filtre, Map.<String, String>of()::get);

            new JavaDocumentationAgent(brut, brut, Map.<String, String>of()::get)
                    .analyzeJavaClass(gc.javaCode(), "", false, false);
            new JavaDocumentationAgent(avec, avec, Map.<String, String>of()::get)
                    .analyzeJavaClass(gc.javaCode(), "", false, false);

            List<ChatMessage> sansRedaction = brut.calls().get(0);
            List<ChatMessage> avecRedaction = filtre.calls().get(0);
            assertEquals(sansRedaction.size(), avecRedaction.size(), gc.name());
            for (int i = 0; i < sansRedaction.size(); i++) {
                assertEquals(sansRedaction.get(i).type(), avecRedaction.get(i).type(), gc.name());
                assertEquals(CapturingChatModel.textOf(sansRedaction.get(i)),
                        CapturingChatModel.textOf(avecRedaction.get(i)),
                        gc.name() + " : la redaction a modifié le payload");
            }
        }
    }
}

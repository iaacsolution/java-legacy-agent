package com.audensiel.legacy.agent.governance;

import java.util.Map;

/**
 * Levée par {@link RedactingChatModel} en politique {@code block} quand un message contient
 * un secret ou une PII : l'appel au LLM n'a pas lieu. Le message ne porte que les types et
 * les nombres de détections, jamais une valeur.
 */
public final class RedactionBlockedException extends IllegalStateException {

    private final Map<Redactor.Type, Integer> counts;

    public RedactionBlockedException(Map<Redactor.Type, Integer> counts) {
        super("Envoi au LLM bloqué (LLM_REDACTION_POLICY=block) : détections " + counts
                + " — aucune donnée n'a été transmise");
        this.counts = Map.copyOf(counts);
    }

    public Map<Redactor.Type, Integer> counts() {
        return counts;
    }
}

package com.audensiel.legacy.agent;

/**
 * Pattern Commande — encapsule une action d'analyse avec sa description.
 * Permet à l'orchestrateur de retenter la même commande sans dupliquer la logique.
 */
public interface AnalysisCommand<T> {

    T execute() throws Exception;

    String describe();

    static <T> AnalysisCommand<T> of(String description, RunMetrics.StepSupplier<T> supplier) {
        return new AnalysisCommand<T>() {
            @Override public T execute() throws Exception { return supplier.get(); }
            @Override public String describe()            { return description; }
        };
    }
}

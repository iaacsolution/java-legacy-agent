package com.audensiel.legacy.agent.observability;

/**
 * Destination des spans terminés.
 *
 * <p>Deux implémentations : {@link SpanWriter} (file bornée + COPY vers TimescaleDB)
 * et un puits en mémoire dans les tests, qui permet de vérifier le parentage entre
 * threads sans base de données.
 *
 * <p>Contrat impératif : {@link #accept} ne bloque jamais et ne lève jamais
 * d'exception. Le pipeline d'analyse ne doit jamais échouer à cause de
 * l'observabilité.
 */
public interface SpanSink extends AutoCloseable {

    /** Dépose un span terminé. Non bloquant, ne lève rien. Peut perdre le span (compté). */
    void accept(SpanRecord record);

    /** Vide ce qui peut l'être, puis relâche les ressources. Ne lève jamais. */
    @Override
    void close();
}

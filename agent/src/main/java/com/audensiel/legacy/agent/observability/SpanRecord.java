package com.audensiel.legacy.agent.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne prête à écrire dans {@code agent_span}. Immuable : une fois construite
 * par {@code Span.close()}, elle traverse la file vers le thread d'écriture sans
 * publication d'état mutable.
 *
 * <p>{@code time} doit toujours provenir du même {@link SpanId#next()} que
 * {@code spanId}, sinon la contrainte CHECK de la table rejette la ligne.
 *
 * <p>{@code attributesJson} ne contient <strong>jamais</strong> de prompt, de réponse
 * de modèle, de code source ni de secret — uniquement des métadonnées structurelles.
 */
public record SpanRecord(
        Instant time,
        UUID spanId,
        UUID parentId,
        UUID runId,
        String sessionId,
        String project,
        String agent,
        String spanKind,
        String name,
        String status,
        double durationMs,
        int tokensIn,
        int tokensOut,
        BigDecimal costUsd,
        String attributesJson
) {}

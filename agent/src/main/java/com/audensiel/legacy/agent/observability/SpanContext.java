package com.audensiel.legacy.agent.observability;

import java.util.UUID;

/**
 * Poignée immuable désignant un span parent.
 *
 * <p>Délibérément une valeur et non un objet vivant : c'est ce qui permet de la
 * capturer dans une variable {@code final} avant {@code pool.submit(...)} et de
 * la transmettre <strong>explicitement</strong> au thread worker. Avec
 * {@code AGENT_WORKERS=4}, un {@link ThreadLocal} seul ne propage rien à travers
 * l'executor — voir {@code SpanRecorder.adopt}.
 */
public record SpanContext(UUID runId, UUID spanId) {}

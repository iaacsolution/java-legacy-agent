package com.audensiel.legacy.agent.observability;

import java.math.BigDecimal;

/**
 * Un span en cours. S'utilise en try-with-resources :
 *
 * <pre>{@code
 * try (Span s = recorder.span("agent", "AstParserAgent", "ast")) {
 *     s.attribute("class", className);
 * }
 * }</pre>
 *
 * <p><strong>Enregistrement des échecs.</strong> Java ne donne pas à {@code close()}
 * accès à l'exception qui sort d'un try-with-resources. Pour que les échecs soient
 * réellement enregistrés <em>et</em> relancés, le pipeline utilise
 * {@code SpanRecorder.call(...)}, qui enveloppe l'appel dans un try/catch/finally.
 * Ce bloc try-with-resources reste valable pour du code sans échec attendu, où
 * l'appelant pose lui-même le statut.
 *
 * <p><strong>Règle de minimisation.</strong> {@link #attribute} ne doit jamais recevoir
 * de prompt, de réponse de modèle, de code source ni de secret — uniquement des
 * métadonnées structurelles (nom de classe, étape, modèle, compteurs, type
 * d'exception). Les valeurs sont tronquées par sécurité, ce qui limite les dégâts
 * d'un écart mais ne dispense pas de la règle.
 */
public interface Span extends AutoCloseable {

    Span tokensIn(int tokens);

    Span tokensOut(int tokens);

    Span costUsd(BigDecimal cost);

    /** "ok", "error" ou "timeout". */
    Span status(String status);

    /** Métadonnée structurelle uniquement — jamais de contenu métier. */
    Span attribute(String key, Object value);

    /** Contexte à transmettre explicitement à une tâche soumise à un executor. */
    SpanContext context();

    /** Clôt le span et le dépose dans le puits. Ne lève jamais. */
    @Override
    void close();
}

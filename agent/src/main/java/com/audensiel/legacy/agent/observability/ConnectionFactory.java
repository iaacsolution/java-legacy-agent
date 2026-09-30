package com.audensiel.legacy.agent.observability;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Fournit une connexion neuve à la base du flight recorder.
 *
 * <p>Cette indirection existe pour que {@link SpanWriter} ne détienne aucun identifiant :
 * il demande une connexion, il ne sait pas comment elle est obtenue. Les informations de
 * connexion restent confinées à {@link SpanRecorder#start()}, qui les lit dans
 * l'environnement.
 *
 * <p>Elle rend aussi le writer testable sans base, en substituant une fabrique qui échoue
 * ou qui rend une connexion factice.
 */
@FunctionalInterface
public interface ConnectionFactory {

    /** Ouvre une connexion neuve. Appelée à nouveau après chaque échec, pour reconnecter. */
    Connection open() throws SQLException;
}

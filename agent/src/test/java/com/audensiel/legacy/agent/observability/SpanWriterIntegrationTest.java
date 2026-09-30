package com.audensiel.legacy.agent.observability;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aller-retour réel contre la base du flight recorder.
 *
 * <p><strong>Exclu du build par défaut</strong> ({@code @Tag("integration")}). À la demande :
 * <pre>mvn -f agent/pom.xml test -Dgroups=integration -DexcludedGroups=</pre>
 *
 * <p>Ce que les tests unitaires ne peuvent pas prouver et que celui-ci vérifie :
 * <ul>
 *   <li>l'échappement CSV survit au vrai {@code COPY} — un décalage de colonnes ne se
 *       manifeste que côté PostgreSQL, jamais côté Java ;</li>
 *   <li>la contrainte {@code CHECK (uuid_extract_timestamp(span_id) = time)} est bien
 *       satisfaite par l'UUID v7 généré côté Java.</li>
 * </ul>
 *
 * <p>Base cible : <strong>legacyrec</strong>. Jamais {@code flightrec}, qui porte les données
 * du cours TimescaleDB — les deux bases vivent dans le même conteneur.
 *
 * <p>Base injoignable ⇒ test ignoré (assumption), jamais en échec : le recorder est
 * désactivé par défaut, donc l'absence de base est le cas nominal de ce dépôt.
 *
 * <p>Note PgJDBC : l'opérateur JSONB {@code ?} entre en collision avec les paramètres
 * {@code ?} de JDBC. Les requêtes ci-dessous utilisent donc {@code jsonb_exists(...)}
 * plutôt que {@code attributes ? 'clé'}.
 */
@Tag("integration")
class SpanWriterIntegrationTest {

    private static final String URL =
            envOr("FLIGHTREC_URL", "jdbc:postgresql://localhost:5432/legacyrec");
    private static final String USER = envOr("FLIGHTREC_USER", "postgres");

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String credentials() {
        String v = System.getenv("FLIGHTREC_PASSWORD");
        if (v == null || v.isBlank()) v = System.getenv("PGPASSWORD");
        return v == null ? "" : v;
    }

    private static Connection connect() throws java.sql.SQLException {
        return DriverManager.getConnection(URL, USER, credentials());
    }

    /** Ignore le test si la base n'est pas joignable ou si le schéma n'est pas appliqué. */
    private static void requiresDatabase() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM agent_span LIMIT 0");
        } catch (Exception e) {
            Assumptions.abort("Base " + URL + " injoignable ou schéma absent — test ignoré : " + e.getMessage());
        }
    }

    @Test
    @DisplayName("aller-retour réel : guillemets, virgules et retours à la ligne restent intacts")
    void allerRetourAvecCaracteresPiegeux() throws Exception {
        requiresDatabase();

        UUID runId = SpanId.next().id();
        // Exactement les caractères qui cassent un CSV mal échappé.
        String classePiegeuse = "Client, \"Bean\"\nsur deux lignes\tavec tabulation";

        try (SpanWriter writer = new SpanWriter(SpanWriterIntegrationTest::connect, 1000)) {
            SpanRecorder recorder = SpanRecorder.forSink(writer, runId);

            try (Span run = recorder.span("run", "LegacyMigrationOrchestrator", "analyze")) {
                run.attribute("project", "test-integration");
                try (Span agent = recorder.span("agent", "AstParserAgent", "ast")) {
                    agent.attribute("class", classePiegeuse);
                    try (Span llm = recorder.span("llm", "JavaDocumentationAgent", "chat")) {
                        llm.tokensIn(120).tokensOut(340).costUsd(new BigDecimal("0.001820"));
                    }
                }
            }
            writer.awaitDrain(5_000);
        }

        try {
            assertEquals(3, countFor(runId), "les 3 spans doivent être arrivés en base");

            // L'attribut piégeux se relit à l'identique : aucune colonne n'a été décalée.
            assertEquals(classePiegeuse, scalar(runId,
                    "SELECT attributes ->> 'class' FROM agent_span WHERE run_id = ? AND span_kind = 'agent'"),
                    "guillemets, virgule et retour à la ligne doivent traverser le COPY intacts");

            // Le parentage est bien reconstruit côté base.
            assertEquals("AstParserAgent", scalar(runId,
                    "SELECT p.agent FROM agent_span c JOIN agent_span p ON c.parent_id = p.span_id "
                  + "WHERE c.run_id = ? AND c.span_kind = 'llm'"),
                    "le span llm doit avoir le span d'agent pour parent");

            // Jetons et coût dans les bonnes colonnes.
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT tokens_in, tokens_out, cost_usd FROM agent_span WHERE run_id = ? AND span_kind = 'llm'")) {
                ps.setObject(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(120, rs.getInt(1));
                    assertEquals(340, rs.getInt(2));
                    assertEquals(0, new BigDecimal("0.001820").compareTo(rs.getBigDecimal(3)),
                            "cost_usd doit arriver intact dans NUMERIC(12,6)");
                }
            }
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("la contrainte CHECK uuid_extract_timestamp(span_id) = time est satisfaite")
    void contrainteCheckSatisfaite() throws Exception {
        requiresDatabase();

        UUID runId = SpanId.next().id();
        // Volume suffisant pour franchir de nombreuses frontières de milliseconde : c'est
        // précisément là qu'une seconde lecture d'horloge ferait rejeter la ligne.
        int total = 2_000;

        try (SpanWriter writer = new SpanWriter(SpanWriterIntegrationTest::connect, 5_000)) {
            SpanRecorder recorder = SpanRecorder.forSink(writer, runId);
            for (int i = 0; i < total; i++) {
                recorder.span("agent", "AstParserAgent", "ast").close();
            }
            writer.awaitDrain(20_000);
        }

        try {
            assertEquals(total, countFor(runId),
                    "aucune ligne ne doit avoir été rejetée par la contrainte CHECK");

            // Contrôle explicite côté serveur, avec la vraie fonction PostgreSQL 18.
            assertEquals("0", scalar(runId,
                    "SELECT count(*)::text FROM agent_span WHERE run_id = ? "
                  + "AND uuid_extract_timestamp(span_id) <> time"),
                    "uuid_extract_timestamp(span_id) doit toujours égaler time");
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("la règle de minimisation tient jusqu'en base : le type oui, le message jamais")
    void minimisationVerifieeEnBase() throws Exception {
        requiresDatabase();

        UUID runId = SpanId.next().id();

        try (SpanWriter writer = new SpanWriter(SpanWriterIntegrationTest::connect, 100)) {
            SpanRecorder recorder = SpanRecorder.forSink(writer, runId);
            assertThrows(IllegalStateException.class,
                    () -> recorder.call("agent", "AstParserAgent", "ast", () -> {
                        throw new IllegalStateException("MARQUEUR-CONFIDENTIEL-9A2B");
                    }));
            writer.awaitDrain(5_000);
        }

        try {
            // Le type est présent — sans cette assertion, la suivante passerait aussi si
            // rien n'avait été écrit du tout.
            assertEquals("java.lang.IllegalStateException", scalar(runId,
                    "SELECT attributes ->> 'exception.type' FROM agent_span WHERE run_id = ?"));
            assertEquals("error", scalar(runId,
                    "SELECT status FROM agent_span WHERE run_id = ?"));

            // Le message, lui, ne doit exister sous aucune forme.
            assertEquals("0", scalar(runId,
                    "SELECT count(*)::text FROM agent_span WHERE run_id = ? "
                  + "AND jsonb_exists(attributes, 'exception.message')"),
                    "exception.message ne doit jamais atteindre la base");
            assertEquals("0", scalar(runId,
                    "SELECT count(*)::text FROM agent_span WHERE run_id = ? "
                  + "AND attributes::text LIKE '%MARQUEUR-CONFIDENTIEL%'"),
                    "aucun fragment du message ne doit atteindre la base");
        } finally {
            cleanup(runId);
        }
    }

    // ── utilitaires ─────────────────────────────────────────────────────────

    private static String scalar(UUID runId, String sql) throws Exception {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "aucune ligne retournée par : " + sql);
                return rs.getString(1);
            }
        }
    }

    private static long countFor(UUID runId) throws Exception {
        return Long.parseLong(scalar(runId, "SELECT count(*)::text FROM agent_span WHERE run_id = ?"));
    }

    /** Le test ne laisse jamais ses lignes derrière lui dans une base partagée. */
    private static void cleanup(UUID runId) throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM agent_span WHERE run_id = ?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        }
    }
}

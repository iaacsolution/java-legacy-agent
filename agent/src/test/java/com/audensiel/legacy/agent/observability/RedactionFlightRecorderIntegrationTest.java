package com.audensiel.legacy.agent.observability;

import com.audensiel.legacy.agent.governance.CapturingChatModel;
import com.audensiel.legacy.agent.governance.FakeSecrets;
import com.audensiel.legacy.agent.governance.RedactingChatModel;
import com.audensiel.legacy.agent.governance.Redactor;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aller-retour réel : les comptages de redaction arrivent bien dans {@code agent_span} de
 * la base <b>legacyrec</b> (jamais {@code flightrec}), sous un span {@code kind=redaction}.
 *
 * <p>Vérifie ce que le test unitaire ne peut pas : aucune contrainte de la base ne refuse
 * ce {@code span_kind} (un span refusé serait compté perdu, sans erreur visible), et les
 * attributs se relisent en JSONB.
 *
 * <p>Exclu par défaut ({@code @Tag("integration")}). À la demande :
 * <pre>mvn -f agent/pom.xml test -Dgroups=integration -DexcludedGroups=</pre>
 * Base injoignable ⇒ test ignoré, jamais en échec.
 */
@Tag("integration")
class RedactionFlightRecorderIntegrationTest {

    private static final String URL = envOr("FLIGHTREC_URL", "jdbc:postgresql://localhost:5432/legacyrec");
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

    private static void requiresDatabase() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM agent_span LIMIT 0");
        } catch (Exception e) {
            Assumptions.abort("Base " + URL + " injoignable ou schéma absent — test ignoré : " + e.getMessage());
        }
    }

    @Test
    @DisplayName("span redaction écrit en base : attributs numériques relus en JSONB, aucune valeur")
    void comptagesEnBase() throws Exception {
        requiresDatabase();
        UUID runId = UUID.randomUUID();

        try (SpanWriter writer = new SpanWriter(RedactionFlightRecorderIntegrationTest::connect, 1000)) {
            SpanRecorder recorder = SpanRecorder.forSink(writer, runId);
            // Constructeur public : le décorateur lit l'enregistreur ACTIF, comme en production.
            SpanRecorder.install(recorder);
            try {
                RedactingChatModel model = new RedactingChatModel(new CapturingChatModel(),
                        Redactor.withKey("cle-de-test-fixe".getBytes(StandardCharsets.UTF_8)),
                        RedactingChatModel.Policy.REDACT);

                try (Span agent = recorder.span("agent", "JavaDocumentationAgent", "analyze")) {
                    agent.attribute("class", "BillingGatewayBean");
                    model.generate(List.of(UserMessage.from(FakeSecrets.legacyClass())));
                }
                writer.awaitDrain(5_000);
            } finally {
                SpanRecorder.install(SpanRecorder.disabled());
            }
        }

        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT r.attributes ->> 'redaction.PASSWORD', r.attributes ->> 'redaction.total', "
                   + "r.attributes::text, p.attributes ->> 'class' "
                   + "FROM agent_span r JOIN agent_span p ON r.parent_id = p.span_id "
                   + "WHERE r.run_id = ? AND r.span_kind = 'redaction'")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "le span redaction doit être arrivé en base (pas compté perdu)");
                assertTrue(Integer.parseInt(rs.getString(1)) >= 1);
                assertTrue(Integer.parseInt(rs.getString(2)) >= 1);
                String attrs = rs.getString(3);
                for (String v : FakeSecrets.values()) {
                    assertFalse(attrs.contains(v), "jamais une valeur en base");
                }
                assertEquals("BillingGatewayBean", rs.getString(4), "attribution au fichier via le span parent");
            }
        } finally {
            try (Connection c = connect();
                 PreparedStatement del = c.prepareStatement("DELETE FROM agent_span WHERE run_id = ?")) {
                del.setObject(1, runId);
                del.executeUpdate();
            }
        }
    }
}

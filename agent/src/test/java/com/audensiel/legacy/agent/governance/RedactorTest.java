package com.audensiel.legacy.agent.governance;

import com.audensiel.legacy.agent.governance.Redactor.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.audensiel.legacy.agent.governance.FakeSecrets.*;
import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {

    // Clé fixe : les marqueurs deviennent exacts et vérifiables.
    private static final byte[] KEY = "cle-de-test-fixe-NON-SECRETE-0123456789".getBytes(StandardCharsets.UTF_8);
    private final Redactor redactor = Redactor.withKey(KEY);

    /** Marqueur attendu, recalculé indépendamment du code testé. */
    private static String expectedMarker(Type type, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        byte[] d = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return "<REDACTED:" + type + ":" + HexFormat.of().formatHex(d, 0, 4) + ">";
    }

    private void assertRedacted(String input, Type type, String value, String expectedOutput) throws Exception {
        Redactor.Result r = redactor.redact(input);
        assertEquals(expectedOutput.replace("§", expectedMarker(type, value)), r.text());
        assertEquals(Map.of(type, 1), r.counts());
    }

    // ── Marqueurs exacts, un type à la fois ─────────────────────────────────

    @Test
    @DisplayName("mot de passe — littéral affecté, guillemets conservés")
    void password() throws Exception {
        assertRedacted(LINE_PW, Type.PASSWORD, PW_VALUE, "String dbPass" + "word = \"§\";");
        assertRedacted(LINE_SETTER, Type.PASSWORD, PW_VALUE, "ds.setPass" + "word(\"§\");");
    }

    @Test
    @DisplayName("jeton et clé d'API — affectation d'un littéral")
    void tokenEtCle() throws Exception {
        assertRedacted(LINE_TK, Type.SECRET_TOKEN, TK_VALUE, "String authTok" + "en = \"§\";");
        assertRedacted(LINE_AK, Type.API_KEY, AK_VALUE, "String api" + "Key = \"§\";");
    }

    @Test
    @DisplayName("clé d'API — préfixes connus (Anthropic, AWS, GitHub)")
    void prefixes() throws Exception {
        for (String k : List.of(ANTHROPIC, AWS, GITHUB)) {
            assertRedacted("k = \"" + k + "\";", Type.API_KEY, k, "k = \"§\";");
        }
    }

    @Test
    @DisplayName("PRIVATE_KEY : le bloc BEGIN … END est remplacé en entier")
    void clePrivee() throws Exception {
        assertRedacted("avant\n" + PRIVATE_KEY + "\naprès", Type.PRIVATE_KEY, PRIVATE_KEY, "avant\n§\naprès");
    }

    @Test
    @DisplayName("EMAIL et INTERNAL_IP")
    void emailEtIp() throws Exception {
        assertRedacted("/** @author " + EMAIL + " */", Type.EMAIL, EMAIL, "/** @author § */");
        assertRedacted("host = \"" + IP_A + "\";", Type.INTERNAL_IP, IP_A, "host = \"§\";");
        assertRedacted("relay " + IP_B + ".", Type.INTERNAL_IP, IP_B, "relay §.");
    }

    @Test
    @DisplayName("e-mail suivi d'un suffixe (« …@example.com-backup ») : l'adresse est filtrée")
    void emailSuiviDUnSuffixe() throws Exception {
        // Régression relevée en relecture : un lookahead final faisait rater ce cas (fuite).
        assertRedacted(EMAIL + "-backup", Type.EMAIL, EMAIL, "§-backup");
    }

    // ── JDBC et chevauchement de règles ─────────────────────────────────────

    @Test
    @DisplayName("chevauchement : jdbc:mysql://admin:FAKE@db.example.com — un seul JDBC_CREDENTIALS, aucun EMAIL")
    void chevauchementJdbcEmail() throws Exception {
        Redactor.Result r = redactor.redact(JDBC_MYSQL);

        assertEquals(Map.of(Type.JDBC_CREDENTIALS, 1), r.counts(),
                "un seul remplacement, et la règle EMAIL ne doit pas toucher « FAKE@db.example.com »");
        assertEquals("jdbc:mysql://" + expectedMarker(Type.JDBC_CREDENTIALS, MYSQL_USERINFO) + "@db.example.com",
                r.text());
        // Structure : schéma intact, un seul '@', hôte intact.
        assertTrue(Pattern.matches("jdbc:mysql://[^@/]+@db\\.example\\.com", r.text()), r.text());
        assertEquals(1, r.text().chars().filter(c -> c == '@').count());
    }

    @Test
    @DisplayName("JDBC : chaque paramètre user/password d'une URL, et la forme Oracle user/pass@")
    void jdbcParametresEtOracle() throws Exception {
        Redactor.Result r = redactor.redact(JDBC_PARAMS);
        assertEquals(Map.of(Type.JDBC_CREDENTIALS, 2), r.counts(), "user= et le mot de passe : deux paramètres");
        assertEquals("jdbc:sqlserver://db.example.com;user=" + expectedMarker(Type.JDBC_CREDENTIALS, "sa")
                + ";pass" + "word=" + expectedMarker(Type.JDBC_CREDENTIALS, JDBC_PW), r.text());

        assertRedacted(JDBC_ORACLE, Type.JDBC_CREDENTIALS, ORACLE_USERINFO,
                "jdbc:oracle:thin:§@db.example.com:1521:ORCL");
    }

    @Test
    @DisplayName("JDBC : paramètre de mot de passe après user:pass@ — les deux sont filtrés")
    void jdbcUserinfoEtParametre() {
        Redactor.Result r = redactor.redact(JDBC_MYSQL + "/app?pass" + "word=" + JDBC_PW);
        assertEquals(Map.of(Type.JDBC_CREDENTIALS, 2), r.counts());
        assertFalse(r.text().contains(JDBC_PW));
        assertFalse(r.text().contains(MYSQL_USERINFO));
    }

    // ── Stabilité des marqueurs ─────────────────────────────────────────────

    @Test
    @DisplayName("même secret → même marqueur ; secrets différents → marqueurs différents")
    void stabilite() {
        String a1 = redactor.redact(LINE_PW).text();
        String a2 = Redactor.withKey(KEY).redact(LINE_PW).text();
        assertEquals(a1, a2, "même clé, même secret, même marqueur — d'un appel ou d'une instance à l'autre");

        String b = redactor.redact(LINE_PW.replace(PW_VALUE, PW_VALUE + "-autre")).text();
        assertNotEquals(a1, b);

        String c = Redactor.withRandomKey().redact(LINE_PW).text();
        assertNotEquals(a1, c, "clé différente : marqueur différent (la clé est bien utilisée)");
    }

    @Test
    @DisplayName("idempotence : filtrer un texte déjà filtré ne change rien")
    void idempotence() {
        Redactor.Result once = redactor.redact(FakeSecrets.legacyClass());
        Redactor.Result twice = redactor.redact(once.text());
        assertEquals(once.text(), twice.text());
        assertEquals(0, twice.total(), "aucune règle ne doit capturer un marqueur");
    }

    @Test
    @DisplayName("classe legacy complète : aucune valeur de fixture ne subsiste")
    void classeComplete() {
        Redactor.Result r = redactor.redact(FakeSecrets.legacyClass());
        for (String v : FakeSecrets.values()) {
            assertFalse(r.text().contains(v), "valeur non filtrée : type attendu introuvable");
        }
    }

    // ── Négatifs : rien ne doit bouger ──────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "disk-cache-0123456789abcdef",
            "task-0123456789abcdefghijklmnop",
            "risk-0123456789abcdefghijklmnop",
            "String url = \"jdbc:mysql://db.example.com:3306/app\";",
            "String url = \"jdbc:oracle:thin:@db.example.com:1521:ORCL\";",
            "version 10.2.0.15.3 et 1.10.0.1",
            "le token expire après 10 minutes",
            "\"SELECT CLI_ID FROM T_CLIENT WHERE CLI_CODE = ?\"",
            "@Stateless @Override {@link Foo#bar}",
            "String x = \"${DB_PASS" + "WORD}\";",
    })
    @DisplayName("négatifs : texte inchangé, zéro détection")
    void negatifs(String input) {
        Redactor.Result r = redactor.redact(input);
        assertEquals(input, r.text());
        assertEquals(0, r.total());
    }

    @Test
    @DisplayName("négatifs : affectation sans littéral, UUID et SHA-256 isolés")
    void negatifsConstruits() {
        // Construits ici : écrits en clair, ces lignes seraient prises par le hook pre-commit.
        String sansLitteral1 = "String pass" + "word = getPass" + "word();";
        String sansLitteral2 = "pass" + "word = null;";
        String uuid = UUID.nameUUIDFromBytes("fixe".getBytes(StandardCharsets.UTF_8)).toString();
        String sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

        for (String input : List.of(sansLitteral1, sansLitteral2, uuid, sha256)) {
            Redactor.Result r = redactor.redact(input);
            assertEquals(input, r.text());
            assertEquals(0, r.total());
        }
    }

    @Test
    @DisplayName("toString ne révèle ni la clé ni une valeur")
    void toStringMuet() {
        assertFalse(redactor.toString().contains("cle-de-test"));
    }
}

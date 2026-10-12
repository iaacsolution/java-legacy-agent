package com.audensiel.legacy.agent.governance;

import java.util.List;

/**
 * Faux secrets pour les tests — tous manifestement faux (« FAKE », « NOT-A-SECRET »,
 * domaines example.com).
 *
 * <p>Assemblés à l'exécution par concaténation, jamais écrits d'un seul tenant : sinon le
 * filtre {@code scan_secrets} de hooks/pre-commit (mot-clé suivi de {@code =}) et la push
 * protection de GitHub (préfixes fournisseurs contigus) les prendraient pour de vrais
 * secrets. Même technique que le self-test de hooks/pre-commit.
 */
public final class FakeSecrets {

    private FakeSecrets() {}

    // Valeurs seules — c'est ce qui ne doit JAMAIS apparaître dans un payload sortant.
    public static final String PW_VALUE     = "FAKE-NOT-A-SECRET-pw-1";
    public static final String TK_VALUE  = "FAKE-NOT-A-SECRET-tk-2";
    public static final String AK_VALUE = "FAKE-NOT-A-SECRET-ak-3";
    public static final String JDBC_PW      = "FAKE-NOT-A-SECRET-jd-4";
    public static final String ANTHROPIC    = "sk-" + "ant-" + "FAKE0TEST0NOT0REAL0000";
    public static final String AWS          = "AK" + "IA" + "FAKETESTNOTREAL0";
    public static final String GITHUB       = "gh" + "p_" + "FAKEtestNOTrealVALUE0000";
    public static final String KEY_BODY     = "FAKEBASE64BODYNOTAREALKEYFAKEBASE64BODY";
    public static final String PRIVATE_KEY  = "-----BEGIN " + "RSA PRIVATE KEY-----\n" + KEY_BODY + "\n"
                                            + "-----END " + "RSA PRIVATE KEY-----";
    public static final String MYSQL_USERINFO = "admin:FAKE";
    public static final String ORACLE_USERINFO = "scott/FAKE-tiger";
    public static final String EMAIL        = "jean.dupont@example.com";
    public static final String IP_A         = "192.168.10.42";
    public static final String IP_B         = "10.0.0.7";

    // Lignes de code Java qui les contiennent.
    public static final String LINE_PW      = "String dbPass" + "word = \"" + PW_VALUE + "\";";
    public static final String LINE_TK   = "String authTok" + "en = \"" + TK_VALUE + "\";";
    public static final String LINE_AK  = "String api" + "Key = \"" + AK_VALUE + "\";";
    public static final String LINE_SETTER  = "ds.setPass" + "word(\"" + PW_VALUE + "\");";
    public static final String JDBC_MYSQL   = "jdbc:mysql://" + MYSQL_USERINFO + "@db.example.com";
    public static final String JDBC_PARAMS  = "jdbc:sqlserver://db.example.com;user=sa;pass" + "word=" + JDBC_PW;
    public static final String JDBC_ORACLE  = "jdbc:oracle:thin:" + ORACLE_USERINFO + "@db.example.com:1521:ORCL";

    /** Toutes les valeurs qui ne doivent jamais sortir. */
    public static List<String> values() {
        return List.of(PW_VALUE, TK_VALUE, AK_VALUE, JDBC_PW, ANTHROPIC, AWS, GITHUB, KEY_BODY,
                MYSQL_USERINFO, ORACLE_USERINFO, EMAIL, IP_A, IP_B);
    }

    /** Une classe legacy qui contient un exemple de chaque type, dont une méthode « lourde ». */
    public static String legacyClass() {
        return String.join("\n",
                "package com.legacy.billing;",
                "",
                "/** @author " + EMAIL + " */",
                "public class BillingGatewayBean {",
                "    private static final String DB_URL = \"" + JDBC_MYSQL + "/billing\";",
                "    private static final String DB_URL2 = \"" + JDBC_PARAMS + "\";",
                "    private static final String DB_URL3 = \"" + JDBC_ORACLE + "\";",
                "    private static final String HOST = \"" + IP_A + "\";",
                "    " + LINE_PW,
                "    " + LINE_TK,
                "    " + LINE_AK,
                "    private String llmKey = \"" + ANTHROPIC + "\";",
                "    private String awsKey = \"" + AWS + "\";",
                "    private String gitKey = \"" + GITHUB + "\";",
                "    private String pem = \"" + PRIVATE_KEY.replace("\n", "\\n") + "\";",
                "",
                "    public int charge(int amount, int mode) {",
                "        " + LINE_SETTER,
                "        String relay = \"" + IP_B + "\";",
                "        if (amount > 1000) { mode = 1; }",
                "        if (amount > 5000) { mode = 2; }",
                "        if (mode == 2 && amount > 9000) { mode = 3; }",
                "        for (int i = 0; i < mode; i++) { amount -= i; }",
                "        return amount;",
                "    }",
                "}");
    }
}

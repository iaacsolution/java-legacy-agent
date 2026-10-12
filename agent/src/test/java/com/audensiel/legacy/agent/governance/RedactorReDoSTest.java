package com.audensiel.legacy.agent.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Résistance au retour arrière catastrophique (ReDoS) : chaque entrée piégée doit être
 * filtrée en moins d'une seconde. Le Redactor voit du code arbitraire (lignes minifiées,
 * chaînes encodées, blocs générés) : une règle quadratique bloquerait le pipeline.
 *
 * <p>Mots-clés assemblés à l'exécution : écrits d'un seul tenant devant {@code =}, ces lignes
 * seraient prises par le filtre scan_secrets de hooks/pre-commit.
 */
class RedactorReDoSTest {

    private static final int N = 100_000;
    private static final Duration LIMITE = Duration.ofSeconds(1);

    private static final String PW = "pass" + "word";
    private static final String TK = "tok" + "en";
    private static final String AK = "api" + "_key";

    private static String fill(String motif) {
        return motif.repeat(N / motif.length() + 1).substring(0, N);
    }

    private static Map<String, String> pieges() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("100 000 caractères sans espace", fill("a"));
        p.put("mot-clé répété sans fin, sans =", fill(PW));
        p.put("mot-clé répété puis = sans littéral", fill(PW) + "=");
        p.put("mot-clé répété puis = et guillemet non fermé", fill(PW) + "=\"" + fill("x"));
        p.put("forme setter sans littéral", fill(PW) + "(" + fill(" "));
        p.put("forme put(\"clé\", …) sans valeur", "put(\"" + fill(PW) + "\", ");
        p.put("littéral ouvert, mots-clés séparés, aucun =", "\"" + fill(PW + " "));
        p.put("littéral ouvert, jeton répété", "\"" + fill(TK + "_"));
        p.put("littéral ouvert, api_key répété", "\"" + fill(AK + "."));
        p.put("e-mail : local-part sans @", fill("a."));
        p.put("e-mail : domaine sans TLD valide", "a@" + fill("b.") + "1");
        p.put("IP : octets répétés", fill("10."));
        p.put("JDBC : URL sans fin", "jdbc:" + fill("a"));
        p.put("JDBC : user:pass sans @", "jdbc:mysql://" + fill("a:"));
        p.put("JDBC : paramètres répétés", "jdbc:x:" + fill("?user="));
        p.put("JDBC : sous-protocoles répétés", "jdbc:" + fill("a:") + "//u:p@h");
        p.put("clé privée : BEGIN sans END, répété", fill("-----BEGIN " + "RSA PRIVATE KEY-----"));
        p.put("préfixe sk- répété", fill("sk-"));
        p.put("guillemets alternés", fill("\"a\"="));
        return p;
    }

    @TestFactory
    @DisplayName("ReDoS : chaque entrée piégée est filtrée en moins d'une seconde")
    Stream<DynamicTest> chaquePiegeSousUneSeconde() {
        Redactor redactor = Redactor.withKey("cle-de-test-fixe".getBytes(StandardCharsets.UTF_8));
        redactor.redact("préchauffage : " + FakeSecrets.legacyClass());   // compilation JIT hors chrono
        return pieges().entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(),
                () -> assertTimeoutPreemptively(LIMITE, () -> { redactor.redact(e.getValue()); })));
    }
}

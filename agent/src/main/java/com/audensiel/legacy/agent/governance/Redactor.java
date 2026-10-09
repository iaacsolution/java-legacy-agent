package com.audensiel.legacy.agent.governance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remplace secrets et PII par des marqueurs stables {@code <REDACTED:TYPE:h>} avant tout
 * envoi à un LLM.
 *
 * <p><b>Marqueur.</b> {@code h} = 8 premiers caractères hexadécimaux de
 * HMAC-SHA256(clé, secret). Même secret ⇒ même marqueur sur tous les appels du processus,
 * quel que soit l'ordre des workers, et <b>aucune table de correspondance</b> n'existe :
 * rien à protéger, rien qui permette de réinjecter un secret dans un document produit.
 * La clé est tirée par {@link SecureRandom} au démarrage, ne vit qu'en mémoire, et n'est
 * jamais journalisée, persistée ni envoyée. Sans elle, le fournisseur du LLM ne peut pas
 * retrouver un secret faible (« admin ») par dictionnaire.
 *
 * <p><b>Pourquoi des expressions régulières.</b> Il n'existe pas d'équivalent Java maintenu
 * à detect-secrets ; gitleaks est un binaire Go. Conséquence assumée : des faux négatifs
 * restent possibles (secret construit dynamiquement, nom de variable neutre, format
 * inconnu). Ce n'est pas une garantie absolue — voir README, section « Redaction ».
 *
 * <p><b>Ordre des règles</b> (chacune s'applique au texte déjà filtré par les précédentes) :
 * <ol>
 *   <li>{@code PRIVATE_KEY} — bloc multiligne, peut contenir n'importe quelle suite de caractères ;</li>
 *   <li>{@code JDBC_CREDENTIALS} — avant EMAIL ({@code admin:FAKE@db.example.com} contient
 *       {@code FAKE@db.example.com}) et avant PASSWORD (le paramètre de mot de passe d'une URL
 *       JDBC est un identifiant JDBC) ;</li>
 *   <li>{@code API_KEY} (préfixes connus), puis affectations {@code API_KEY}, {@code PASSWORD},
 *       {@code SECRET_TOKEN} ;</li>
 *   <li>{@code EMAIL} ;</li>
 *   <li>{@code INTERNAL_IP} en dernier — une IP peut être l'hôte d'une URL JDBC dont seuls
 *       les identifiants ont été remplacés.</li>
 * </ol>
 * Aucune règle ne capture un marqueur déjà posé : filtrer deux fois donne le même texte.
 */
public final class Redactor {

    public enum Type { PRIVATE_KEY, JDBC_CREDENTIALS, API_KEY, PASSWORD, SECRET_TOKEN, EMAIL, INTERNAL_IP }

    /** Texte filtré et nombre de remplacements par type. Ne contient jamais de valeur d'origine. */
    public record Result(String text, Map<Type, Integer> counts) {
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Une règle : un motif, le groupe à remplacer (0 = tout le motif), un filtre éventuel sur
     * la valeur capturée, et une portée optionnelle — si elle est présente, le motif n'est
     * cherché qu'à l'intérieur de chaque correspondance de la portée (paramètres d'une URL JDBC).
     */
    private record Rule(Type type, Pattern scope, Pattern pattern, int group, Predicate<String> accept) {
        Rule(Type type, Pattern pattern, int group) {
            this(type, null, pattern, group, v -> true);
        }
        Rule(Type type, Pattern pattern, int group, Predicate<String> accept) {
            this(type, null, pattern, group, accept);
        }
    }

    private static final String MARKER_PREFIX = "<REDACTED:";

    // Valeur d'un littéral affecté : non vide, ni marqueur déjà posé, ni gabarit ${...}.
    private static final String LITERAL_VALUE = "(?!<REDACTED:)(?!\\$\\{)([^\"\\r\\n]+)";
    // Caractères d'URL hors délimiteurs ; '<' et '>' exclus pour ne jamais capturer un marqueur.
    private static final String URL_CHARS = "[^\\s\"'<>@/:;&?]";

    private static final List<Rule> RULES = List.of(
            // 1. Clé privée : tout le bloc BEGIN … END.
            new Rule(Type.PRIVATE_KEY, Pattern.compile(
                    "-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----"), 0),

            // 2. JDBC — identifiants seuls, la structure de l'URL reste lisible.
            //    Paramètres d'URL user / password (forme « ? & » ou « ; » de SQL Server) EN PREMIER :
            //    chaque paramètre de chaque URL, d'où la portée. Passés après la forme user:pass@,
            //    ils seraient hors portée — le marqueur posé contient '<', qui clôt l'URL.
            new Rule(Type.JDBC_CREDENTIALS, Pattern.compile("jdbc:[^\\s\"'<>]+"), Pattern.compile(
                    "(?i)[?;&](?:user|username|password|pwd)=((?!<REDACTED:)[^&;\\s\"'<>]+)"), 1, v -> true),
            //    jdbc:mysql://user:pass@hôte — utilisateur ET mot de passe non vides.
            new Rule(Type.JDBC_CREDENTIALS, Pattern.compile(
                    "jdbc:[A-Za-z0-9]+(?::[A-Za-z0-9]+)*://(" + URL_CHARS + "+:" + URL_CHARS + "+)@"), 1),
            //    jdbc:oracle:thin:user/pass@hôte — « jdbc:oracle:thin:@hôte » (sans identifiants) ne correspond pas.
            new Rule(Type.JDBC_CREDENTIALS, Pattern.compile(
                    "jdbc:oracle:[A-Za-z]+:(" + URL_CHARS + "+/" + URL_CHARS + "+)@"), 1),

            // 3a. Clés d'API à préfixe connu — en début de jeton uniquement
            //     (« disk-cache-… », « task-… », « risk-… » ne sont pas des clés sk-).
            new Rule(Type.API_KEY, Pattern.compile(
                    "(?<![A-Za-z0-9_-])(?:sk-ant-[A-Za-z0-9_-]{16,}|sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}"
                  + "|ghp_[A-Za-z0-9]{20,}|xox[bp]-[A-Za-z0-9-]{10,})(?![A-Za-z0-9_-])"), 0),

            // 3b. Affectation d'un littéral à un identifiant révélateur.
            //     x = "…", x: "…", "x": "…", setX("…"), put("x", "…"), et x=… à l'intérieur d'un littéral.
            //     Sans littéral à droite (appel de méthode, null) : aucune correspondance.
            assignment(Type.API_KEY, "api_?key"),
            assignment(Type.PASSWORD, "password|passwd|pwd"),
            assignment(Type.SECRET_TOKEN, "secret|token|credential"),
            propertyInLiteral(Type.API_KEY, "api_?key"),
            propertyInLiteral(Type.PASSWORD, "password|passwd|pwd"),
            propertyInLiteral(Type.SECRET_TOKEN, "secret|token|credential"),

            // 4. E-mail — le caractère qui précède le local-part ne peut pas en faire partie
            //    (exclut « thin:@hôte » d'Oracle et un marqueur JDBC suivi de « @hôte »).
            new Rule(Type.EMAIL, Pattern.compile(
                    "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"), 0),

            // 5. IP internes (RFC 1918 + boucle locale). Octets ≤ 255 vérifiés ; une suite de
            //    plus de 4 nombres (numéro de version) ne correspond pas.
            new Rule(Type.INTERNAL_IP, Pattern.compile(
                    "(?<![\\d.])(?:10|127|192\\.168|172\\.(?:1[6-9]|2\\d|3[01]))(?:\\.\\d{1,3}){2,3}(?!\\.?\\d)"), 0,
                    Redactor::isInternalIpv4)
    );

    private static Rule assignment(Type type, String keywords) {
        String ident = "\\b\\w*(?:" + keywords + ")\\w*";
        return new Rule(type, Pattern.compile(
                "(?i)(?:" + ident + "\"?\\s*[=:]\\s*"            // x = "…" · x: "…" · "x": "…"
              + "|" + ident + "\\s*\\(\\s*"                      // setX("…")
              + "|\"" + "\\w*(?:" + keywords + ")\\w*" + "\"\\s*,\\s*)"  // put("x", "…")
              + "\"" + LITERAL_VALUE + "\""), 1);
    }

    private static Rule propertyInLiteral(Type type, String keywords) {
        // « x=valeur » sans espace, à l'intérieur d'un littéral : la séquence doit suivre un
        // guillemet ouvrant sur la même ligne, sans autre guillemet entre les deux.
        return new Rule(type, Pattern.compile(
                "(?i)\"[^\"\\r\\n]*?\\b\\w*(?:" + keywords + ")\\w*=((?!<REDACTED:)(?!\\$\\{)[^&;\"\\s]+)"), 1);
    }

    private static boolean isInternalIpv4(String candidate) {
        String[] octets = candidate.split("\\.");
        if (octets.length != 4) return false;
        for (String o : octets) {
            if (Integer.parseInt(o) > 255) return false;
        }
        return true;
    }

    private final byte[] key;

    private Redactor(byte[] key) {
        this.key = key.clone();
    }

    /** Clé aléatoire de 32 octets, tirée une fois — usage normal, une instance par processus. */
    public static Redactor withRandomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return new Redactor(k);
    }

    /** Clé fournie — pour les tests uniquement (marqueurs exacts vérifiables). */
    public static Redactor withKey(byte[] key) {
        if (key == null || key.length == 0) throw new IllegalArgumentException("clé HMAC vide");
        return new Redactor(key);
    }

    public Result redact(String text) {
        if (text == null || text.isEmpty()) return new Result(text, Map.of());
        EnumMap<Type, Integer> counts = new EnumMap<>(Type.class);
        String current = text;
        for (Rule rule : RULES) {
            current = apply(rule, current, counts);
        }
        return new Result(current, Collections.unmodifiableMap(counts));
    }

    private String apply(Rule rule, String text, Map<Type, Integer> counts) {
        if (rule.scope() == null) return applyPattern(rule, text, counts);
        Matcher scope = rule.scope().matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (scope.find()) {
            out.append(text, last, scope.start())
               .append(applyPattern(rule, scope.group(), counts));
            last = scope.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    private String applyPattern(Rule rule, String text, Map<Type, Integer> counts) {
        Matcher m = rule.pattern().matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String value = m.group(rule.group());
            if (value == null || value.isEmpty() || value.startsWith(MARKER_PREFIX) || !rule.accept().test(value)) {
                continue;
            }
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, m.start(rule.group()))
               .append(marker(rule.type(), value));
            last = m.end(rule.group());
            counts.merge(rule.type(), 1, Integer::sum);
        }
        if (out == null) return text;
        return out.append(text, last, text.length()).toString();
    }

    private String marker(Type type, String secret) {
        return MARKER_PREFIX + type + ":" + hmac8(secret) + ">";
    }

    private String hmac8(String secret) {
        try {
            // Mac n'est pas thread-safe : une instance par appel (workers parallèles).
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 est obligatoire sur toute JVM conforme : ne devrait jamais arriver.
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /** Ne révèle ni la clé ni aucune valeur. */
    @Override
    public String toString() {
        return "Redactor[clé en mémoire, non affichée]";
    }
}

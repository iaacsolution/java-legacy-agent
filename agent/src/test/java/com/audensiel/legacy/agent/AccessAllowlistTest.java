package com.audensiel.legacy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Moindre privilège, appliqué statiquement : seules les classes listées ici peuvent écrire
 * ou lire sur disque, ouvrir le réseau, appeler un LLM, lire l'environnement, lancer un
 * processus. Cette liste EST la matrice de docs/GOVERNANCE.md, côté code.
 *
 * <p>Le test échoue dans les deux sens :
 * <ul>
 *   <li>une classe hors liste utilise une de ces API (ex. un agent d'analyse pur qui se
 *       mettrait à écrire sur disque) ;</li>
 *   <li>une classe listée ne l'utilise plus : l'allowlist doit rester exacte, pas permissive.</li>
 * </ul>
 * Dans les deux cas : mettre à jour cette liste ET la matrice de docs/GOVERNANCE.md.
 *
 * <p>Analyse du source, commentaires et littéraux exclus des correspondances. Limites
 * assumées : ce test ne voit ni la réflexion, ni les bibliothèques (logback écrit son
 * fichier pour toutes les classes), ni un accès passé par une classe autorisée.
 */
class AccessAllowlistTest {

    enum Acces { ECRITURE_DISQUE, LECTURE_DISQUE, RESEAU, LLM, ENV, PROCESSUS }

    private static final Map<Acces, Pattern> MOTIFS = new EnumMap<>(Map.of(
            Acces.ECRITURE_DISQUE, Pattern.compile(
                    "Files\\s*(\\.|::)\\s*(write|writeString|newBufferedWriter|newOutputStream|createDirectories"
                  + "|createDirectory|createTempDirectory|createTempFile|createFile|delete|deleteIfExists|copy|move)\\b"
                  + "|new\\s+(FileOutputStream|FileWriter|RandomAccessFile)\\b"),
            Acces.LECTURE_DISQUE, Pattern.compile(
                    "Files\\s*(\\.|::)\\s*(readString|readAllBytes|readAllLines|lines|newBufferedReader|newInputStream"
                  + "|walk|walkFileTree|list|find)\\b|new\\s+(FileInputStream|FileReader)\\b"),
            Acces.RESEAU, Pattern.compile(
                    "HttpServer\\.create|HttpClient\\.new|DriverManager\\.getConnection|new\\s+(Socket|ServerSocket)\\b"
                  + "|\\.openConnection\\(|OtlpGrpcSpanExporter\\.builder"),
            Acces.LLM, Pattern.compile("(Anthropic|Ollama|OpenAi)ChatModel\\.builder|AiServices\\.(create|builder)"),
            Acces.ENV, Pattern.compile("System\\s*(\\.|::)\\s*(getenv|getProperty)\\b"),
            Acces.PROCESSUS, Pattern.compile("\\bProcessBuilder\\b|Runtime\\.getRuntime\\(\\)\\.exec")));

    /** Allowlist exacte — chemins relatifs au package com.audensiel.legacy.agent. */
    private static final Map<Acces, Set<String>> AUTORISE = new EnumMap<>(Map.of(
            Acces.ECRITURE_DISQUE, Set.of(
                    "CallGraphCache.java",                 // cache du call graph (AGENT_STATE_DIR)
                    "EvalMain.java",                       // --json
                    "HandoffBundle.java",                  // handoff analyze → report
                    "LegacyMigrationOrchestrator.java",    // rapport, handoff temporaire
                    "RoiLogger.java",                      // impact-roi.csv (AGENT_STATE_DIR)
                    "RunMetrics.java"),                    // metrics_<projet>.json
            Acces.LECTURE_DISQUE, Set.of(
                    "CallGraphAgent.java", "CallGraphCache.java", "EvalMain.java", "FileScannerAgent.java",
                    "HandoffBundle.java", "LegacyMigrationOrchestrator.java"),
            Acces.RESEAU, Set.of(
                    "ImpactServer.java", "MetricsPusher.java", "PipelineTracer.java", "observability/SpanRecorder.java"),
            Acces.LLM, Set.of(
                    "LlmModelFactory.java",                // seul site de construction de modèle
                    "JavaDocumentationAgent.java", "MigrationPlannerAgent.java"),
            Acces.ENV, Set.of(
                    "CallGraphAgent.java", "EvalMain.java", "ImpactServer.java", "JavaDocumentationAgent.java",
                    "LegacyMigrationOrchestrator.java", "LlmModelFactory.java", "Main.java", "StateDir.java",
                    "observability/SpanRecorder.java"),
            Acces.PROCESSUS, Set.of()));

    private static final Path RACINE = Path.of("src", "main", "java", "com", "audensiel", "legacy", "agent");

    @Test
    @DisplayName("moindre privilège : usages réels = allowlist, catégorie par catégorie")
    void usagesConformesALaMatrice() throws IOException {
        Map<Acces, Set<String>> reel = new EnumMap<>(Acces.class);
        for (Acces a : Acces.values()) reel.put(a, new TreeSet<>());

        try (Stream<Path> fichiers = Files.walk(RACINE)) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = RACINE.relativize(f).toString().replace('\\', '/');
                String code = sansCommentairesNiLitteraux(Files.readString(f, StandardCharsets.UTF_8));
                MOTIFS.forEach((acces, motif) -> {
                    if (motif.matcher(code).find()) reel.get(acces).add(rel);
                });
            }
        }

        List<String> ecarts = new ArrayList<>();
        for (Acces a : Acces.values()) {
            for (String f : reel.get(a)) {
                if (!AUTORISE.get(a).contains(f)) ecarts.add(a + " : " + f + " n'est PAS autorisé");
            }
            for (String f : AUTORISE.get(a)) {
                if (!reel.get(a).contains(f)) ecarts.add(a + " : " + f + " est autorisé mais ne l'utilise plus (allowlist à resserrer)");
            }
        }
        if (!ecarts.isEmpty()) {
            fail("Écart à la matrice des droits — mettre à jour AccessAllowlistTest ET docs/GOVERNANCE.md :\n  "
                    + String.join("\n  ", ecarts));
        }
    }

    /** Retire commentaires et contenu des littéraux (chaînes, caractères, blocs de texte). */
    static String sansCommentairesNiLitteraux(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i); i = j < 0 ? n : j; continue;
            }
            if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2); i = j < 0 ? n : j + 2; continue;
            }
            if (src.startsWith("\"\"\"", i)) {
                int j = src.indexOf("\"\"\"", i + 3); i = j < 0 ? n : j + 3; out.append("\"\""); continue;
            }
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) j += src.charAt(j) == '\\' ? 2 : 1;
                i = Math.min(j + 1, n); out.append(c).append(c); continue;
            }
            out.append(c); i++;
        }
        return out.toString();
    }
}

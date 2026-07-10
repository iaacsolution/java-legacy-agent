package com.audensiel.legacy.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Frontière de confiance entre l'analyzer (lit le code source brut, aucun accès cloud)
 * et le reporter (ne voit jamais le code brut, seulement les specs déjà produites ici).
 * Le passage par disque force la séparation : le reporter ne peut physiquement lire
 * que ce que l'analyzer a choisi d'écrire — jamais les fichiers .java d'origine.
 */
public class HandoffBundle {

    public record Data(String projectName, int fileCount, String aggregatedSpecs, String dependencyReport) {}

    public static void write(Path handoffDir, Data data) throws IOException {
        Path dir = handoffDir.resolve(data.projectName());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("specs.md"), data.aggregatedSpecs());
        Files.writeString(dir.resolve("dependencies.md"), data.dependencyReport());
        Files.writeString(dir.resolve("manifest.txt"),
                "project=" + data.projectName() + "\n"
              + "fileCount=" + data.fileCount() + "\n"
              + "generatedAt=" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n");
        System.out.println("📦 Bundle d'analyse écrit : " + dir);
    }

    public static Data read(Path handoffDir, String projectName) throws IOException {
        Path dir = handoffDir.resolve(projectName);
        if (!Files.exists(dir)) {
            throw new IllegalStateException("Aucune analyse trouvée pour '" + projectName
                    + "' dans " + handoffDir + " — lancer le mode 'analyze' d'abord.");
        }
        String specs = Files.readString(dir.resolve("specs.md"));
        String deps  = Files.readString(dir.resolve("dependencies.md"));

        int fileCount = 0;
        for (String line : Files.readAllLines(dir.resolve("manifest.txt"))) {
            if (line.startsWith("fileCount=")) {
                fileCount = Integer.parseInt(line.substring("fileCount=".length()).trim());
            }
        }
        return new Data(projectName, fileCount, specs, deps);
    }
}

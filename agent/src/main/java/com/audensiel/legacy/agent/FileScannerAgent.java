package com.audensiel.legacy.agent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 1 — Scanne un projet Java et charge les fichiers source.
 */
public class FileScannerAgent {

    public record JavaFile(Path path, String className, String content) {}

    public List<JavaFile> scanProject(Path projectRoot) throws IOException {
        List<JavaFile> files = new ArrayList<>();

        if (!Files.exists(projectRoot)) {
            throw new IllegalArgumentException("Dossier introuvable : " + projectRoot);
        }

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    String content = Files.readString(file);
                    String className = file.getFileName().toString().replace(".java", "");
                    files.add(new JavaFile(file, className, content));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        System.out.printf("📁 %d fichier(s) Java trouvé(s) dans %s%n", files.size(), projectRoot);
        return files;
    }
}

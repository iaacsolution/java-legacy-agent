package com.audensiel.legacy.agent;

import java.util.*;
import java.util.regex.*;

/**
 * Agent 3 — Extrait les dépendances entre classes sans LLM (regex).
 * Rapide et déterministe : imports, extends, implements, injections de champs.
 */
public class DependencyMapperAgent {

    public record ClassDependencies(
            String className,
            List<String> imports,
            List<String> extendsList,
            List<String> implementsList,
            List<String> fieldTypes
    ) {}

    private static final Pattern IMPORT_PATTERN    = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern EXTENDS_PATTERN   = Pattern.compile("class\\s+\\w+\\s+extends\\s+(\\w+)");
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("implements\\s+([\\w,\\s]+)\\{");
    private static final Pattern FIELD_PATTERN     = Pattern.compile("private\\s+(\\w+)\\s+\\w+");

    public ClassDependencies analyze(FileScannerAgent.JavaFile file) {
        String code = file.content();

        List<String> imports      = extract(IMPORT_PATTERN, code);
        List<String> extendsList  = extract(EXTENDS_PATTERN, code);
        List<String> implementsList = extractSplit(IMPLEMENTS_PATTERN, code);
        List<String> fieldTypes   = extract(FIELD_PATTERN, code);

        return new ClassDependencies(file.className(), imports, extendsList, implementsList, fieldTypes);
    }

    public String buildDependencyReport(List<ClassDependencies> allDeps) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Carte des dépendances\n\n");

        for (ClassDependencies dep : allDeps) {
            sb.append("### ").append(dep.className()).append("\n");
            if (!dep.extendsList().isEmpty())
                sb.append("- **Hérite de** : ").append(String.join(", ", dep.extendsList())).append("\n");
            if (!dep.implementsList().isEmpty())
                sb.append("- **Implémente** : ").append(String.join(", ", dep.implementsList())).append("\n");
            if (!dep.fieldTypes().isEmpty())
                sb.append("- **Dépendances injectées** : ").append(String.join(", ", dep.fieldTypes())).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<String> extract(Pattern pattern, String code) {
        List<String> results = new ArrayList<>();
        Matcher m = pattern.matcher(code);
        while (m.find()) results.add(m.group(1).trim());
        return results;
    }

    private List<String> extractSplit(Pattern pattern, String code) {
        List<String> results = new ArrayList<>();
        Matcher m = pattern.matcher(code);
        if (m.find()) {
            for (String part : m.group(1).split(","))
                results.add(part.trim());
        }
        return results;
    }
}

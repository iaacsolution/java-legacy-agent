package com.audensiel.legacy.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Construit le graphe d'appels inter-méthodes d'un projet Java via JavaParser.
 * Pour chaque méthode déclarée, liste tous les sites d'appel dans le projet.
 * Utilisé par BreakingChangeDetector pour identifier les appels entrants cassants.
 */
public class CallGraphAgent {

    /** Clé : "ClassName.methodName" → liste des callers "CallerClass.callerMethod:line" */
    public record CallGraph(Map<String, List<String>> callers) {

        public List<String> callersOf(String className, String methodName) {
            return callers.getOrDefault(className + "." + methodName, List.of());
        }

        public boolean hasCallers(String className, String methodName) {
            return !callersOf(className, methodName).isEmpty();
        }
    }

    private final JavaParser parser = new JavaParser(
        new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
    );

    /**
     * Scanne récursivement tous les .java du projet et construit le call graph.
     */
    public CallGraph buildCallGraph(Path projectRoot) throws IOException {
        List<Path> javaFiles = Files.walk(projectRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());

        System.out.println("[CallGraph] " + javaFiles.size() + " fichiers Java trouvés dans " + projectRoot);

        // Étape 1 — collecter toutes les déclarations de méthodes (className.methodName)
        Set<String> allMethods = new HashSet<>();
        Map<Path, CompilationUnit> parsedFiles = new LinkedHashMap<>();

        for (Path file : javaFiles) {
            try {
                var result = parser.parse(file);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    CompilationUnit cu = result.getResult().get();
                    parsedFiles.put(file, cu);
                    cu.findAll(MethodDeclaration.class).forEach(m -> {
                        String className = m.findAncestor(
                                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                .map(c -> c.getNameAsString()).orElse("Unknown");
                        allMethods.add(className + "." + m.getNameAsString());
                    });
                } else {
                    System.out.println("[CallGraph] Parse échoué : " + file.getFileName());
                }
            } catch (Exception e) {
                System.out.println("[CallGraph] Erreur : " + file.getFileName() + " — " + e.getMessage());
            }
        }
        System.out.println("[CallGraph] " + allMethods.size() + " méthodes déclarées, " + parsedFiles.size() + " fichiers parsés");

        // Étape 2 — pour chaque appel de méthode, enregistrer le caller
        Map<String, List<String>> callers = new LinkedHashMap<>();

        for (var entry : parsedFiles.entrySet()) {
            CompilationUnit cu = entry.getValue();
            cu.findAll(MethodCallExpr.class).forEach(call -> {
                String calledMethod = call.getNameAsString();

                // Cherche la méthode englobante
                String callerClass = call.findAncestor(
                        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                        .map(c -> c.getNameAsString()).orElse("Unknown");
                String callerMethod = call.findAncestor(MethodDeclaration.class)
                        .map(m -> m.getNameAsString()).orElse("<init>");
                int line = call.getBegin().map(p -> p.line).orElse(-1);

                String callerRef = callerClass + "." + callerMethod + ":" + line;

                // Associe l'appel à toutes les déclarations dont le nom correspond
                allMethods.stream()
                        .filter(m -> m.endsWith("." + calledMethod))
                        .forEach(target ->
                                callers.computeIfAbsent(target, k -> new ArrayList<>()).add(callerRef));
            });
        }

        return new CallGraph(callers);
    }
}

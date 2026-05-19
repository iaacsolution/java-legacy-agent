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
 * Cache JSON incrémental : re-scanne uniquement les fichiers modifiés depuis le dernier run.
 */
public class CallGraphAgent {

    public record CallGraph(Map<String, List<String>> callers) {

        public List<String> callersOf(String className, String methodName) {
            return callers.getOrDefault(className + "." + methodName, List.of());
        }

        public boolean hasCallers(String className, String methodName) {
            return !callersOf(className, methodName).isEmpty();
        }
    }

    private final JavaParser parser = new JavaParser(
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
    );

    public CallGraph buildCallGraph(Path projectRoot) throws IOException {
        List<Path> javaFiles = Files.walk(projectRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());

        System.out.println("[CallGraph] " + javaFiles.size() + " fichiers Java trouvés dans " + projectRoot);

        // Cache à la racine du volume monté (parent du src/) pour éviter les problèmes de droits
        Path cacheRoot = projectRoot.getParent() != null ? projectRoot.getParent().getParent() : projectRoot;
        Optional<CallGraphCache.CacheEntry> cacheOpt = CallGraphCache.load(cacheRoot);
        Map<String, List<String>> callers;
        Map<String, String> hashes;

        if (cacheOpt.isPresent()) {
            CallGraphCache.CacheEntry cache = cacheOpt.get();
            List<Path> stale = CallGraphCache.staleFiles(cache.fileHashes(), javaFiles);

            if (stale.isEmpty()) {
                System.out.println("[CallGraph] Cache valide — 0 fichier modifié (scan ignoré)");
                return new CallGraph(cache.callers());
            }

            System.out.println("[CallGraph] Cache partiel — " + stale.size() + " fichier(s) à re-scanner");
            callers = new LinkedHashMap<>(cache.callers());
            hashes  = new LinkedHashMap<>(cache.fileHashes());

            for (Path staleFile : stale) {
                hashes.remove(staleFile.toString());
                String fname = staleFile.getFileName().toString().replace(".java", "");
                callers.entrySet().removeIf(e ->
                        e.getKey().startsWith(fname + ".") ||
                        e.getValue().removeIf(c -> c.startsWith(fname + ".")));
            }
            scanFiles(stale, callers, hashes);

        } else {
            System.out.println("[CallGraph] Pas de cache — scan complet");
            callers = new LinkedHashMap<>();
            hashes  = new LinkedHashMap<>();
            scanFiles(javaFiles, callers, hashes);
        }

        CallGraphCache.save(cacheRoot, hashes, callers);
        System.out.println("[CallGraph] " + callers.size() + " méthodes indexées — cache sauvegardé");
        return new CallGraph(callers);
    }

    private void scanFiles(List<Path> files, Map<String, List<String>> callers,
                           Map<String, String> hashes) throws IOException {

        // Étape 1 — collecter les déclarations de méthodes dans les fichiers scannés
        Set<String> allMethods = new HashSet<>();
        Map<Path, CompilationUnit> parsedFiles = new LinkedHashMap<>();

        for (Path file : files) {
            try {
                var result = parser.parse(file);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    CompilationUnit cu = result.getResult().get();
                    parsedFiles.put(file, cu);
                    hashes.put(file.toString(), CallGraphCache.md5(file));
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
        System.out.println("[CallGraph] " + allMethods.size() + " méthodes déclarées, "
                + parsedFiles.size() + " fichiers parsés");

        // Étape 2 — enregistrer les appels entrants
        for (var entry : parsedFiles.entrySet()) {
            CompilationUnit cu = entry.getValue();
            cu.findAll(MethodCallExpr.class).forEach(call -> {
                String calledMethod = call.getNameAsString();
                String callerClass  = call.findAncestor(
                        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                        .map(c -> c.getNameAsString()).orElse("Unknown");
                String callerMethod = call.findAncestor(MethodDeclaration.class)
                        .map(m -> m.getNameAsString()).orElse("<init>");
                int line = call.getBegin().map(p -> p.line).orElse(-1);
                String callerRef = callerClass + "." + callerMethod + ":" + line;

                allMethods.stream()
                        .filter(m -> m.endsWith("." + calledMethod))
                        .forEach(target ->
                                callers.computeIfAbsent(target, k -> new ArrayList<>()).add(callerRef));
            });
        }
    }
}

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

    private static final ParserConfiguration PARSER_CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

    private final java.util.function.Function<String, String> env;

    public CallGraphAgent() {
        this(System::getenv);
    }

    /** Environnement injectable (AGENT_STATE_DIR) — pour les tests. */
    CallGraphAgent(java.util.function.Function<String, String> env) {
        this.env = env;
    }

    // JavaParser n'est pas thread-safe : une instance partagée entre appels concurrents
    // provoque des échecs de parsing — observés ici sous la forme d'un
    // java.lang.AssertionError « A reference was unexpectedly null » venant des entrailles
    // de JavaParser. Comme c'est une Error et non une Exception, le catch de scanFiles ne
    // la retient même pas : elle fait tomber l'analyse entière.
    // Même correctif qu'AstParserAgent.newParser() (commit 4128e54) : une instance par
    // appel supprime l'état partagé. Surtout PAS de verrou partagé — ce serait revenir
    // sur ce correctif, et sérialiser un objet dont la recréation est négligeable.
    private JavaParser newParser() {
        return new JavaParser(PARSER_CONFIG);
    }

    /** Graphe et nombre de fichiers exclus volontairement (IMPACT_EXCLUSIONS_FILE). */
    public record ScanResult(CallGraph graph, int excludedFiles) {}

    /**
     * Construit le graphe, avec un cache utilisé SEULEMENT si le contenu de TOUS les fichiers
     * est identique (MD5) et qu'aucun fichier n'a été ajouté ni supprimé. Au moindre
     * changement : scan complet.
     *
     * <p>Avant le Lot 3, un rescan incrémental des seuls fichiers modifiés perdait des appels :
     * les méthodes cibles n'étaient cherchées que dans les fichiers rescannés (un appel depuis
     * un fichier modifié vers un fichier inchangé disparaissait), la purge supprimait une
     * entrée entière dès qu'un seul de ses appelants changeait, et un fichier supprimé
     * gardait ses appels. Un appelant manquant fait passer un changement cassant pour sûr :
     * la justesse prime sur le gain de temps.
     */
    public CallGraph buildCallGraph(Path projectRoot) throws IOException {
        return scan(projectRoot).graph();   // mêmes règles que scan() : échec fermé, exclusions
    }

    /**
     * Construit le graphe d'appels du projet — ou échoue : jamais un graphe incomplet présenté
     * comme complet (écart E8, docs/GOVERNANCE.md).
     *
     * <p>Échec fermé ({@link ImpactScanException}) si un seul fichier ne se parse pas : ses
     * appels disparaîtraient du graphe, et un changement cassant passerait pour sûr. Seul
     * recours : la liste d'exclusions ({@link ImpactExclusions}), explicite et justifiée ; le
     * nombre de fichiers exclus est toujours rapporté.
     *
     * <p>Cache utilisé SEULEMENT si le contenu de TOUS les fichiers est identique (MD5), sans
     * ajout ni suppression, et avec la même liste d'exclusions. Au moindre changement : scan
     * complet. Un scan en échec n'est jamais mis en cache (le passage suivant le prendrait
     * pour valide). Avant le Lot 3, un rescan incrémental perdait des appels : la justesse
     * prime sur le gain de temps.
     */
    public ScanResult scan(Path projectRoot) throws IOException {
        List<Path> javaFiles;
        try (var stream = Files.walk(projectRoot)) {
            javaFiles = stream.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }

        // Dossier d'état hors du projet analysé (refus explicite sinon), cache propre au projet.
        Path stateDir = StateDir.resolve(projectRoot, env);
        StateDir.warnLegacyArtifacts(projectRoot);
        Path cacheFile = CallGraphCache.cacheFileFor(stateDir, projectRoot);

        ImpactExclusions.Exclusions exclusions = ImpactExclusions.load(projectRoot, env);
        Path racine = projectRoot.toAbsolutePath().normalize();
        List<Path> analyses = javaFiles.stream()
                .filter(f -> !exclusions.paths().contains(relatif(racine, f)))
                .collect(Collectors.toList());

        System.out.println("[CallGraph] " + javaFiles.size() + " fichiers Java trouvés dans " + projectRoot
                + " — " + exclusions.count() + " fichier(s) exclu(s) volontairement");

        // Clé du cache = MD5 des seuls fichiers ANALYSÉS : exclure un fichier (ou cesser de
        // l'exclure) change cet ensemble, donc invalide le cache — sans entrée supplémentaire.
        Map<String, String> hashes = CallGraphCache.hashesOf(projectRoot, analyses);
        Optional<CallGraphCache.CacheEntry> cacheOpt = CallGraphCache.loadFile(cacheFile);

        if (cacheOpt.isPresent() && cacheOpt.get().fileHashes().equals(hashes)) {
            System.out.println("[CallGraph] Cache valide — contenu identique (scan ignoré)");
            return new ScanResult(new CallGraph(cacheOpt.get().callers()), exclusions.count());
        }

        System.out.println(cacheOpt.isPresent()
                ? "[CallGraph] Contenu modifié, ajouté ou supprimé — scan complet"
                : "[CallGraph] Pas de cache — scan complet");
        Map<String, List<String>> callers = new LinkedHashMap<>();
        List<String> nonParses = scanFiles(racine, analyses, callers);

        if (!nonParses.isEmpty()) {
            throw new ImpactScanException(nonParses.size() + " fichier(s) non parsé(s) : graphe d'appels incomplet",
                    nonParses, exclusions.count(), null);
        }

        CallGraphCache.saveFile(cacheFile, hashes, callers);
        System.out.println("[CallGraph] " + callers.size() + " méthodes indexées — cache sauvegardé");
        return new ScanResult(new CallGraph(callers), exclusions.count());
    }

    private static String relatif(Path racine, Path f) {
        return racine.relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Renvoie les fichiers NON parsés (chemins relatifs au projet). */
    private List<String> scanFiles(Path racine, List<Path> files, Map<String, List<String>> callers) throws IOException {

        // Étape 1 — collecter les déclarations de méthodes dans les fichiers scannés
        Set<String> allMethods = new HashSet<>();
        Map<Path, CompilationUnit> parsedFiles = new LinkedHashMap<>();
        List<String> nonParses = new ArrayList<>();

        for (Path file : files) {
            try {
                var result = newParser().parse(file);
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
                    System.out.println("[CallGraph] Parse échoué : " + relatif(racine, file));
                    nonParses.add(relatif(racine, file));
                }
            } catch (Exception | AssertionError e) {
                // AssertionError : forme observée d'un échec interne de JavaParser (voir newParser).
                System.out.println("[CallGraph] Erreur : " + relatif(racine, file) + " — " + e.getClass().getSimpleName());
                nonParses.add(relatif(racine, file));
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
        return nonParses;
    }
}

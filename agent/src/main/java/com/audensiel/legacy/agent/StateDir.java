package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Dossier d'état de l'outil : cache du call graph et {@code impact-roi.csv}.
 *
 * <p>Avant le Lot 3, ces deux fichiers étaient écrits dans le GRAND-PARENT du chemin
 * analysé — la racine du dépôt pour l'usage des hooks ({@code impact /project/agent/src}),
 * mais {@code /home} pour un projet {@code /home/x/proj}. Désormais : {@code AGENT_STATE_DIR},
 * par défaut {@code .java-legacy-agent/} dans le répertoire courant.
 *
 * <p>Un outil d'analyse ne doit pas écrire dans le dépôt qu'il analyse : un dossier d'état
 * situé À L'INTÉRIEUR du projet analysé est refusé (exception explicite, jamais de repli
 * silencieux vers un autre emplacement).
 */
public final class StateDir {

    private static final Logger log = LoggerFactory.getLogger(StateDir.class);

    public static final String ENV = "AGENT_STATE_DIR";
    public static final String DEFAULT_NAME = ".java-legacy-agent";

    /** Noms des fichiers que l'outil écrivait dans le grand-parent avant le Lot 3. */
    static final List<String> LEGACY_FILES = List.of(".callgraph-cache.json", "impact-roi.csv");

    private StateDir() {}

    public static Path resolve(Path analyzedProject) {
        return resolve(analyzedProject, System::getenv);
    }

    public static Path resolve(Path analyzedProject, Function<String, String> env) {
        String configured = env.apply(ENV);
        Path dir = (configured == null || configured.isBlank())
                ? Path.of("").toAbsolutePath().resolve(DEFAULT_NAME)
                : Path.of(configured);
        dir = dir.toAbsolutePath().normalize();
        Path project = analyzedProject.toAbsolutePath().normalize();
        if (dir.startsWith(project)) {
            throw new IllegalStateException(ENV + " (" + dir + ") est à l'intérieur du projet analysé ("
                    + project + ") : l'outil n'écrit jamais dans le dépôt qu'il analyse. "
                    + "Définir " + ENV + " hors du projet.");
        }
        return dir;
    }

    /**
     * Fichiers orphelins laissés dans le grand-parent par les versions d'avant le Lot 3. Ils
     * ne sont plus lus ni écrits ; on les signale sans jamais les supprimer (ils peuvent
     * appartenir à l'utilisateur, ou à un autre outil).
     */
    public static List<Path> legacyArtifacts(Path analyzedProject) {
        Path p = analyzedProject.toAbsolutePath().normalize();
        Path grandParent = p.getParent() != null ? p.getParent().getParent() : null;
        List<Path> found = new ArrayList<>();
        if (grandParent == null) return found;
        for (String name : LEGACY_FILES) {
            Path f = grandParent.resolve(name);
            if (Files.exists(f)) found.add(f);
        }
        return found;
    }

    public static void warnLegacyArtifacts(Path analyzedProject) {
        for (Path f : legacyArtifacts(analyzedProject)) {
            log.warn("Fichier orphelin d'avant le Lot 3, plus utilisé et NON supprimé : {} "
                    + "(l'état vit désormais dans {})", f, ENV);
        }
    }
}

package com.audensiel.legacy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nettoyage du handoff temporaire : un échec de suppression n'est plus avalé
 * (LegacyMigrationOrchestrator, ex-{@code catch (IOException ignored)}).
 */
class HandoffCleanupTest {

    @Test
    @DisplayName("suppression complète : rien ne reste, aucun échec rapporté")
    void suppressionComplete(@TempDir Path base) throws IOException {
        Path handoff = Files.createDirectories(base.resolve("handoff/projet"));
        Files.writeString(handoff.resolve("specs.md"), "specs");

        List<Path> echecs = LegacyMigrationOrchestrator.deleteRecursively(base.resolve("handoff"), Files::deleteIfExists);

        assertTrue(echecs.isEmpty());
        assertFalse(Files.exists(base.resolve("handoff")));
    }

    @Test
    @DisplayName("échec de suppression : rapporté (plus avalé), les autres fichiers sont quand même supprimés")
    void echecRapporte(@TempDir Path base) throws IOException {
        Path handoff = Files.createDirectories(base.resolve("handoff/projet"));
        Path bloque = Files.writeString(handoff.resolve("specs.md"), "specs");
        Files.writeString(handoff.resolve("manifest.txt"), "manifest");

        List<Path> echecs = LegacyMigrationOrchestrator.deleteRecursively(base.resolve("handoff"), p -> {
            if (p.equals(bloque)) throw new IOException("suppression refusée (simulée)");
            Files.deleteIfExists(p);
        });

        assertTrue(echecs.contains(bloque), "le fichier non supprimé doit être rapporté");
        assertTrue(Files.exists(bloque));
        assertFalse(Files.exists(handoff.resolve("manifest.txt")), "les autres suppressions continuent");
    }

    @Test
    @DisplayName("dossier absent : aucun échec")
    void dossierAbsent(@TempDir Path base) {
        assertTrue(LegacyMigrationOrchestrator.deleteRecursively(base.resolve("absent"), Files::deleteIfExists).isEmpty());
    }
}

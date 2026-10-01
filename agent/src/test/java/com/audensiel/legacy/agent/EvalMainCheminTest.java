package com.audensiel.legacy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Les sorties d'{@code EvalMain} sont conservées comme preuves de mesure et commitées
 * (voir {@code results/raw/}). Un chemin absolu y ferait fuiter le nom d'utilisateur et
 * l'arborescence locale vers un dépôt public — c'est arrivé, six logs ont dû être
 * assainis après coup.
 *
 * <p>Ce test verrouille la règle : ce qui est affiché ne doit jamais ressembler à un
 * chemin absolu.
 */
class EvalMainCheminTest {

    @Test
    @DisplayName("un dataset du répertoire de travail est affiché en relatif")
    void datasetLocalAfficheEnRelatif() {
        assertEquals("golden_dataset_3cases.json",
                EvalMain.cheminRelatif(Path.of("golden_dataset_3cases.json")));
    }

    @Test
    @DisplayName("un chemin absolu pointant dans le répertoire de travail est replié en relatif")
    void absoluInterneReplieEnRelatif() {
        Path absolu = Path.of("golden_dataset_3cases.json").toAbsolutePath();
        String affiche = EvalMain.cheminRelatif(absolu);

        assertEquals("golden_dataset_3cases.json", affiche);
        assertFalse(affiche.contains(System.getProperty("user.name")),
                "le nom d'utilisateur ne doit jamais apparaître dans une sortie commitée");
    }

    @Test
    @DisplayName("un dataset hors du répertoire de travail est réduit à son nom de fichier")
    void horsRepertoireReduitAuNomDeFichier() {
        // relativize() produirait ici une enfilade de ".." tout aussi bavarde que l'absolu.
        Path dehors = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("ailleurs").resolve("mon_dataset.json");

        assertEquals("mon_dataset.json", EvalMain.cheminRelatif(dehors));
    }

    @Test
    @DisplayName("aucune forme de chemin absolu ne subsiste dans l'affichage")
    void jamaisDApparenceAbsolue() {
        for (Path p : new Path[]{
                Path.of("golden_dataset.json"),
                Path.of("golden_dataset.json").toAbsolutePath(),
                Path.of(System.getProperty("java.io.tmpdir")).resolve("x.json")}) {

            String affiche = EvalMain.cheminRelatif(p);
            assertFalse(affiche.matches("^[A-Za-z]:[\\\\/].*"), "racine Windows visible : " + affiche);
            assertFalse(affiche.startsWith("/"), "racine POSIX visible : " + affiche);
            assertFalse(affiche.contains(".."), "remontée visible : " + affiche);
        }
    }
}

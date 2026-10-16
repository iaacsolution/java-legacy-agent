package com.audensiel.legacy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Le cache du call graph doit refléter le CONTENU actuel du projet analysé, et lui seul.
 */
class CallGraphCacheCorrectnessTest {

    private static final String B = "public class B { public void foo() {} }";
    private static final String A = "public class A { void run(B b) { b.foo(); } }";

    private static Path projet(Path racine) throws IOException {
        Path src = Files.createDirectories(racine.resolve("src"));
        Files.writeString(src.resolve("B.java"), B);
        Files.writeString(src.resolve("A.java"), A);
        return src;
    }

    private static CallGraphAgent agent(Path etat) {
        Function<String, String> env = k -> "AGENT_STATE_DIR".equals(k) ? etat.toString() : null;
        return new CallGraphAgent(env);
    }

    @Test
    @DisplayName("contenu modifié : l'appel retiré disparaît du graphe")
    void contenuModifie(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"));
        CallGraphAgent agent = agent(base.resolve("etat"));
        assertTrue(agent.buildCallGraph(src).hasCallers("B", "foo"));

        Files.writeString(src.resolve("A.java"), "public class A { void run(B b) { } }");
        assertFalse(agent.buildCallGraph(src).hasCallers("B", "foo"), "cache invalidé par le contenu");
    }

    @Test
    @DisplayName("fichier supprimé : ses appels ne survivent pas dans le cache")
    void fichierSupprime(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"));
        CallGraphAgent agent = agent(base.resolve("etat"));
        assertTrue(agent.buildCallGraph(src).hasCallers("B", "foo"));

        Files.delete(src.resolve("A.java"));
        assertFalse(agent.buildCallGraph(src).hasCallers("B", "foo"),
                "l'appelant supprimé ne doit plus apparaître");
    }

    @Test
    @DisplayName("fichier modifié qui appelle toujours la méthode : l'appel est conservé")
    void appelConserveApresModification(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"));
        CallGraphAgent agent = agent(base.resolve("etat"));
        assertTrue(agent.buildCallGraph(src).hasCallers("B", "foo"));

        // A change (commentaire ajouté) mais appelle toujours b.foo() ; B est inchangé.
        Files.writeString(src.resolve("A.java"), "public class A { /* v2 */ void run(B b) { b.foo(); } }");
        assertTrue(agent.buildCallGraph(src).hasCallers("B", "foo"),
                "un appel depuis un fichier modifié vers un fichier inchangé ne doit pas disparaître");
    }

    @Test
    @DisplayName("méthode à deux appelants : modifier l'un conserve l'autre")
    void appelantsMultiples(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"));
        Files.writeString(src.resolve("C.java"), "public class C { void go(B b) { b.foo(); } }");
        CallGraphAgent agent = agent(base.resolve("etat"));
        assertEquals(2, agent.buildCallGraph(src).callersOf("B", "foo").size());

        Files.writeString(src.resolve("A.java"), "public class A { void run(B b) { } }");
        var callers = agent.buildCallGraph(src).callersOf("B", "foo");
        assertEquals(1, callers.size(), "seul l'appelant retiré disparaît : " + callers);
        assertTrue(callers.get(0).startsWith("C.go"), callers.toString());
    }

    @Test
    @DisplayName("cache : fichier propre au projet sous AGENT_STATE_DIR, rien dans le grand-parent")
    void emplacementDuCache(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"));
        Path etat = base.resolve("etat");
        agent(etat).buildCallGraph(src);

        assertTrue(Files.exists(CallGraphCache.cacheFileFor(etat, src)));
        assertFalse(Files.exists(base.resolve(".callgraph-cache.json")),
                "plus d'écriture dans le grand-parent du projet analysé");
    }

    @Test
    @DisplayName("AGENT_STATE_DIR à l'intérieur du projet analysé : refus explicite, rien n'est écrit")
    void etatDansLeProjetRefuse(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"));
        Path dedans = src.resolve(".etat");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> agent(dedans).buildCallGraph(src));
        assertTrue(e.getMessage().contains("AGENT_STATE_DIR"));
        assertFalse(Files.exists(dedans));
    }

    @Test
    @DisplayName("anciens fichiers du grand-parent : signalés, jamais supprimés ni modifiés")
    void orphelinsSignalesNonSupprimes(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"));          // grand-parent de src = base
        Path ancienCache = Files.writeString(base.resolve(".callgraph-cache.json"), "{}");
        Path ancienCsv = Files.writeString(base.resolve("impact-roi.csv"), "entete\n");

        assertEquals(java.util.List.of(ancienCache, ancienCsv), StateDir.legacyArtifacts(src));
        agent(base.resolve("etat")).buildCallGraph(src);

        assertEquals("{}", Files.readString(ancienCache), "ancien cache ni supprimé ni réécrit");
        assertEquals("entete\n", Files.readString(ancienCsv));
    }

    @Test
    @DisplayName("deux projets, même dossier d'état : graphes séparés")
    void projetsSepares(@TempDir Path base) throws IOException {
        Path p1 = projet(base.resolve("p1"));
        Path p2 = Files.createDirectories(base.resolve("p2/src"));
        Files.writeString(p2.resolve("B.java"), B);   // B sans appelant dans p2

        CallGraphAgent agent = agent(base.resolve("etat"));
        assertTrue(agent.buildCallGraph(p1).hasCallers("B", "foo"));
        assertFalse(agent.buildCallGraph(p2).hasCallers("B", "foo"),
                "le graphe de p2 ne doit pas hériter des appelants de p1");
    }
}

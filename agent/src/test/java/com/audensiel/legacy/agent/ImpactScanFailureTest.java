package com.audensiel.legacy.agent;

import com.audensiel.legacy.agent.observability.SpanRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Écart E8 (docs/GOVERNANCE.md) : une analyse d'impact qui n'a pas pu se faire ne doit
 * JAMAIS produire « aucun impact ». Échec fermé : exception typée, code de sortie 3.
 */
class ImpactScanFailureTest {

    private static final String B = "public class B { public void foo() {} }";
    private static final String A_OK = "public class A { void run(B b) { b.foo(); } }";
    private static final String A_CASSE = "public class A { void run(B b) { b.foo(); }  ++ erreur de syntaxe";

    private static Path projet(Path racine, String contenuA) throws IOException {
        Path src = Files.createDirectories(racine.resolve("src"));
        Files.writeString(src.resolve("B.java"), B);
        Files.writeString(src.resolve("A.java"), contenuA);
        return src;
    }

    private static Function<String, String> env(Path etat, Path exclusions) {
        Map<String, String> m = new HashMap<>();
        m.put(StateDir.ENV, etat.toString());
        if (exclusions != null) m.put(ImpactExclusions.ENV, exclusions.toString());
        return m::get;
    }

    private static Path exclusions(Path base, String... lignes) throws IOException {
        return Files.writeString(base.resolve("impact-exclusions.txt"), String.join("\n", lignes) + "\n");
    }

    // ── CallGraphAgent : parse partiel = échec fermé ────────────────────────

    @Test
    @DisplayName("(3) un fichier non parsé : échec avec son chemin relatif, et aucun cache écrit")
    void fichierNonParse(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"), A_CASSE);
        Path etat = base.resolve("etat");

        ImpactScanException e = assertThrows(ImpactScanException.class,
                () -> new CallGraphAgent(env(etat, null)).scan(src));
        assertEquals(List.of("A.java"), e.unparsedFiles());
        assertFalse(Files.exists(CallGraphCache.cacheFileFor(etat, src)),
                "un scan incomplet ne doit jamais être mis en cache");
    }

    @Test
    @DisplayName("projet sain : aucun fichier exclu, compte affiché à 0")
    void projetSain(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"), A_OK);
        CallGraphAgent.ScanResult r = new CallGraphAgent(env(base.resolve("etat"), null)).scan(src);
        assertEquals(0, r.excludedFiles());
        assertTrue(r.graph().hasCallers("B", "foo"));
    }

    // ── Exclusions explicites et versionnées ────────────────────────────────

    @Test
    @DisplayName("exclusion justifiée : le fichier non parsé est écarté, compté, et l'analyse aboutit")
    void exclusionJustifiee(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"), A_CASSE);
        Path liste = exclusions(base, "# commentaire", "", "A.java | généré par un outil, syntaxe non supportée");

        CallGraphAgent.ScanResult r = new CallGraphAgent(env(base.resolve("etat"), liste)).scan(src);
        assertEquals(1, r.excludedFiles());
        assertFalse(r.graph().hasCallers("B", "foo"), "le fichier exclu ne compte pas (risque accepté)");
    }

    @Test
    @DisplayName("exclusion sans justification : refusée")
    void exclusionSansJustification(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"), A_CASSE);
        Path liste = exclusions(base, "A.java");
        ImpactScanException e = assertThrows(ImpactScanException.class,
                () -> new CallGraphAgent(env(base.resolve("etat"), liste)).scan(src));
        assertTrue(e.getMessage().contains("justification"), e.getMessage());
    }

    @Test
    @DisplayName("exclusion périmée (fichier absent) : refusée, la liste doit rester exacte")
    void exclusionPerimee(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"), A_OK);
        Path liste = exclusions(base, "Disparu.java | ancien fichier");
        assertThrows(ImpactScanException.class,
                () -> new CallGraphAgent(env(base.resolve("etat"), liste)).scan(src));
    }

    @Test
    @DisplayName("exclusion hors du projet : « .. » et chemin absolu refusés")
    void exclusionHorsProjet(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"), A_OK);
        Files.writeString(base.resolve("p/Dehors.java"), B);   // existe, mais hors de src
        for (String entree : List.of("../Dehors.java | tentative de sortie",
                                     "sub/../../Dehors.java | tentative détournée",
                                     base.resolve("p/Dehors.java").toAbsolutePath() + " | chemin absolu")) {
            Path liste = exclusions(base, entree);
            ImpactScanException e = assertThrows(ImpactScanException.class,
                    () -> new CallGraphAgent(env(base.resolve("etat"), liste)).scan(src), entree);
            assertTrue(e.getMessage().contains("hors du projet") || e.getMessage().contains("absolu"), e.getMessage());
        }
        // Tout « .. » est refusé, même s'il retombe dans le projet après normalisation.
        Path liste = exclusions(base, "x/../B.java | reste dans le projet, mais « .. » interdit");
        ImpactScanException e = assertThrows(ImpactScanException.class,
                () -> new CallGraphAgent(env(base.resolve("etat"), liste)).scan(src));
        assertTrue(e.getMessage().contains(".."), e.getMessage());
    }

    @Test
    @DisplayName("changer la liste d'exclusions invalide le cache")
    void exclusionsInvalidentLeCache(@TempDir Path base) throws IOException {
        Path src = projet(base.resolve("p"), A_CASSE);
        Path etat = base.resolve("etat");
        Path liste = exclusions(base, "A.java | généré");
        new CallGraphAgent(env(etat, liste)).scan(src);              // aboutit, cache écrit

        exclusions(base, "# plus d'exclusion");
        assertThrows(ImpactScanException.class, () -> new CallGraphAgent(env(etat, liste)).scan(src),
                "le cache écrit avec l'ancienne liste ne doit pas masquer le fichier non parsé");
    }

    // ── BreakingChangeDetector : jamais « aucun impact » sur un échec ───────

    @Test
    @DisplayName("détecteur : (1) projet absent, (2) dossier d'état refusé, (3) fichier non parsé → exception, jamais NONE")
    void detecteurEchecFerme(@TempDir Path base) throws IOException {
        Path etat = base.resolve("etat");
        Path absent = base.resolve("absent/src");
        Path sain = projet(base.resolve("sain"), A_OK);
        Path casse = projet(base.resolve("casse"), A_CASSE);

        assertThrows(ImpactScanException.class,
                () -> new BreakingChangeDetector(new CallGraphAgent(env(etat, null))).analyze(absent, "B", "foo"));
        assertThrows(ImpactScanException.class,
                () -> new BreakingChangeDetector(new CallGraphAgent(env(sain.resolve(".etat"), null))).analyze(sain, "B", "foo"));
        assertThrows(ImpactScanException.class,
                () -> new BreakingChangeDetector(new CallGraphAgent(env(etat, null))).analyze(casse, "B", "foo"));
    }

    // ── CLI : code de sortie 3 ──────────────────────────────────────────────

    private record Sortie(int code, String texte) {}

    private static Sortie impact(Path projet, String cls, String m, Function<String, String> env) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = Main.runImpact(projet, cls, m, true, new BreakingChangeDetector(new CallGraphAgent(env)),
                SpanRecorder.disabled(), env, new PrintStream(buf, true, StandardCharsets.UTF_8));
        return new Sortie(code, buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CLI : analyse impossible → code 3 et JSON d'erreur, pour les trois causes")
    void cliCode3(@TempDir Path base) throws Exception {
        Path etat = base.resolve("etat");
        Path sain = projet(base.resolve("sain"), A_OK);

        Sortie s1 = impact(base.resolve("absent/src"), "B", "foo", env(etat, null));
        Sortie s2 = impact(sain, "B", "foo", env(sain.resolve(".etat"), null));
        Sortie s3 = impact(projet(base.resolve("casse"), A_CASSE), "B", "foo", env(etat, null));

        for (Sortie s : List.of(s1, s2, s3)) {
            assertEquals(3, s.code(), s.texte());
            assertTrue(s.texte().contains("\"error\": \"scan_failed\""), s.texte());
            assertFalse(s.texte().contains("\"severity\""), "jamais de verdict quand l'analyse a échoué");
        }
        assertTrue(s3.texte().contains("\"unparsed_files\": [\"A.java\"]"), s3.texte());
    }

    @Test
    @DisplayName("CLI : codes 0 et 1 inchangés, fichiers exclus toujours affichés")
    void cliCodesInchanges(@TempDir Path base) throws Exception {
        Path etat = base.resolve("etat");
        Sortie un = impact(projet(base.resolve("un"), A_OK), "B", "foo", env(etat, null));   // 1 appelant : LOW
        assertEquals(0, un.code(), un.texte());
        assertTrue(un.texte().contains("\"excluded_files\": 0"), un.texte());

        Path trois = projet(base.resolve("trois"), A_OK);
        Files.writeString(trois.resolve("C.java"), "public class C { void go(B b) { b.foo(); } }");
        Files.writeString(trois.resolve("D.java"), "public class D { void go(B b) { b.foo(); } }");
        Sortie cassant = impact(trois, "B", "foo", env(etat, null));                          // 3 appelants : MEDIUM
        assertEquals(1, cassant.code(), cassant.texte());
        assertTrue(Files.exists(etat.resolve("impact-roi.csv")), "le CSV va dans AGENT_STATE_DIR");
    }
}

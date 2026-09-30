package com.audensiel.legacy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code CallGraphAgent} partageait une instance {@link com.github.javaparser.JavaParser}
 * entre tous les appels. C'est exactement le motif que le commit {@code 4128e54} a retiré
 * d'{@code AstParserAgent} : JavaParser n'est pas thread-safe, une instance partagée levait
 * des {@code ConcurrentModificationException} que le {@code catch (Exception)} de
 * {@code scanFiles} imprime puis avale — chaque fichier retombait sur « parse échoué » et
 * le graphe revenait incomplet, **rapporté comme un succès**.
 *
 * <p>Ce test rejoue ce montage : <strong>une seule instance</strong> de l'agent, sollicitée
 * depuis 4 threads. Le parser est alors le seul état mutable partagé, donc un échec lui est
 * attribuable.
 *
 * <p>Chaque appel reçoit son <strong>propre répertoire</strong>, pour deux raisons : le cache
 * de {@code buildCallGraph} s'écrit dans {@code projectRoot.getParent().getParent()} (des
 * répertoires communs les feraient se marcher dessus), et surtout un second appel sur le même
 * répertoire sortirait sur « cache valide » <em>sans parser</em> — ce qui masquerait la course
 * que le test cherche justement à provoquer.
 *
 * <p>Deux symptômes vérifiés, correspondant aux deux formes de l'échec :
 * <ol>
 *   <li>le graphe produit doit être <strong>identique</strong> à la référence mono-thread —
 *       échoue si une analyse revient vide ou incomplète ;</li>
 *   <li>la sortie ne doit contenir <strong>aucune</strong> trace d'erreur de parsing —
 *       échoue si une exception a été avalée.</li>
 * </ol>
 */
class CallGraphAgentConcurrencyTest {

    private static final int THREADS = 4;
    private static final int ROUNDS  = 12;      // appels par thread, chacun sur un répertoire neuf
    private static final int CLASSES = 6;       // fichiers Java par répertoire

    /** Classe jouet : run appelle helper et compute, compute appelle helper. */
    private static String source(int i) {
        return """
               public class Alpha%d {
                   public void run%d() { helper%d(); compute%d(); }
                   public int compute%d() { return helper%d() + 1; }
                   public int helper%d() { return 42; }
               }
               """.formatted(i, i, i, i, i, i, i);
    }

    /**
     * Crée {@code <base>/<nom>/wrapper/src} peuplé de CLASSES fichiers, et rend le
     * projectRoot. Le double niveau est imposé par le calcul du cacheRoot dans
     * {@code buildCallGraph}.
     */
    private static Path projet(Path base, String nom) throws Exception {
        Path src = base.resolve(nom).resolve("wrapper").resolve("src");
        Files.createDirectories(src);
        for (int i = 0; i < CLASSES; i++) {
            Files.writeString(src.resolve("Alpha" + i + ".java"), source(i));
        }
        return src;
    }

    /** Graphe normalisé : clés triées, valeurs triées — comparable d'un appel à l'autre. */
    private static Map<String, List<String>> normalise(CallGraphAgent.CallGraph g) {
        Map<String, List<String>> out = new TreeMap<>();
        g.callers().forEach((k, v) -> {
            List<String> copie = new ArrayList<>(v);
            copie.sort(String::compareTo);
            out.put(k, copie);
        });
        return out;
    }

    @Test
    @DisplayName("4 threads sur une instance partagée : aucun graphe vide, aucune exception avalée")
    void analysesParallelesCoherentes(@TempDir Path base) throws Exception {

        // ── Référence : un appel seul, sur un agent neuf ────────────────────
        Map<String, List<String>> reference =
                normalise(new CallGraphAgent().buildCallGraph(projet(base, "reference")));

        assertFalse(reference.isEmpty(), "la référence doit contenir des appels — sinon le test ne prouve rien");
        assertEquals(CLASSES * 2, reference.size(),
                "attendu : helperN (appelé par runN et computeN) et computeN (appelé par runN), pour chaque classe");

        // ── Répertoires préparés à l'avance : le temps de création ne doit pas
        //    désynchroniser les threads et relâcher la pression sur le parser ──
        List<Path> projets = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            for (int r = 0; r < ROUNDS; r++) projets.add(projet(base, "t" + t + "_r" + r));
        }

        // ── L'instance PARTAGÉE : seul état mutable commun aux 4 threads ────
        CallGraphAgent agentPartage = new CallGraphAgent();

        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream sortieOriginale = System.out;
        List<Map<String, List<String>>> resultats = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));

            List<Callable<List<Map<String, List<String>>>>> taches = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int thread = t;
                taches.add(() -> {
                    List<Map<String, List<String>>> locaux = new ArrayList<>();
                    for (int r = 0; r < ROUNDS; r++) {
                        Path p = projets.get(thread * ROUNDS + r);
                        locaux.add(normalise(agentPartage.buildCallGraph(p)));
                    }
                    return locaux;
                });
            }

            for (Future<List<Map<String, List<String>>>> f : pool.invokeAll(taches)) {
                // get() relance ce qui a échappé au catch de scanFiles
                resultats.addAll(f.get(120, TimeUnit.SECONDS));
            }
        } finally {
            System.setOut(sortieOriginale);
            pool.shutdownNow();
        }

        String journal = capture.toString(StandardCharsets.UTF_8);

        // ── Symptôme 1 : analyse vide ou incomplète ─────────────────────────
        assertEquals(THREADS * ROUNDS, resultats.size(), "chaque appel doit rendre un graphe");
        for (int i = 0; i < resultats.size(); i++) {
            Map<String, List<String>> obtenu = resultats.get(i);
            assertFalse(obtenu.isEmpty(),
                    "graphe VIDE à l'appel " + i + " — le parser partagé a échoué silencieusement");
            assertEquals(reference, obtenu,
                    "graphe différent de la référence à l'appel " + i
                  + " — analyse incomplète alors que le projet est identique");
        }

        // ── Symptôme 2 : exception avalée par le catch de scanFiles ─────────
        assertFalse(journal.contains("[CallGraph] Erreur"),
                "une exception a été avalée par scanFiles :\n" + extraitsErreur(journal));
        assertFalse(journal.contains("Parse échoué"),
                "un fichier n'a pas pu être parsé :\n" + extraitsErreur(journal));
    }

    /** Ne remonte que les lignes utiles — le journal complet fait des milliers de lignes. */
    private static String extraitsErreur(String journal) {
        List<String> lignes = new ArrayList<>();
        for (String l : journal.split("\\R")) {
            if (l.contains("[CallGraph] Erreur") || l.contains("Parse échoué")) {
                lignes.add("    " + l);
                if (lignes.size() >= 8) { lignes.add("    … (tronqué)"); break; }
            }
        }
        return String.join("\n", lignes);
    }

    @Test
    @DisplayName("un appel isolé produit un graphe complet — garde-fou de la référence")
    void appelIsoleProduitUnGrapheComplet(@TempDir Path base) throws Exception {
        Map<String, List<String>> g = normalise(new CallGraphAgent().buildCallGraph(projet(base, "solo")));

        assertEquals(CLASSES * 2, g.size());
        for (int i = 0; i < CLASSES; i++) {
            assertTrue(g.containsKey("Alpha" + i + ".helper" + i), "helper" + i + " doit avoir des appelants");
            assertEquals(2, g.get("Alpha" + i + ".helper" + i).size(),
                    "helper" + i + " est appelé par run" + i + " et compute" + i);
        }
    }

    /** Garde-fou d'isolation : les répertoires générés sont bien distincts. */
    @Test
    @DisplayName("chaque appel reçoit un répertoire neuf — sinon le cache masquerait la course")
    void repertoiresDistincts(@TempDir Path base) throws Exception {
        Path a = projet(base, "a");
        Path b = projet(base, "b");
        assertNotEquals(a, b);
        assertNotEquals(a.getParent().getParent(), b.getParent().getParent(),
                "les cacheRoot doivent différer, sinon les caches se recouvrent");
    }
}

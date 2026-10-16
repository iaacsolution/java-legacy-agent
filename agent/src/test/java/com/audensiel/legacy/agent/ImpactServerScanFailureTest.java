package com.audensiel.legacy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E8 côté HTTP : un scan échoué n'est plus un 200 « aucun impact ». ImpactServer n'a pas
 * d'authentification (E10) : la réponse ne porte que le NOMBRE de fichiers en cause,
 * jamais leurs chemins (ils vont au journal du serveur).
 */
class ImpactServerScanFailureTest {

    @Test
    @DisplayName("scan échoué → HTTP 500, nombre de fichiers non parsés, aucun chemin")
    void scanEchoue(@TempDir Path base) throws Exception {
        Path src = Files.createDirectories(base.resolve("p/src"));
        Files.writeString(src.resolve("B.java"), "public class B { public void foo() {} }");
        Files.writeString(src.resolve("SecretInterneNonParse.java"), "public class X { ++ erreur");
        Path etat = base.resolve("etat");
        BreakingChangeDetector detector = new BreakingChangeDetector(
                new CallGraphAgent(Map.of(StateDir.ENV, etat.toString())::get));

        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        ImpactServer server = new ImpactServer();
        server.start(src, port, detector);
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/impact"))
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"class_name\":\"B\",\"method_name\":\"foo\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(500, r.statusCode(), r.body());
            assertTrue(r.body().contains("\"error\":\"scan_failed\""), r.body());
            assertTrue(r.body().contains("\"unparsed_files\":1"), r.body());
            assertFalse(r.body().contains("SecretInterneNonParse"), "aucun nom de fichier côté client");
            assertFalse(r.body().contains("severity"), "jamais de verdict sur un scan échoué");
        } finally {
            server.stop();
        }
    }
}

package com.audensiel.legacy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImpactServer, sans authentification : une erreur interne ne renvoie au client qu'un
 * message générique. Le détail (ici, un chemin du serveur) reste dans les journaux.
 */
class ImpactServerErrorTest {

    @Test
    @DisplayName("erreur interne : 500 générique, aucun chemin ni message d'exception renvoyé")
    void erreurGenerique(@TempDir Path base) throws Exception {
        // Le détecteur rattrape lui-même un scan échoué (réponse 200, sévérité NONE — écart
        // documenté dans docs/GOVERNANCE.md) : on simule donc une exception qui lui échappe,
        // avec un message contenant un chemin du serveur.
        Path cheminServeur = base.resolve("chemin-serveur-a-ne-pas-divulguer");
        BreakingChangeDetector enPanne = new BreakingChangeDetector() {
            @Override
            public BreakingChangeReport analyze(Path projectRoot, String className, String methodName) {
                throw new IllegalStateException("échec interne sur " + cheminServeur);
            }
        };
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }

        ImpactServer server = new ImpactServer();
        server.start(cheminServeur, port, enPanne);
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/impact"))
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"class_name\":\"A\",\"method_name\":\"b\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(500, r.statusCode());
            assertEquals(ImpactServer.INTERNAL_ERROR_JSON, r.body());
            assertFalse(r.body().contains("chemin-serveur"), "aucun chemin du serveur côté client");
        } finally {
            server.stop();
        }
    }
}

package com.audensiel.legacy.agent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sérialisation CSV consommée par {@code COPY ... FROM STDIN WITH (FORMAT csv)}.
 *
 * <p>C'est la fonction la plus facile à casser silencieusement du composant : un
 * échappement incorrect ne lève pas d'exception côté Java, il décale les colonnes côté
 * PostgreSQL. Le test d'intégration vérifie le vrai aller-retour ; celui-ci verrouille
 * les règles d'échappement sans base.
 */
class SpanWriterCsvTest {

    @Test
    @DisplayName("un parent_id null devient un champ vide non quoté (NULL SQL)")
    void parentNullEstUnChampVide() {
        String csv = SpanWriter.toCsv(List.of(span(null, "{}")));

        // Un champ vide NON quoté est NULL en CSV PostgreSQL ; "" serait la chaîne vide,
        // que la colonne UUID refuserait. Le contrôle porte donc sur le texte brut :
        // span_id fermant, deux virgules consécutives, run_id ouvrant.
        assertTrue(csv.contains("\",,\""),
                "parent_id absent doit produire un champ vide sans guillemets, pas \"\"");
    }

    @Test
    @DisplayName("les guillemets du JSON sont doublés, virgules et retours à la ligne passent tels quels")
    void echappementDuJson() {
        String json = "{\"class\":\"Client, \\\"Bean\\\"\",\"note\":\"ligne1\\nligne2\"}";
        String csv = SpanWriter.toCsv(List.of(span(UUID.randomUUID(), json)));

        // Règle CSV : à l'intérieur des guillemets, seul le guillemet s'échappe (doublé) ;
        // virgules et retours à la ligne sont littéraux.
        assertTrue(csv.contains("\"\""), "les guillemets internes doivent être doublés");
        assertTrue(csv.endsWith("\n"), "chaque ligne se termine par un saut de ligne");

        List<String> champs = champs(csv.substring(0, csv.length() - 1));
        assertEquals(15, champs.size(), "la ligne doit porter exactement 15 colonnes");
        assertEquals(json, champs.get(14), "le JSON doit se relire à l'identique");
    }

    @Test
    @DisplayName("une virgule dans un attribut ne décale pas les colonnes")
    void virguleNeDecalePasLesColonnes() {
        // Le défaut que cette classe existe pour attraper : un échappement incorrect ne
        // lève rien côté Java, il décale silencieusement les colonnes côté PostgreSQL.
        String json = "{\"deps\":\"a,b,c,d,e\"}";
        String csv = SpanWriter.toCsv(List.of(span(UUID.randomUUID(), json)));

        assertEquals(15, champs(csv.substring(0, csv.length() - 1)).size());
    }

    @Test
    @DisplayName("les nombres sont indépendants de la locale")
    void nombresIndependantsDeLaLocale() {
        // Ce projet a déjà été mordu par une virgule décimale fr_FR qui cassait un export
        // JSON (commit 7a6dae1). En CSV, une virgule décimale décalerait les colonnes.
        SpanRecord r = new SpanRecord(
                SpanId.next().time(), UUID.randomUUID(), null, UUID.randomUUID(), null,
                SpanRecorder.PROJECT, "AstParserAgent", "agent", "ast", "ok",
                1234.5678, 10, 20, new BigDecimal("0.001234"), "{}");

        String csv = SpanWriter.toCsv(List.of(r));

        assertTrue(csv.contains("1234.5678"), "duration_ms doit utiliser un point décimal");
        assertTrue(csv.contains("0.001234"), "cost_usd doit utiliser un point décimal");
        assertFalse(csv.contains("1234,5678"), "aucune virgule décimale ne doit apparaître");
    }

    @Test
    @DisplayName("un lot produit exactement une ligne par span")
    void uneLigneParSpan() {
        String csv = SpanWriter.toCsv(List.of(
                span(UUID.randomUUID(), "{}"),
                span(UUID.randomUUID(), "{}"),
                span(UUID.randomUUID(), "{}")));

        assertEquals(3, csv.lines().count());
    }

    // ── utilitaires ─────────────────────────────────────────────────────────

    private static SpanRecord span(UUID parentId, String attributesJson) {
        SpanId.Stamped s = SpanId.next();
        return new SpanRecord(s.time(), s.id(), parentId, UUID.randomUUID(), null,
                SpanRecorder.PROJECT, "AstParserAgent", "agent", "ast", "ok",
                1.0, 0, 0, BigDecimal.ZERO.setScale(6), attributesJson);
    }

    /**
     * Découpe une ligne CSV en respectant les guillemets — c'est-à-dire en lisant la ligne
     * comme PostgreSQL la lira. Un découpage naïf sur la virgule passerait à côté du défaut
     * que ce test cherche justement à détecter.
     */
    private static List<String> champs(String ligne) {
        List<String> champs = new java.util.ArrayList<>();
        StringBuilder courant = new StringBuilder();
        boolean dansGuillemets = false;

        for (int i = 0; i < ligne.length(); i++) {
            char c = ligne.charAt(i);
            if (dansGuillemets) {
                if (c == '"') {
                    if (i + 1 < ligne.length() && ligne.charAt(i + 1) == '"') {
                        courant.append('"');   // guillemet doublé → un seul guillemet
                        i++;
                    } else {
                        dansGuillemets = false;
                    }
                } else {
                    courant.append(c);
                }
            } else if (c == '"') {
                dansGuillemets = true;
            } else if (c == ',') {
                champs.add(courant.toString());
                courant.setLength(0);
            } else {
                courant.append(c);
            }
        }
        champs.add(courant.toString());
        return champs;
    }
}

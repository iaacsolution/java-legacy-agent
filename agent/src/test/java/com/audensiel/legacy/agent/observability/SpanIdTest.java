package com.audensiel.legacy.agent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariant central du flight recorder : la table porte
 * {@code CHECK (uuid_extract_timestamp(span_id) = time)}. Si l'identifiant et la colonne
 * {@code time} ne sortent pas de la même milliseconde, PostgreSQL rejette la ligne.
 *
 * <p>Le défaut correspondant serait non déterministe — il n'apparaîtrait que lorsque deux
 * lectures d'horloge tombent de part et d'autre d'une frontière de milliseconde. D'où la
 * répétition : elle transforme une course rare en échec reproductible.
 */
class SpanIdTest {

    @RepeatedTest(2000)
    @DisplayName("la date extraite de l'UUID v7 est exactement égale au time du span")
    void dateExtraiteEgaleAuTime() {
        SpanId.Stamped stamped = SpanId.next();

        Instant reextrait = SpanId.timestampOf(stamped.id());

        assertEquals(stamped.time(), reextrait,
                "uuid_extract_timestamp(span_id) doit être égal à time, sinon la contrainte CHECK rejette la ligne");
    }

    @Test
    @DisplayName("time est à la milliseconde près, sans résidu sous-milliseconde")
    void timeEstAlaMilliseconde() {
        // uuid_extract_timestamp ne restitue que des millisecondes : un time porteur de
        // microsecondes ne pourrait jamais lui être égal.
        for (int i = 0; i < 1000; i++) {
            SpanId.Stamped stamped = SpanId.next();
            assertEquals(0, stamped.time().getNano() % 1_000_000,
                    "time ne doit porter aucune fraction inférieure à la milliseconde");
        }
    }

    @Test
    @DisplayName("version 7, variant RFC 4122, et les 12 bits rand_a à zéro")
    void dispositionDesBits() {
        for (int i = 0; i < 1000; i++) {
            UUID id = SpanId.next().id();

            assertEquals(7, id.version(), "la version doit être 7");
            assertEquals(2, id.variant(), "le variant doit être 0b10 (RFC 4122)");
            assertEquals(0, id.getMostSignificantBits() & 0xFFFL,
                    "les 12 bits rand_a qui suivent la version doivent être à zéro");
        }
    }

    @Test
    @DisplayName("le timestamp encodé correspond bien à l'heure courante")
    void timestampPlausible() {
        long avant = System.currentTimeMillis();
        SpanId.Stamped stamped = SpanId.next();
        long apres = System.currentTimeMillis();

        long encode = stamped.time().toEpochMilli();
        assertTrue(encode >= avant && encode <= apres,
                "le timestamp encodé doit tomber dans l'intervalle de l'appel");
    }

    @Test
    @DisplayName("deux spans successifs ont des identifiants distincts")
    void identifiantsDistincts() {
        // Même dans la même milliseconde, les 62 bits rand_b doivent séparer les spans :
        // span_id est clé primaire avec time.
        UUID a = SpanId.next().id();
        UUID b = SpanId.next().id();
        assertNotEquals(a, b);
    }
}

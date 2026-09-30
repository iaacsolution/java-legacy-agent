package com.audensiel.legacy.agent.observability;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Génération d'identifiants UUID version 7 (RFC 9562) pour les spans.
 *
 * <p>La table {@code agent_span} porte la contrainte :
 * <pre>CHECK (uuid_extract_timestamp(span_id) = time)</pre>
 * L'identifiant et la colonne {@code time} doivent donc être dérivés de la
 * <strong>même</strong> milliseconde. C'est la raison d'être de {@link #next()} :
 * une <strong>lecture d'horloge unique</strong> produit les deux valeurs à la fois.
 * Lire l'horloge deux fois (une pour l'UUID, une pour {@code time}) fait échouer
 * la contrainte dès que les deux lectures tombent de part et d'autre d'une
 * frontière de milliseconde — un défaut rare, non déterministe et pénible à
 * diagnostiquer, d'où l'API qui rend l'erreur impossible à commettre.
 *
 * <p>Disposition des 128 bits :
 * <pre>
 *   bits 127..80 : timestamp Unix en millisecondes (48 bits)
 *   bits  79..76 : version = 7
 *   bits  75..64 : rand_a  = 0    (mis à zéro comme demandé)
 *   bits  63..62 : variant = 0b10 (RFC 4122/9562)
 *   bits  61..0  : rand_b  = aléatoire (62 bits)
 * </pre>
 */
public final class SpanId {

    private SpanId() {}

    /** Un identifiant de span et l'instant dont il est issu — la même milliseconde. */
    public record Stamped(UUID id, Instant time) {}

    /**
     * Produit un UUID v7 et l'instant correspondant à partir d'une seule lecture d'horloge.
     */
    public static Stamped next() {
        long ts = System.currentTimeMillis();   // ← lecture UNIQUE, ne jamais en ajouter une seconde

        long msb = (ts << 16) | (0x7L << 12);   // timestamp + version 7, rand_a laissé à zéro
        long lsb = (ThreadLocalRandom.current().nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
                 | 0x8000_0000_0000_0000L;      // variant 0b10

        return new Stamped(new UUID(msb, lsb), Instant.ofEpochMilli(ts));
    }

    /**
     * Ré-extrait l'instant encodé dans un UUID v7 — équivalent Java de la fonction
     * PostgreSQL 18 {@code uuid_extract_timestamp}, utilisé par les tests pour
     * vérifier l'invariant sans base de données.
     */
    public static Instant timestampOf(UUID id) {
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}

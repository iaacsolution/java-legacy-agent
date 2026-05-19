package com.audensiel.legacy.agent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Logue chaque blocage dans impact-roi.csv pour le rapport ROI hebdo.
 * Écrit depuis Java (Docker Linux) — aucun problème CRLF Windows.
 *
 * Format : timestamp,class,method,severity,callers_count,callers,blocked,strategy,duration_ms
 */
public class RoiLogger {

    private static final String CSV_HEADER =
            "timestamp,class,method,severity,callers_count,callers,blocked,strategy,duration_ms\n";

    public static void log(Path projectRoot, BreakingChangeDetector.BreakingChangeReport report) {
        Path csv = projectRoot.resolve("impact-roi.csv");
        try {
            if (!Files.exists(csv)) {
                Files.writeString(csv, CSV_HEADER, StandardOpenOption.CREATE);
            }

            String callersList = String.join("|", report.callers());
            String line = String.join(",",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                    report.className(),
                    report.methodName(),
                    report.severity().name(),
                    String.valueOf(report.callers().size()),
                    "\"" + callersList + "\"",
                    String.valueOf(report.isBreaking()),
                    report.refactoring().strategy(),
                    String.valueOf(report.durationMs())
            ) + "\n";

            Files.writeString(csv, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            System.out.println("[ROI] Loggué dans " + csv);
        } catch (IOException e) {
            System.out.println("[ROI] Erreur écriture CSV : " + e.getMessage());
        }
    }
}

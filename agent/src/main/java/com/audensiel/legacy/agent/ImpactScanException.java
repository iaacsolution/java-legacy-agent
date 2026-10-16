package com.audensiel.legacy.agent;

import java.util.List;

/**
 * L'analyse d'impact n'a pas pu se faire : jamais un « aucun impact » (écart E8,
 * docs/GOVERNANCE.md). Projet absent ou illisible, dossier d'état refusé, fichier non
 * parsé, liste d'exclusions invalide.
 *
 * <p>{@code unparsedFiles} : chemins RELATIFS au projet analysé, jamais leur contenu. Ils
 * vont à la CLI et aux journaux ; ImpactServer, sans authentification, n'en renvoie que le
 * nombre.
 */
public final class ImpactScanException extends RuntimeException {

    private final List<String> unparsedFiles;
    private final int excludedFiles;

    public ImpactScanException(String reason, List<String> unparsedFiles, int excludedFiles, Throwable cause) {
        super(reason, cause);
        this.unparsedFiles = List.copyOf(unparsedFiles);
        this.excludedFiles = excludedFiles;
    }

    public ImpactScanException(String reason) {
        this(reason, List.of(), 0, null);
    }

    public List<String> unparsedFiles() { return unparsedFiles; }
    public int excludedFiles() { return excludedFiles; }

    /** Sortie JSON de la CLI en cas d'échec. Les chaînes sont échappées. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"error\": \"scan_failed\",\n");
        sb.append("  \"reason\": \"").append(esc(getMessage())).append("\",\n");
        sb.append("  \"unparsed_files\": [");
        for (int i = 0; i < unparsedFiles.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(esc(unparsedFiles.get(i))).append('"');
        }
        sb.append("],\n");
        sb.append("  \"excluded_files\": ").append(excludedFiles).append("\n}");
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}

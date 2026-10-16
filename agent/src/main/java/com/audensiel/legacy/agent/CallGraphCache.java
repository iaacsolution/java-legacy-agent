package com.audensiel.legacy.agent;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cache JSON du call graph basé sur MD5.
 * Avant de parser un fichier, compare son MD5 actuel avec le cache.
 * Seuls les fichiers dont le contenu a changé sont re-scannés.
 * Plus fiable que les timestamps (git checkout, copies, etc. ne déclenchent pas de faux positifs).
 *
 * Format .callgraph-cache.json :
 * {
 *   "version": 3,
 *   "hashes": { "chemin/relatif/au/projet/File.java": "d41d8cd98f00b204e9800998ecf8427e" },
 *   "callers": { "Class.method": ["Caller.m:12"] }
 * }
 */
public class CallGraphCache {

    private static final String CACHE_FILE = ".callgraph-cache.json";

    public record CacheEntry(
            Map<String, String> fileHashes,         // path → MD5 hex
            Map<String, List<String>> callers
    ) {}

    // ── Emplacement et clés (Lot 3) ───────────────────────────────────────────

    /**
     * Fichier de cache propre AU PROJET analysé, sous le dossier d'état. Avant le Lot 3, un
     * seul fichier par grand-parent : deux projets voisins partageaient leur graphe.
     */
    public static Path cacheFileFor(Path stateDir, Path projectRoot) {
        String key = projectRoot.toAbsolutePath().normalize().toString();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return stateDir.resolve("callgraph").resolve(java.util.HexFormat.of().formatHex(d, 0, 8) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * MD5 du contenu de chaque fichier, clé = chemin RELATIF au projet, séparateur « / ».
     * Avant le Lot 3 : chemin absolu, dont les antislashs Windows étaient doublés à
     * l'écriture et jamais rétablis à la lecture — sous Windows, le cache n'était donc
     * jamais valide.
     */
    public static Map<String, String> hashesOf(Path projectRoot, List<Path> files) {
        Map<String, String> hashes = new TreeMap<>();
        for (Path f : files) {
            hashes.put(projectRoot.relativize(f).toString().replace('\\', '/'), md5(f));
        }
        return hashes;
    }

    public static Optional<CacheEntry> loadFile(Path cacheFile) {
        if (!Files.exists(cacheFile)) return Optional.empty();
        try {
            String json = Files.readString(cacheFile);
            Map<String, String> hashes  = parseHashes(json);
            Map<String, List<String>> callers = parseCallers(json);
            System.out.println("[Cache] Chargé : " + hashes.size() + " fichiers (MD5), "
                    + callers.size() + " méthodes");
            return Optional.of(new CacheEntry(hashes, callers));
        } catch (Exception e) {
            System.out.println("[Cache] Illisible — scan complet : " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public static void saveFile(Path cacheFile, Map<String, String> hashes, Map<String, List<String>> callers) {
        try {
            Files.createDirectories(cacheFile.getParent());
        } catch (IOException e) {
            System.out.println("[Cache] ERREUR création du dossier d'état : " + e.getMessage());
            return;
        }
        writeCache(cacheFile, hashes, callers);
    }

    // ── API historique (emplacement d'avant le Lot 3) — plus utilisée par CallGraphAgent ──

    /** @deprecated emplacement d'avant le Lot 3 (dossier fourni) ; utiliser {@link #loadFile}. */
    @Deprecated
    public static Optional<CacheEntry> load(Path projectRoot) {
        Path cacheFile = projectRoot.resolve(CACHE_FILE);
        if (!Files.exists(cacheFile)) return Optional.empty();
        try {
            String json = Files.readString(cacheFile);
            Map<String, String> hashes  = parseHashes(json);
            Map<String, List<String>> callers = parseCallers(json);
            System.out.println("[Cache] Chargé : " + hashes.size() + " fichiers (MD5), "
                    + callers.size() + " méthodes");
            return Optional.of(new CacheEntry(hashes, callers));
        } catch (Exception e) {
            System.out.println("[Cache] Illisible — scan complet : " + e.getMessage());
            return Optional.empty();
        }
    }

    /** @deprecated emplacement d'avant le Lot 3 (dossier fourni) ; utiliser {@link #saveFile}. */
    @Deprecated
    public static void save(Path projectRoot, Map<String, String> hashes,
                            Map<String, List<String>> callers) {
        writeCache(projectRoot.resolve(CACHE_FILE), hashes, callers);
    }

    private static void writeCache(Path cacheFile, Map<String, String> hashes,
                                   Map<String, List<String>> callers) {
        System.out.println("[Cache] Sauvegarde → " + cacheFile);
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"version\": 3,\n");

            json.append("  \"hashes\": {\n");
            List<String> hEntries = hashes.entrySet().stream()
                    .map(e -> "    \"" + esc(e.getKey()) + "\": \"" + e.getValue() + "\"")
                    .collect(Collectors.toList());
            json.append(String.join(",\n", hEntries)).append("\n  },\n");

            json.append("  \"callers\": {\n");
            List<String> cEntries = callers.entrySet().stream()
                    .map(e -> {
                        String vals = e.getValue().stream()
                                .map(v -> "\"" + esc(v) + "\"")
                                .collect(Collectors.joining(", "));
                        return "    \"" + esc(e.getKey()) + "\": [" + vals + "]";
                    })
                    .collect(Collectors.toList());
            json.append(String.join(",\n", cEntries)).append("\n  }\n}");

            Files.writeString(cacheFile, json.toString());
            System.out.println("[Cache] Sauvegardé — " + hashes.size() + " fichiers (MD5)");
        } catch (IOException e) {
            System.out.println("[Cache] ERREUR sauvegarde : " + e.getMessage());
        }
    }

    /** Retourne les fichiers dont le MD5 diffère du cache — contenu réellement modifié. */
    public static List<Path> staleFiles(Map<String, String> cachedHashes, List<Path> currentFiles) {
        return currentFiles.stream().filter(f -> {
            String cached  = cachedHashes.get(f.toString());
            String current = md5(f);
            boolean stale  = cached == null || !cached.equals(current);
            if (!stale) {
                // Contenu identique — pas besoin de re-parser
            }
            return stale;
        }).collect(Collectors.toList());
    }

    /** Calcule le MD5 hex d'un fichier. Retourne "" en cas d'erreur. */
    public static String md5(Path file) {
        try {
            byte[] bytes  = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash   = md.digest(bytes);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ── Parsing JSON minimaliste ──────────────────────────────────────────────

    private static Map<String, String> parseHashes(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int start = json.indexOf("\"hashes\"");
        int end   = json.indexOf("\"callers\"");
        if (start < 0 || end < 0) return result;
        String block = json.substring(start, end);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([^\"]+)\":\\s*\"([a-f0-9]{32})\"").matcher(block);
        while (m.find()) result.put(m.group(1), m.group(2));
        return result;
    }

    private static Map<String, List<String>> parseCallers(String json) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int start = json.indexOf("\"callers\"");
        if (start < 0) return result;
        String block = json.substring(start);
        java.util.regex.Matcher keyM = java.util.regex.Pattern
                .compile("\"([\\w.:<>]+)\":\\s*\\[([^\\]]*)]").matcher(block);
        while (keyM.find()) {
            String key = keyM.group(1);
            List<String> vals = new ArrayList<>();
            java.util.regex.Matcher valM = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"").matcher(keyM.group(2));
            while (valM.find()) vals.add(valM.group(1));
            result.put(key, vals);
        }
        return result;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

package com.audensiel.legacy.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Liste d'exclusions de l'analyse d'impact : fichiers que le parseur ne sait pas lire, écartés
 * VOLONTAIREMENT, un par un, avec une justification. Désignée par {@code IMPACT_EXCLUSIONS_FILE} ;
 * sans cette variable, aucune exclusion.
 *
 * <p>Format, une entrée par ligne ({@code #} = commentaire) :
 * <pre>chemin/relatif/au/projet/Fichier.java | justification</pre>
 *
 * <p>Échec fermé ({@link ImpactScanException}) si la liste est invalide : fichier désigné mais
 * absent, entrée sans justification, chemin absolu, segment {@code ..} (une entrée reste
 * toujours À L'INTÉRIEUR du projet analysé), fichier exclu qui n'existe plus (la liste doit
 * rester exacte, comme l'allowlist d'AccessAllowlistTest).
 *
 * <p>Un fichier exclu n'est pas analysé : ses appels sont ignorés. C'est un risque accepté,
 * nommé et justifié ; l'analyse affiche toujours leur nombre.
 */
public final class ImpactExclusions {

    public static final String ENV = "IMPACT_EXCLUSIONS_FILE";

    /** Fichiers exclus : chemins relatifs au projet, séparateur « / ». */
    public record Exclusions(Set<String> paths) {
        public int count() { return paths.size(); }
    }

    private ImpactExclusions() {}

    public static Exclusions load(Path projectRoot, Function<String, String> env) {
        String configured = env.apply(ENV);
        if (configured == null || configured.isBlank()) return new Exclusions(Set.of());

        Path liste = Path.of(configured);
        List<String> lignes;
        try {
            lignes = Files.readAllLines(liste, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ImpactScanException(ENV + " désigne une liste illisible ou absente : " + liste);
        }

        Path racine = projectRoot.toAbsolutePath().normalize();
        Set<String> paths = new TreeSet<>();
        for (int n = 0; n < lignes.size(); n++) {
            String ligne = lignes.get(n).strip();
            if (ligne.isEmpty() || ligne.startsWith("#")) continue;
            String ou = "liste d'exclusions, ligne " + (n + 1);

            int sep = ligne.indexOf('|');
            String chemin = (sep < 0 ? ligne : ligne.substring(0, sep)).strip();
            String justification = sep < 0 ? "" : ligne.substring(sep + 1).strip();
            if (justification.isEmpty()) {
                throw new ImpactScanException(ou + " : entrée sans justification (« chemin | justification »)");
            }

            Path p;
            try {
                p = Path.of(chemin);
            } catch (InvalidPathException e) {
                throw new ImpactScanException(ou + " : chemin invalide");
            }
            if (p.isAbsolute() || chemin.startsWith("/") || chemin.startsWith("\\")) {
                throw new ImpactScanException(ou + " : chemin absolu refusé, attendu relatif au projet");
            }
            for (Path segment : p) {
                if (segment.toString().equals("..")) {
                    throw new ImpactScanException(ou + " : « .. » refusé — une entrée hors du projet est interdite,"
                            + " elle doit rester à l'intérieur du projet analysé");
                }
            }
            Path cible = racine.resolve(p).normalize();
            if (!cible.startsWith(racine)) {
                throw new ImpactScanException(ou + " : entrée hors du projet analysé");
            }
            if (!Files.isRegularFile(cible)) {
                throw new ImpactScanException(ou + " : entrée périmée, fichier absent du projet");
            }
            paths.add(racine.relativize(cible).toString().replace('\\', '/'));
        }
        return new Exclusions(Set.copyOf(paths));
    }
}

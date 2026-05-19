package com.audensiel.legacy.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Valide la qualité des outputs LLM avant de les intégrer au rapport.
 * Évite d'exporter un DAT vide ou tronqué.
 */
public class OutputValidator {

    private static final Logger log = LoggerFactory.getLogger(OutputValidator.class);

    private static final int MIN_LENGTH_ANALYSIS  = 200;
    private static final int MIN_LENGTH_DAT        = 500;
    private static final int MIN_LENGTH_MIGRATION  = 300;

    public record ValidationResult(boolean valid, List<String> warnings) {
        public static ValidationResult ok()                     { return new ValidationResult(true, List.of()); }
        public static ValidationResult warn(List<String> w)    { return new ValidationResult(true, w); }
        public static ValidationResult fail(List<String> w)    { return new ValidationResult(false, w); }
    }

    public ValidationResult validateAnalysis(String className, String output) {
        List<String> warnings = new ArrayList<>();

        if (output == null || output.isBlank()) {
            log.warn("Output vide pour l'analyse de {}", className);
            return ValidationResult.fail(List.of("Output vide"));
        }
        if (output.length() < MIN_LENGTH_ANALYSIS) {
            warnings.add("Output trop court (" + output.length() + " chars) pour " + className);
        }
        if (!output.contains("##") && !output.contains("**")) {
            warnings.add("Pas de structure Markdown détectée pour " + className);
        }

        if (!warnings.isEmpty()) log.warn("Validation warnings pour {} : {}", className, warnings);
        return warnings.isEmpty() ? ValidationResult.ok() : ValidationResult.warn(warnings);
    }

    public ValidationResult validateDAT(String output) {
        List<String> warnings = new ArrayList<>();

        if (output == null || output.isBlank())
            return ValidationResult.fail(List.of("DAT vide"));
        if (output.length() < MIN_LENGTH_DAT)
            warnings.add("DAT trop court : " + output.length() + " chars");

        String lower = output.toLowerCase();
        if (!lower.contains("architecture") && !lower.contains("module"))
            warnings.add("DAT ne mentionne pas l'architecture ni le module");
        if (!lower.contains("recommand") && !lower.contains("migrat"))
            warnings.add("DAT ne contient pas de recommandations");

        if (!warnings.isEmpty()) log.warn("Validation DAT : {}", warnings);
        return warnings.isEmpty() ? ValidationResult.ok() : ValidationResult.warn(warnings);
    }

    public ValidationResult validateMigrationPlan(String output) {
        List<String> warnings = new ArrayList<>();

        if (output == null || output.isBlank())
            return ValidationResult.fail(List.of("Plan de migration vide"));
        if (output.length() < MIN_LENGTH_MIGRATION)
            warnings.add("Plan trop court : " + output.length() + " chars");

        String lower = output.toLowerCase();
        if (!lower.contains("priorit") && !lower.contains("étape") && !lower.contains("roadmap"))
            warnings.add("Plan ne contient pas de priorisation");

        if (!warnings.isEmpty()) log.warn("Validation plan migration : {}", warnings);
        return warnings.isEmpty() ? ValidationResult.ok() : ValidationResult.warn(warnings);
    }

    public String fallback(String context) {
        log.error("Fallback activé pour : {}", context);
        return "_[Génération échouée pour " + context + " — relancer l'agent sur ce fichier]_";
    }
}

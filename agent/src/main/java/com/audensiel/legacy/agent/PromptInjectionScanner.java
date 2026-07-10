package com.audensiel.legacy.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Détection best-effort de patterns d'injection de prompt dans du code source, avant
 * envoi à un LLM. Défense en profondeur — pas un filtre fiable à 100% (un attaquant
 * motivé peut l'éviter). Le vrai confinement reste la séparation instruction/données
 * dans les prompts (JavaDocumentationAgent, MigrationPlannerAgent) et l'absence
 * d'auto-exécution de la sortie du LLM par le pipeline.
 */
public class PromptInjectionScanner {

    private static final List<Pattern> SUSPICIOUS_PATTERNS = List.of(
            Pattern.compile("ignor(e|ez|e[sz]?).{0,25}(instruction|consigne)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard.{0,25}(previous|above|prior)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsystem\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byou are now\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("nouvelle[s]?\\s+instruction", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnew instructions?\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[/?INST\\]"),
            Pattern.compile("<\\|(system|im_start|im_end)\\|>")
    );

    public record ScanResult(boolean suspicious, List<String> matchedPatterns) {
        public static final ScanResult CLEAN = new ScanResult(false, List.of());
    }

    private PromptInjectionScanner() {}

    public static ScanResult scan(String content) {
        if (content == null || content.isBlank()) return ScanResult.CLEAN;

        List<String> matches = new ArrayList<>();
        for (Pattern p : SUSPICIOUS_PATTERNS) {
            if (p.matcher(content).find()) {
                matches.add(p.pattern());
            }
        }
        return matches.isEmpty() ? ScanResult.CLEAN : new ScanResult(true, matches);
    }
}

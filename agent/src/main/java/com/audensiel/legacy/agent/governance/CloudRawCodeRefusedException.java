package com.audensiel.legacy.agent.governance;

/**
 * Levée quand du code source brut devrait partir vers un backend cloud sans le drapeau
 * explicite {@code ALLOW_CLOUD_RAW_CODE=true}.
 *
 * <p>Refus bruyant par construction : jamais de repli silencieux sur une analyse vide —
 * c'est la classe de bug déjà rencontrée avec JavaParser partagé entre workers (analyse
 * vide, pipeline en succès).
 */
public final class CloudRawCodeRefusedException extends IllegalStateException {

    public static final String FLAG = "ALLOW_CLOUD_RAW_CODE";

    public CloudRawCodeRefusedException() {
        super("Envoi du code source brut vers le backend cloud refusé : drapeau " + FLAG
                + "=true absent. Utiliser un backend on-prem (vLLM/Ollama), l'analyse par squelette AST, "
                + "ou positionner " + FLAG + "=true en connaissance de cause (code synthétique uniquement).");
    }
}

package com.audensiel.legacy.agent;

import java.util.List;

/**
 * Génère des suggestions de refactoring sans LLM (règles statiques).
 * Garanti < 5ms — basé uniquement sur le nom de classe, méthode et sévérité.
 */
public class RefactoringAdvisor {

    public record RefactoringPlan(
            String strategy,
            String immediateAction,
            String shortTermAction,
            String codeTemplate
    ) {}

    public RefactoringPlan suggest(String className, String methodName,
                                   List<String> callers,
                                   BreakingChangeDetector.Severity severity) {
        return switch (severity) {
            case NONE -> new RefactoringPlan(
                    "SAFE",
                    "Aucune action requise.",
                    "Aucune action requise.",
                    ""
            );
            case LOW -> new RefactoringPlan(
                    "DIRECT_UPDATE",
                    "Mettre à jour les " + callers.size() + " appelant(s) directement.",
                    "Ouvrir un ticket de refactoring mineur.",
                    callerUpdateSnippet(className, methodName, callers)
            );
            case MEDIUM -> new RefactoringPlan(
                    "DEPRECATE_AND_OVERLOAD",
                    "Annoter l'ancienne signature @Deprecated + créer la surcharge.",
                    "Migrer les " + callers.size() + " appelants sur 1 sprint.",
                    deprecateSnippet(className, methodName)
            );
            case HIGH -> new RefactoringPlan(
                    "EXTRACT_INTERFACE",
                    "Créer une interface de transition + adapter pattern.",
                    "Planifier migration sur 2-3 sprints avec feature flag.",
                    interfaceSnippet(className, methodName, callers.size())
            );
            case CRITICAL -> new RefactoringPlan(
                    "VERSIONED_API",
                    "BLOQUER le merge — soumettre au comité d'architecture.",
                    "Créer v2 de la méthode + roadmap de migration formelle.",
                    versionedApiSnippet(className, methodName)
            );
        };
    }

    // ── Templates de code ─────────────────────────────────────────────────────

    private String deprecateSnippet(String cls, String method) {
        return """
                // Étape 1 — garder l'ancienne signature opérationnelle
                @Deprecated(since = "2.0", forRemoval = true)
                public String %s(String arg) {
                    return %s(arg, ""); // délègue à la nouvelle
                }

                // Étape 2 — nouvelle signature
                public String %s(String arg, String context) {
                    // nouvelle implémentation
                }
                """.formatted(method, method, method);
    }

    private String callerUpdateSnippet(String cls, String method, List<String> callers) {
        StringBuilder sb = new StringBuilder("// Fichiers à mettre à jour :\n");
        callers.forEach(c -> sb.append("// → ").append(c).append("\n"));
        sb.append("// Remplacer : ").append(method).append("(arg)\n");
        sb.append("// Par       : ").append(method).append("(arg, \"\")");
        return sb.toString();
    }

    private String interfaceSnippet(String cls, String method, int callerCount) {
        return """
                // Adapter pattern — %d sites à migrer progressivement
                public interface %sPort {
                    String %s(String arg, String context); // v2
                }

                // Adapter legacy
                public class %sAdapter implements %sPort {
                    private final %s delegate;
                    @Override
                    public String %s(String arg, String context) {
                        return delegate.%s(arg); // bridge v1→v2
                    }
                }
                """.formatted(callerCount, cls, method, cls, cls, cls, method, method);
    }

    private String versionedApiSnippet(String cls, String method) {
        return """
                // API versionnée — migration sans rupture
                /** @deprecated Utiliser %sV2() — suppression prévue en v3.0 */
                @Deprecated(since = "2.0", forRemoval = true)
                public String %s(String arg) { return %sV2(arg, "", false); }

                // Nouvelle API stable
                public String %sV2(String arg, String context, boolean strict) {
                    // implémentation v2
                }
                """.formatted(method, method, method, method);
    }
}

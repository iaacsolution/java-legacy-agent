package com.audensiel.legacy.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Remplace DependencyMapperAgent (regex fragile) par une analyse syntaxique complète via JavaParser.
 * Extrait imports, hiérarchie, méthodes et complexité cyclomatique depuis l'AST réel.
 */
public class AstParserAgent {

    public record MethodSignature(String name, String returnType, List<String> paramTypes) {
        @Override
        public String toString() {
            return returnType + " " + name + "(" + String.join(", ", paramTypes) + ")";
        }
    }

    /** Seuils déterminant si une méthode mérite un appel LLM dédié plutôt qu'une simple ligne de signature. */
    private static final int HEAVY_METHOD_CC_THRESHOLD  = 3;
    private static final int HEAVY_METHOD_LOC_THRESHOLD = 20;

    /** Détail d'une méthode — corps inclus uniquement pour les méthodes jugées "lourdes". */
    public record MethodDetail(
            String name,
            MethodSignature signature,
            String body,
            int cyclomaticComplexity,
            int lineCount
    ) {
        public boolean isHeavy() {
            return cyclomaticComplexity > HEAVY_METHOD_CC_THRESHOLD || lineCount > HEAVY_METHOD_LOC_THRESHOLD;
        }
    }

    public record AstAnalysis(
            String className,
            List<String> imports,
            String superClass,
            List<String> interfaces,
            List<String> fieldTypes,
            List<MethodSignature> methods,
            int cyclomaticComplexity,
            List<String> annotations,
            List<MethodDetail> methodDetails
    ) {
        /** Méthodes dont le corps mérite une analyse LLM dédiée (logique non triviale). */
        public List<MethodDetail> heavyMethods() {
            return methodDetails.stream().filter(MethodDetail::isHeavy).collect(Collectors.toList());
        }

        /** Méthodes triviales (getters/setters, corps courts) — couvertes par la seule signature. */
        public List<MethodDetail> trivialMethods() {
            return methodDetails.stream().filter(m -> !m.isHeavy()).collect(Collectors.toList());
        }
        /** Contexte structuré injecté dans le prompt LLM — remplace le code brut seul */
        public String toPromptContext() {
            StringBuilder sb = new StringBuilder();
            sb.append("### Analyse AST — ").append(className).append("\n");
            if (superClass != null)
                sb.append("- **Hérite de** : ").append(superClass).append("\n");
            if (!interfaces.isEmpty())
                sb.append("- **Implémente** : ").append(String.join(", ", interfaces)).append("\n");
            if (!annotations.isEmpty())
                sb.append("- **Annotations** : ").append(String.join(", ", annotations)).append("\n");
            if (!fieldTypes.isEmpty())
                sb.append("- **Champs** : ").append(String.join(", ", fieldTypes)).append("\n");
            if (!methods.isEmpty()) {
                sb.append("- **Méthodes** :\n");
                methods.forEach(m -> sb.append("  - `").append(m).append("`\n"));
            }
            sb.append("- **Complexité cyclomatique** : ").append(cyclomaticComplexity).append("\n");
            if (!imports.isEmpty()) {
                int cap = Math.min(5, imports.size());
                sb.append("- **Imports** : ").append(String.join(", ", imports.subList(0, cap)));
                if (imports.size() > cap) sb.append(" (+ ").append(imports.size() - cap).append(" autres)");
                sb.append("\n");
            }
            return sb.toString();
        }

        /**
         * Variante du squelette pour envoi à un backend cloud — jamais de corps de méthode
         * (déjà le cas ici) et packages internes anonymisés (les noms de classes/méthodes restent
         * en clair, nécessaires à un rapport de migration exploitable ; ce sont les chemins de
         * package — souvent révélateurs du nom de domaine/produit client — qui sont masqués).
         * Les packages java, javax et jakarta sont laissés tels quels (API publique, non sensible).
         */
        public String toAnonymizedPromptContext() {
            StringBuilder sb = new StringBuilder();
            sb.append("### Analyse AST — ").append(className).append("\n");
            if (superClass != null)
                sb.append("- **Hérite de** : ").append(anonymizeType(superClass)).append("\n");
            if (!interfaces.isEmpty())
                sb.append("- **Implémente** : ").append(interfaces.stream()
                        .map(AstAnalysis::anonymizeType).collect(Collectors.joining(", "))).append("\n");
            if (!annotations.isEmpty())
                sb.append("- **Annotations** : ").append(String.join(", ", annotations)).append("\n");
            if (!fieldTypes.isEmpty())
                sb.append("- **Champs** : ").append(fieldTypes.stream()
                        .map(AstAnalysis::anonymizeType).collect(Collectors.joining(", "))).append("\n");
            if (!methods.isEmpty()) {
                sb.append("- **Méthodes** :\n");
                methods.forEach(m -> sb.append("  - `")
                        .append(anonymizeType(m.returnType())).append(" ").append(m.name())
                        .append("(").append(m.paramTypes().stream()
                                .map(AstAnalysis::anonymizeType).collect(Collectors.joining(", ")))
                        .append(")`\n"));
            }
            sb.append("- **Complexité cyclomatique** : ").append(cyclomaticComplexity).append("\n");
            if (!imports.isEmpty()) {
                List<String> anonImports = imports.stream().map(AstAnalysis::anonymizeType).distinct()
                        .collect(Collectors.toList());
                int cap = Math.min(5, anonImports.size());
                sb.append("- **Imports** : ").append(String.join(", ", anonImports.subList(0, cap)));
                if (anonImports.size() > cap) sb.append(" (+ ").append(anonImports.size() - cap).append(" autres)");
                sb.append("\n");
            }
            return sb.toString();
        }

        /** Masque le chemin de package d'un type qualifié interne ; laisse java/javax/jakarta et les noms simples intacts. */
        private static String anonymizeType(String type) {
            if (type == null || !type.contains(".")) return type;
            if (type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jakarta.")) return type;
            String simple = type.substring(type.lastIndexOf('.') + 1);
            return "internal." + simple;
        }
    }

    private final JavaParser parser = new JavaParser(
        new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
    );

    public AstAnalysis analyze(FileScannerAgent.JavaFile file) {
        try {
            var result = parser.parse(file.content());
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return fromCu(file.className(), result.getResult().get());
            }
        } catch (Exception ignored) {
            // Code partiel ou invalide — fallback silencieux
        }
        return empty(file.className());
    }

    private AstAnalysis fromCu(String className, CompilationUnit cu) {
        List<String> imports = cu.getImports().stream()
                .map(i -> i.getNameAsString())
                .collect(Collectors.toList());

        Optional<ClassOrInterfaceDeclaration> classOpt = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (classOpt.isEmpty()) {
            return new AstAnalysis(className, imports, null, List.of(), List.of(), List.of(), 0, List.of(), List.of());
        }

        ClassOrInterfaceDeclaration clazz = classOpt.get();

        String superClass = clazz.getExtendedTypes().isEmpty() ? null
                : clazz.getExtendedTypes().get(0).getNameAsString();

        List<String> interfaces = clazz.getImplementedTypes().stream()
                .map(t -> t.getNameAsString())
                .collect(Collectors.toList());

        List<String> fieldTypes = clazz.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getTypeAsString())
                .distinct()
                .collect(Collectors.toList());

        List<MethodSignature> methods = clazz.getMethods().stream()
                .map(m -> new MethodSignature(
                        m.getNameAsString(),
                        m.getTypeAsString(),
                        m.getParameters().stream().map(p -> p.getTypeAsString()).collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        List<String> annotations = clazz.getAnnotations().stream()
                .map(a -> "@" + a.getNameAsString())
                .collect(Collectors.toList());

        List<MethodDetail> methodDetails = clazz.getMethods().stream()
                .map(this::toMethodDetail)
                .collect(Collectors.toList());

        return new AstAnalysis(className, imports, superClass, interfaces,
                fieldTypes, methods, cyclomaticComplexity(clazz), annotations, methodDetails);
    }

    private MethodDetail toMethodDetail(MethodDeclaration m) {
        MethodSignature sig = new MethodSignature(
                m.getNameAsString(),
                m.getTypeAsString(),
                m.getParameters().stream().map(p -> p.getTypeAsString()).collect(Collectors.toList())
        );
        String source = m.toString();
        int lineCount = (int) source.lines().count();
        return new MethodDetail(m.getNameAsString(), sig, source, cyclomaticComplexity(m), lineCount);
    }

    /** CC = 1 + points de décision : if, for, while, catch, switch, ternaire, && / || — applicable à une classe ou une méthode. */
    private int cyclomaticComplexity(Node node) {
        int cc = 1;
        cc += node.findAll(IfStmt.class).size();
        cc += node.findAll(ForStmt.class).size();
        cc += node.findAll(ForEachStmt.class).size();
        cc += node.findAll(WhileStmt.class).size();
        cc += node.findAll(CatchClause.class).size();
        cc += node.findAll(SwitchEntry.class).size();
        cc += node.findAll(ConditionalExpr.class).size();
        cc += node.findAll(BinaryExpr.class).stream()
                .filter(b -> b.getOperator() == BinaryExpr.Operator.AND
                          || b.getOperator() == BinaryExpr.Operator.OR)
                .count();
        return cc;
    }

    private AstAnalysis empty(String className) {
        return new AstAnalysis(className, List.of(), null, List.of(), List.of(), List.of(), 0, List.of(), List.of());
    }

    public String buildDependencyReport(List<AstAnalysis> analyses) {
        StringBuilder sb = new StringBuilder("## Carte des dépendances (AST)\n\n");
        analyses.forEach(a -> sb.append(a.toPromptContext()).append("\n"));
        return sb.toString();
    }
}

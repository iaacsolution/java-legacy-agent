package com.audensiel.legacy.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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

    public record AstAnalysis(
            String className,
            List<String> imports,
            String superClass,
            List<String> interfaces,
            List<String> fieldTypes,
            List<MethodSignature> methods,
            int cyclomaticComplexity,
            List<String> annotations
    ) {
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
            return new AstAnalysis(className, imports, null, List.of(), List.of(), List.of(), 0, List.of());
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

        return new AstAnalysis(className, imports, superClass, interfaces,
                fieldTypes, methods, cyclomaticComplexity(clazz), annotations);
    }

    /** CC = 1 + points de décision : if, for, while, catch, switch, ternaire, && / || */
    private int cyclomaticComplexity(ClassOrInterfaceDeclaration clazz) {
        int cc = 1;
        cc += clazz.findAll(IfStmt.class).size();
        cc += clazz.findAll(ForStmt.class).size();
        cc += clazz.findAll(ForEachStmt.class).size();
        cc += clazz.findAll(WhileStmt.class).size();
        cc += clazz.findAll(CatchClause.class).size();
        cc += clazz.findAll(SwitchEntry.class).size();
        cc += clazz.findAll(ConditionalExpr.class).size();
        cc += clazz.findAll(BinaryExpr.class).stream()
                .filter(b -> b.getOperator() == BinaryExpr.Operator.AND
                          || b.getOperator() == BinaryExpr.Operator.OR)
                .count();
        return cc;
    }

    private AstAnalysis empty(String className) {
        return new AstAnalysis(className, List.of(), null, List.of(), List.of(), List.of(), 0, List.of());
    }

    public String buildDependencyReport(List<AstAnalysis> analyses) {
        StringBuilder sb = new StringBuilder("## Carte des dépendances (AST)\n\n");
        analyses.forEach(a -> sb.append(a.toPromptContext()).append("\n"));
        return sb.toString();
    }
}

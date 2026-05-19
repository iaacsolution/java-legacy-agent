package com.audensiel.legacy.agent;

import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.time.Duration;

/**
 * Agent de rétro-documentation Java Legacy.
 * Utilise Qwen2.5-Coder via Ollama pour analyser du code Java existant.
 */
public class JavaDocumentationAgent {

    // ── Interface de l'agent (vision micro — Qwen-Coder) ─────────────────────
    interface CodeAnalyzer {

        @SystemMessage("""
            Tu es un expert en analyse de code Java Legacy (Java 6/7/8, EJB, Struts).
            Ta mission est d'extraire les spécifications techniques implicites du code.
            Réponds toujours en français, de manière structurée et concise.
            Format de réponse : Markdown.
            """)
        @UserMessage("""
            Analyse ce code Java et génère :
            1. **Résumé fonctionnel** (1-2 phrases)
            2. **Responsabilités** (liste des actions principales)
            3. **Dépendances détectées** (classes, services, DB)
            4. **Risques identifiés** (code smell, dette technique)
            5. **Javadoc suggéré** pour la classe/méthode principale

            Code à analyser :
            {{it}}
            """)
        String analyzeCode(String javaCode);
    }

    // ── Interface synthèse macro ──────────────────────────────────────────────
    interface DocumentationSynthesizer {

        @SystemMessage("""
            Tu es un architecte logiciel senior spécialisé en documentation technique.
            Tu produis des dossiers d'architecture (DAT) lisibles par des Product Owners et des DSI.
            Réponds en français, format Markdown professionnel.
            """)
        @UserMessage("""
            Sur la base des spécifications techniques suivantes extraites du code Legacy,
            génère un **Dossier d'Architecture Technique (DAT)** incluant :

            1. **Vue d'ensemble du module**
            2. **Flux fonctionnels principaux**
            3. **Points de vigilance pour la modernisation**
            4. **Recommandations de migration**

            Spécifications sources :
            {{it}}
            """)
        String synthesizeDocumentation(String technicalSpecs);
    }

    private final CodeAnalyzer codeAnalyzer;
    private final DocumentationSynthesizer synthesizer;

    public JavaDocumentationAgent(String ollamaBaseUrl) {
        // Modèle micro — Qwen2.5-Coder pour l'analyse unitaire
        OllamaChatModel coderModel = OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName("qwen2.5-coder:7b")
                .temperature(0.1)  // précision maximale pour le code
                .timeout(Duration.ofMinutes(15))
                .build();

        // Modèle macro — Qwen2.5-Coder pour la synthèse documentaire
        OllamaChatModel synthModel = OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName("qwen2.5-coder:7b")
                .temperature(0.3)
                .timeout(Duration.ofMinutes(15))
                .build();

        this.codeAnalyzer = AiServices.create(CodeAnalyzer.class, coderModel);
        this.synthesizer   = AiServices.create(DocumentationSynthesizer.class, synthModel);
    }

    /**
     * Analyse un fichier Java et retourne les spécifications extraites.
     */
    public String analyzeJavaClass(String javaCode, String context) {

        System.out.println("🔍 Analyse du code avec Qwen2.5-Coder...");
        return codeAnalyzer.analyzeCode(javaCode);
    }

    /**
     * Analyse enrichie : injecte le contexte AST avant le code source.
     * Le LLM reçoit la structure extraite + le code → specs plus précises.
     */
    public String analyzeJavaClassWithAst(String javaCode, AstParserAgent.AstAnalysis ast) {
        System.out.println("🔍 Analyse AST + LLM : " + ast.className());
        String enriched = ast.toPromptContext() + "\n### Code source\n```java\n" + javaCode + "\n```";
        return codeAnalyzer.analyzeCode(enriched);
    }

    /**
     * Synthétise plusieurs spécifications en un DAT complet.
     */
    public String generateDAT(String aggregatedSpecs) {
        System.out.println("📄 Génération du DAT avec Qwen2.5...");
        return synthesizer.synthesizeDocumentation(aggregatedSpecs);
    }
}

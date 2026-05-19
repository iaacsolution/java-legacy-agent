package com.audensiel.legacy.agent;

import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.time.Duration;

/**
 * Agent 5 — Génère un plan de migration priorisé à partir des specs agrégées.
 */
public class MigrationPlannerAgent {

    interface MigrationPlanner {

        @SystemMessage("""
            Tu es un expert en migration de systèmes Java Legacy (EJB, Struts, Java 6/7) vers des architectures modernes
            (Spring Boot 3, microservices, cloud-native).
            Tu produis des plans de migration concrets, priorisés par risque et impact métier.
            Réponds en français, format Markdown professionnel.
            """)
        @UserMessage("""
            Sur la base de ces spécifications techniques extraites du code Legacy :

            {{it}}

            Génère un **Plan de Migration** structuré incluant :

            1. **Bilan de la dette technique** (synthèse des risques détectés)
            2. **Quick wins** (améliorations sans risque, faisables en < 1 sprint)
            3. **Priorités de refactoring** (classées par risque et impact)
            4. **Roadmap de migration** (court terme / moyen terme / long terme)
            5. **Recommandations d'architecture cible** (patterns modernes à adopter)
            """)
        String planMigration(String aggregatedSpecs);
    }

    private final MigrationPlanner planner;

    public MigrationPlannerAgent(String ollamaBaseUrl) {
        OllamaChatModel model = OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName("qwen2.5-coder:7b")
                .temperature(0.2)
                .timeout(Duration.ofMinutes(10))
                .build();

        this.planner = AiServices.create(MigrationPlanner.class, model);
    }

    public String generateMigrationPlan(String aggregatedSpecs) {
        System.out.println("🗺️  Génération du plan de migration...");
        return planner.planMigration(aggregatedSpecs);
    }
}

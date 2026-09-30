package com.audensiel.legacy.agent.observability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Table de prix <strong>illustrative</strong> servant à estimer le coût d'un appel LLM.
 *
 * <p><strong>Ces chiffres ne font pas autorité.</strong> Ils ne sont pas mesurés, ils ne
 * sont pas relevés depuis une facture, et ils ne sont pas tenus à jour automatiquement.
 * Ils existent pour donner un ordre de grandeur dans un tableau de bord, pas pour être
 * cités comme un coût réel. Toute exploitation (rapport, CV, facturation client) doit
 * repartir de la facturation réelle du fournisseur.
 *
 * <p>Conformément à cette règle, la colonne {@code cost_usd} d'un run doit être lue
 * comme « estimation à tarif catalogue », jamais comme « dépense constatée ».
 *
 * <p>Les modèles exécutés sur site (Ollama, vLLM) sont facturés 0 : le coût réel y est
 * de l'électricité et de l'amortissement GPU, que ce composant ne sait pas mesurer.
 */
public final class ModelPricing {

    private ModelPricing() {}

    /** Prix catalogue illustratif, en USD par million de tokens. */
    private record Price(double inputPerMillion, double outputPerMillion) {}

    private static final Price FREE_LOCAL = new Price(0.0, 0.0);

    private static final Map<String, Price> PRICES = Map.of(
            "claude-haiku-4-5-20251001", new Price(1.00, 5.00),
            "claude-sonnet-4-5",         new Price(3.00, 15.00),
            "claude-opus-4-1",           new Price(15.00, 75.00)
    );

    /** Un modèle est considéré local (coût 0) à ces motifs de nom. */
    private static boolean isLocal(String model) {
        if (model == null) return true;
        String m = model.toLowerCase();
        return m.startsWith("qwen") || m.contains("/qwen") || m.contains("coder")
            || m.contains("llama") || m.contains("mistral");
    }

    /**
     * Coût estimé d'un appel, arrondi à 6 décimales — l'échelle de la colonne
     * {@code cost_usd NUMERIC(12,6)}.
     *
     * <p>Un modèle inconnu est facturé 0 plutôt que deviné : mieux vaut un zéro
     * visiblement faux qu'un chiffre plausible et inventé.
     */
    public static BigDecimal costUsd(String model, int tokensIn, int tokensOut) {
        if (isLocal(model)) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

        Price p = PRICES.get(model);
        if (p == null) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

        double cost = (tokensIn / 1_000_000.0) * p.inputPerMillion()
                    + (tokensOut / 1_000_000.0) * p.outputPerMillion();
        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }

    /** Vrai si le modèle a un tarif connu — utile pour signaler une table à compléter. */
    public static boolean isPriced(String model) {
        return !isLocal(model) && PRICES.containsKey(model);
    }
}

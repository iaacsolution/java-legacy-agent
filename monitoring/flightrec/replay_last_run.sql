-- Rejoue le dernier run sous forme d'arbre : run → agents → appels LLM.
--
--   psql "postgresql://postgres@localhost:5432/legacyrec" -f monitoring/flightrec/replay_last_run.sql
--
-- Un run_id porte DEUX racines quand le pipeline tourne en deux process
-- (services java-analyzer et java-reporter) : une racine « analyze » et une
-- racine « report ». Ce n'est pas une anomalie, c'est la topologie réelle —
-- la récursion est donc amorcée sur toutes les racines du run, pas sur une seule.

-- RECURSIVE porte sur la liste WITH entiere, pas sur un CTE en particulier :
-- dernier_run n'est pas recursif, mais le mot-cle doit figurer ici pour que
-- « arbre » puisse se referencer lui-meme.
WITH RECURSIVE dernier_run AS (
    SELECT run_id
    FROM agent_span
    WHERE project = 'java-legacy-agent'
    ORDER BY time DESC
    LIMIT 1
),
arbre AS (
    -- Amorce : TOUTES les racines du run (parent_id IS NULL), pas une seule.
    SELECT
        s.span_id,
        s.parent_id,
        s.run_id,
        s.time,
        s.agent,
        s.span_kind,
        s.name,
        s.status,
        s.duration_ms,
        s.tokens_in,
        s.tokens_out,
        s.cost_usd,
        s.attributes,
        0                                   AS profondeur,
        s.name                              AS racine,
        ARRAY[s.time, s.time]               AS tri
    FROM agent_span s
    JOIN dernier_run d USING (run_id)
    WHERE s.parent_id IS NULL

    UNION ALL

    SELECT
        e.span_id,
        e.parent_id,
        e.run_id,
        e.time,
        e.agent,
        e.span_kind,
        e.name,
        e.status,
        e.duration_ms,
        e.tokens_in,
        e.tokens_out,
        e.cost_usd,
        e.attributes,
        a.profondeur + 1,
        a.racine,                           -- le nom de la racine descend dans la branche
        a.tri || e.time
    FROM agent_span e
    JOIN arbre a ON e.parent_id = a.span_id
    JOIN dernier_run d ON d.run_id = e.run_id
)
SELECT
    racine                                                        AS phase,
    repeat('    ', profondeur) || span_kind || ' · ' || name       AS arbre,
    agent,
    status,
    round(duration_ms::numeric, 1)                                AS duree_ms,
    NULLIF(tokens_in, 0)                                          AS jetons_entree,
    NULLIF(tokens_out, 0)                                         AS jetons_sortie,
    NULLIF(cost_usd, 0)                                           AS cout_usd_estime,
    attributes ->> 'class'                                        AS classe_analysee
FROM arbre
ORDER BY tri, time;

-- ── Agrégats du même run ────────────────────────────────────────────────────
-- P95 par agent, coût total et taux d'erreur — les trois questions auxquelles
-- RunMetrics, Prometheus et Phoenix ne savaient pas répondre sur l'historique.
WITH dernier_run AS (
    SELECT run_id
    FROM agent_span
    WHERE project = 'java-legacy-agent'
    ORDER BY time DESC
    LIMIT 1
)
SELECT
    s.agent,
    s.span_kind,
    count(*)                                                                  AS spans,
    round(percentile_cont(0.95) WITHIN GROUP (ORDER BY s.duration_ms)::numeric, 1) AS p95_ms,
    round(sum(s.duration_ms)::numeric, 1)                                     AS total_ms,
    sum(s.tokens_in)                                                          AS jetons_entree,
    sum(s.tokens_out)                                                         AS jetons_sortie,
    sum(s.cost_usd)                                                           AS cout_usd_estime,
    round(100.0 * count(*) FILTER (WHERE s.status <> 'ok') / count(*), 1)     AS taux_erreur_pct
FROM agent_span s
JOIN dernier_run d USING (run_id)
GROUP BY s.agent, s.span_kind
ORDER BY total_ms DESC;

-- Rappel : cout_usd est une ESTIMATION à tarif catalogue (voir ModelPricing.java,
-- table explicitement illustrative), jamais une dépense constatée. Les modèles
-- locaux (Ollama, vLLM) y figurent à 0.

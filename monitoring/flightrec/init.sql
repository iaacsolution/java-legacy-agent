-- Flight recorder — schéma de la base legacyrec.
--
-- Idempotent : peut être rejoué sans risque sur une base existante.
--
-- Appliquer à la base locale déjà créée (conteneur timescale-course) :
--   psql "postgresql://postgres@localhost:5432/legacyrec" -v ON_ERROR_STOP=1 -f monitoring/flightrec/init.sql
-- ou, si psql n'est pas installé sur l'hôte :
--   Get-Content monitoring/flightrec/init.sql | docker exec -i timescale-course psql -U postgres -d legacyrec -v ON_ERROR_STOP=1
--
-- ATTENTION : cibler legacyrec, jamais flightrec — cette dernière contient les
-- données du cours TimescaleDB et n'a rien à voir avec ce projet. Les deux bases
-- cohabitent dans le même conteneur, seul le nom de base les sépare.

-- ── Prérequis de version ────────────────────────────────────────────────────
-- uuid_extract_timestamp() n'existe qu'à partir de PostgreSQL 18. Sans elle, la
-- contrainte CHECK ci-dessous est impossible à créer ; mieux vaut le dire
-- explicitement qu'échouer sur une erreur de fonction inconnue.
DO $$
BEGIN
    IF current_setting('server_version_num')::int < 180000 THEN
        RAISE EXCEPTION
            'PostgreSQL 18+ requis : uuid_extract_timestamp() (contrainte CHECK de agent_span) n''existe pas avant. Version détectée : %',
            current_setting('server_version');
    END IF;
END
$$;

-- ── Extensions ──────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- toolkit sert aux agrégats de percentiles côté Grafana, pas au recorder lui-même :
-- son absence est signalée mais ne doit pas faire échouer l'initialisation.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS timescaledb_toolkit;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'timescaledb_toolkit indisponible (%) — les percentiles Grafana devront utiliser percentile_cont', SQLERRM;
END
$$;

-- ── Table ───────────────────────────────────────────────────────────────────
-- Définition recopiée à l'identique depuis la spécification de la mission.
-- Ne pas la modifier : le générateur d'UUID v7 côté Java (SpanId) est écrit
-- pour satisfaire exactement la contrainte CHECK ci-dessous.
CREATE TABLE IF NOT EXISTS agent_span (
  time TIMESTAMPTZ NOT NULL, span_id UUID NOT NULL, parent_id UUID,
  run_id UUID NOT NULL, session_id TEXT, project TEXT NOT NULL,
  agent TEXT NOT NULL, span_kind TEXT NOT NULL, name TEXT NOT NULL,
  status TEXT NOT NULL, duration_ms DOUBLE PRECISION NOT NULL,
  tokens_in INT DEFAULT 0, tokens_out INT DEFAULT 0,
  cost_usd NUMERIC(12,6) DEFAULT 0, attributes JSONB,
  PRIMARY KEY (time, span_id),
  CHECK (uuid_extract_timestamp(span_id) = time)
);

-- ── Hypertable ──────────────────────────────────────────────────────────────
-- Chunks d'un jour : le pipeline produit quelques milliers de spans par run,
-- soit des chunks largement sous le seuil où TimescaleDB recommande de découper
-- plus finement, tout en gardant les requêtes « dernier run » sur un seul chunk.
SELECT create_hypertable(
    'agent_span', 'time',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists       => TRUE
);

-- ── Index ───────────────────────────────────────────────────────────────────
-- Parcours par projet et par fenêtre de temps — le cas Grafana courant.
CREATE INDEX IF NOT EXISTS agent_span_project_time_idx
    ON agent_span (project, time DESC);

-- Reconstruction de l'arbre d'un run (requête de replay).
CREATE INDEX IF NOT EXISTS agent_span_run_time_idx
    ON agent_span (run_id, time);

-- Index partiel sur les échecs : les erreurs sont rares, un index partiel reste
-- petit et rend le panneau « taux d'erreur » indépendant du volume de succès.
CREATE INDEX IF NOT EXISTS agent_span_errors_idx
    ON agent_span (project, time DESC)
    WHERE status <> 'ok';

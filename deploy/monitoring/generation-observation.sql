-- Harudle generation observation: READ ONLY, aggregates only.
-- Verified source refs (2026-10-08):
--   main 1fb7adaf89dcef0d628ab05e9b87778de3bd0222
--   dev  a9fcf05c4fbfde6adde26cf91f956e5161b4330a
--   develop-backend df6125e23e7c36f7a08b9ba05a92103ab701b7b5
-- Their migrations, DiaryGeneration, claim/completion service and token record agree.
-- Run once in dev and once in prod, using each application's own database.
-- Never select IDs, diary text, storyboard, image keys, user columns or raw JSON.
-- This is not a recurrent job and does not create tables, indexes or metrics.
--
-- Default window: 2026-10-01 00:00 KST <= completed_at < transaction start.
-- This includes today's partial day. Adjust BOTH time settings below if desired.
-- A closed KST day uses from 2026-10-07 00:00:00+09 to 2026-10-08 00:00:00+09.
-- Confirm GENERATION_PROCESSING_TIMEOUT before interpreting the stale threshold;
-- Runtime dev/prod override verified as 5 minutes; application fallback is 30m.
-- The transaction timeout is per statement; a timeout aborts the transaction.
-- If that happens, ROLLBACK. Do not automatically broaden/repeat the scan in prod.

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL idle_in_transaction_session_timeout = '30s';
SET LOCAL TIME ZONE 'Asia/Seoul';
SET LOCAL harudle_observation.from_at = '2026-10-01 00:00:00+09';
-- Empty end means transaction start; it is stable across the following queries.
SET LOCAL harudle_observation.to_at = '';
SET LOCAL harudle_observation.processing_timeout = '5 minutes';

-- 0. Catalog-only preflight: approximate row count and actual indexes.
-- reltuples is an estimate, not a generation count. No EXPLAIN ANALYZE is run.
SELECT c.reltuples::bigint AS estimated_generation_rows,
       pg_relation_size(c.oid) AS table_heap_bytes,
       pg_size_pretty(pg_relation_size(c.oid)) AS table_heap_size
FROM pg_class c
WHERE c.oid = to_regclass('diary_generations');

SELECT i.relname AS indexname, pg_get_indexdef(i.oid) AS indexdef
FROM pg_index x
JOIN pg_class i ON i.oid = x.indexrelid
WHERE x.indrelid = to_regclass('diary_generations')
ORDER BY i.relname;

-- 1. Daily terminal success rate, by COMPLETION date in KST.
-- The unique generation row is the unit. An idempotent replay does not add a row.
-- A new idempotency key/new generation is intentionally a separate observation.
-- No join to active diaries: FAILED rows have soft-deleted diaries and must count.
-- PROCESSING is excluded from both numerator and denominator, shown in query 4.
-- Zero terminal rows => rate NULL (not 0% and not 100%).
WITH params AS (
    SELECT current_setting('harudle_observation.from_at')::timestamptz AS from_at,
           COALESCE(NULLIF(current_setting('harudle_observation.to_at'), '')::timestamptz,
                    CURRENT_TIMESTAMP) AS to_at
), counts AS (
    SELECT (g.completed_at AT TIME ZONE 'Asia/Seoul')::date AS completed_date_kst,
           count(*) FILTER (WHERE g.status = 'SUCCEEDED') AS succeeded,
           count(*) FILTER (WHERE g.status = 'FAILED') AS failed
    FROM diary_generations g CROSS JOIN params p
    WHERE g.completed_at >= p.from_at AND g.completed_at < p.to_at
      AND g.status IN ('SUCCEEDED', 'FAILED')
    GROUP BY 1
), days AS (
    SELECT d::date AS completed_date_kst
    FROM params p
    CROSS JOIN LATERAL generate_series(
        (p.from_at AT TIME ZONE 'Asia/Seoul')::date,
        ((p.to_at - interval '1 microsecond') AT TIME ZONE 'Asia/Seoul')::date,
        interval '1 day'
    ) AS d
)
SELECT d.completed_date_kst,
       COALESCE(c.succeeded, 0) AS succeeded,
       COALESCE(c.failed, 0) AS failed,
       COALESCE(c.succeeded, 0) + COALESCE(c.failed, 0) AS terminal_generations,
       round(100.0 * c.succeeded / NULLIF(c.succeeded + c.failed, 0), 2)
           AS success_percent,
       p.to_at AS observed_until_exclusive
FROM days d CROSS JOIN params p
LEFT JOIN counts c USING (completed_date_kst)
ORDER BY d.completed_date_kst;

-- 2. Daily failed generation count by bounded application error code.
-- Includes cleanup timeouts as GENERATION_INTERRUPTED, not a Gemini failure.
WITH params AS (
    SELECT current_setting('harudle_observation.from_at')::timestamptz AS from_at,
           COALESCE(NULLIF(current_setting('harudle_observation.to_at'), '')::timestamptz,
                    CURRENT_TIMESTAMP) AS to_at
)
SELECT (g.completed_at AT TIME ZONE 'Asia/Seoul')::date AS completed_date_kst,
       COALESCE(g.error_code, 'UNKNOWN') AS error_code,
       count(*) AS failed_generations
FROM diary_generations g CROSS JOIN params p
WHERE g.completed_at >= p.from_at AND g.completed_at < p.to_at
  AND g.status = 'FAILED'
GROUP BY 1, 2
ORDER BY 1, failed_generations DESC, 2;

-- 3. Terminal wall-clock latency (completed_at - created_at), separately by status.
-- This includes claim/DB elapsed time. It is not Gemini time, HTTP latency,
-- browser image latency or the monotonic executor timer.
-- FAILED timeout rows can approach 5m+cleanup delay in these environments; keep them separate.
-- Negative durations are excluded from percentiles but explicitly counted.
-- P50/P95 with very few samples are descriptive, not an SLO or load-test result.
WITH params AS (
    SELECT current_setting('harudle_observation.from_at')::timestamptz AS from_at,
           COALESCE(NULLIF(current_setting('harudle_observation.to_at'), '')::timestamptz,
                    CURRENT_TIMESTAMP) AS to_at
), elapsed AS (
    SELECT (g.completed_at AT TIME ZONE 'Asia/Seoul')::date AS completed_date_kst,
           g.status,
           EXTRACT(EPOCH FROM g.completed_at - g.created_at)::double precision
               AS elapsed_seconds
    FROM diary_generations g CROSS JOIN params p
    WHERE g.completed_at >= p.from_at AND g.completed_at < p.to_at
      AND g.status IN ('SUCCEEDED', 'FAILED')
)
SELECT completed_date_kst, status,
       count(*) AS terminal_samples,
       count(*) FILTER (WHERE elapsed_seconds >= 0) AS valid_duration_samples,
       count(*) FILTER (WHERE elapsed_seconds < 0 OR elapsed_seconds IS NULL)
           AS invalid_duration_samples,
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY elapsed_seconds)
              FILTER (WHERE elapsed_seconds >= 0))::numeric, 3) AS p50_seconds,
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY elapsed_seconds)
              FILTER (WHERE elapsed_seconds >= 0))::numeric, 3) AS p95_seconds,
       round((max(elapsed_seconds) FILTER (WHERE elapsed_seconds >= 0))::numeric, 3)
           AS max_seconds
FROM elapsed
GROUP BY completed_date_kst, status
ORDER BY completed_date_kst, status;

-- 4. Active PROCESSING health: intentionally inspect ALL PROCESSING rows,
-- including rows created before the selected completion window.
-- Application cleanup uses updated_at <= now - processing_timeout.
-- created_at measures total age; updated_at measures cleanup eligibility.
-- There is no generation heartbeat here. Do not call updated_at "last progress".
WITH params AS (
    SELECT CURRENT_TIMESTAMP AS observed_at,
           current_setting('harudle_observation.processing_timeout')::interval
               AS processing_timeout
)
SELECT count(*) AS processing_generations,
       count(*) FILTER (WHERE g.updated_at <= p.observed_at - p.processing_timeout)
           AS cleanup_eligible_generations,
       count(*) FILTER (WHERE g.created_at <= p.observed_at - p.processing_timeout)
           AS older_than_processing_timeout,
       count(*) FILTER (WHERE g.updated_at IS NULL) AS missing_updated_at,
       round(max(EXTRACT(EPOCH FROM p.observed_at - g.created_at))::numeric, 1)
           AS oldest_processing_age_seconds,
       round(max(EXTRACT(EPOCH FROM p.observed_at - g.updated_at))::numeric, 1)
           AS largest_update_age_seconds,
       current_setting('harudle_observation.processing_timeout') AS configured_query_threshold,
       CURRENT_TIMESTAMP AS observed_at
FROM diary_generations g CROSS JOIN params p
WHERE g.status = 'PROCESSING';

-- 5. Daily persisted STORYBOARD token metadata, successful generations ONLY.
-- Source stores generatedStoryboard.tokenUsage() only after full generation succeeds.
-- This does NOT measure image-generation tokens, failed-call consumption, SDK retries,
-- all Gemini spending, paid billing or the user's daily generation allowance.
-- Missing/null/invalid values remain NULL and have separate counts; never map to 0.
-- A known token count of 0 is kept as a valid observed value.
-- "total" is a provider-reported count; do not add it to prompt/candidate/thought.
WITH params AS (
    SELECT current_setting('harudle_observation.from_at')::timestamptz AS from_at,
           COALESCE(NULLIF(current_setting('harudle_observation.to_at'), '')::timestamptz,
                    CURRENT_TIMESTAMP) AS to_at
), token_values AS (
    SELECT (g.completed_at AT TIME ZONE 'Asia/Seoul')::date AS completed_date_kst,
           g.token_usage IS NOT NULL AND g.token_usage <> 'null'::jsonb
               AS metadata_present,
           t.token_kind,
           t.raw_token_count,
           CASE WHEN jsonb_typeof(t.json_token_count) = 'number'
                          AND t.raw_token_count ~ '^[0-9]{1,10}$'
                THEN t.raw_token_count::numeric END AS known_token_count
    FROM diary_generations g CROSS JOIN params p
    CROSS JOIN LATERAL (VALUES
        ('prompt', g.token_usage ->> 'promptTokenCount',
                   g.token_usage -> 'promptTokenCount'),
        ('candidate', g.token_usage ->> 'candidateTokenCount',
                      g.token_usage -> 'candidateTokenCount'),
        ('thought', g.token_usage ->> 'thoughtTokenCount',
                    g.token_usage -> 'thoughtTokenCount'),
        ('total', g.token_usage ->> 'totalTokenCount',
                  g.token_usage -> 'totalTokenCount')
    ) AS t(token_kind, raw_token_count, json_token_count)
    WHERE g.completed_at >= p.from_at AND g.completed_at < p.to_at
      AND g.status = 'SUCCEEDED'
)
SELECT completed_date_kst, token_kind,
       count(*) AS succeeded_generations,
       count(*) FILTER (WHERE metadata_present) AS generations_with_metadata,
       count(known_token_count) AS known_value_samples,
       count(*) FILTER (WHERE raw_token_count IS NULL) AS missing_value_samples,
       count(*) FILTER (WHERE raw_token_count IS NOT NULL AND known_token_count IS NULL)
           AS invalid_value_samples,
       sum(known_token_count) AS known_tokens_sum,
       round(avg(known_token_count), 1) AS known_tokens_average,
       max(known_token_count) AS known_tokens_max
FROM token_values
GROUP BY completed_date_kst, token_kind
ORDER BY completed_date_kst, token_kind;

-- 6. Data-quality guard for rows CREATED in the selected window.
-- Terminal rows missing completed_at cannot be assigned to a completion day.
-- Do not silently substitute updated_at or created_at for completed_at.
-- This narrower created_at window may miss very old malformed rows; it is not
-- an exhaustive whole-database integrity audit.
WITH params AS (
    SELECT current_setting('harudle_observation.from_at')::timestamptz AS from_at,
           COALESCE(NULLIF(current_setting('harudle_observation.to_at'), '')::timestamptz,
                    CURRENT_TIMESTAMP) AS to_at
)
SELECT count(*) FILTER (
           WHERE g.status IN ('SUCCEEDED', 'FAILED') AND g.completed_at IS NULL
       ) AS terminal_rows_missing_completed_at,
       count(*) FILTER (
           WHERE g.status = 'PROCESSING' AND g.completed_at IS NOT NULL
       ) AS processing_rows_with_completed_at,
       count(*) FILTER (
           WHERE g.status IN ('SUCCEEDED', 'FAILED') AND g.completed_at < g.created_at
       ) AS terminal_rows_with_negative_duration
FROM diary_generations g CROSS JOIN params p
WHERE g.created_at >= p.from_at AND g.created_at < p.to_at;

ROLLBACK;

-- Operational notes:
-- - Migrations provide PK(id), UNIQUE(diary_id), UNIQUE(idempotency_key),
--   UNIQUE(image_object_key), an index(prompt_id), and (created_at DESC,id DESC).
-- - No completed_at index and no (status,updated_at) index exist in these refs.
--   Completion-window and PROCESSING queries may scan the table, even for one day.
-- - Check query 0 first; run during low traffic, one environment at a time.
-- - Keep 5s timeout and avoid recurring execution without a measured query plan.
-- - Use EXPLAIN (without ANALYZE) if a plan is needed. Adding indexes is future
--   code/migration work and intentionally outside this read-only preparation.
-- - User account hard deletion cascades through diaries into generation rows.
--   Historical percentages from this table describe retained generation history,
--   not an immutable audit ledger of every generation ever attempted.

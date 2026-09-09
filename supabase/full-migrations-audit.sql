-- Full audit: every migration in supabase/migrations, in order, checked against the live
-- database rather than assumed from the repo -- per MIGRATIONS.md, prompted by 20260911's
-- unique index turning out to have never been run despite being committed for days. All ten
-- migrations recent-migrations-audit.sql covers were confirmed live 2026-09-08; this is the
-- same discipline applied to the full migration history, not just the last several days.
--
-- NOT a migration itself -- a diagnostic script to run in the SQL editor, kept alongside
-- MIGRATIONS.md rather than under migrations/ so tooling that runs everything in that folder
-- doesn't try to apply it.
--
-- SECTION A: one-shot checks, migration order. Each is independent -- run any of them alone.
-- SECTION B: several functions were rewritten many times (get_kenai_presence_state alone has
-- 6 versions). For those, checking each old migration individually is misleading: a later
-- CREATE OR REPLACE is a complete, self-contained rewrite and does not need the earlier one to
-- have actually run first -- so "is migration N's version live" only ever means "is THIS THE
-- CURRENT version," not "did N specifically get applied." Section B gives one cascading
-- ladder per function: each row checks for a token unique to that version's body (not its
-- surrounding comments -- pg_get_functiondef never includes those, only what's inside $$...$$).
-- Exactly ONE should come back true -- that's the live version. Every row below it being false
-- is expected and fine (superseded, not missing). More than one true, or all false, means
-- something unexpected and worth a closer look.
--
-- get_kenai_presence_state takes a bigint parameter (defaulted, but regprocedure resolution
-- needs the exact signature regardless of defaults) -- every cast below uses
-- get_kenai_presence_state(bigint), not the bare name, which does not resolve.


-- =========================================================================================
-- SECTION A
-- =========================================================================================

-- 20260818000000_add_photo_url_to_sightings.sql
select
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='sightings' and column_name='photo_url') as photo_url_column,
  exists (select 1 from storage.buckets where id = 'sighting-photos') as photo_bucket,
  exists (select 1 from pg_policies where tablename='objects' and schemaname='storage' and policyname='Public read access for sighting photos') as photo_read_policy;

-- 20260818120000_add_sighting_sector_fields.sql
select
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='sightings' and column_name='heading_degrees') as heading_degrees_column,
  exists (select 1 from pg_constraint where conname='sightings_heading_source_check') as heading_source_check,
  exists (select 1 from pg_constraint where conname='sightings_distance_bucket_check') as distance_bucket_check;

-- 20260819000000_add_notification_zones_and_subscriptions.sql
select
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='zones') as zones_table,
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='point_presets') as point_presets_table,
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='subscriptions') as subscriptions_table,
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='sightings' and column_name='is_geofence_verified') as is_geofence_verified_column;

-- 20260819130000_seed_notification_zones_and_point_presets.sql
select
  (select count(*) from public.zones) as zones_row_count, -- expect > 0
  (select count(*) from public.point_presets) as point_presets_row_count, -- expect > 0
  exists (select 1 from public.zones where slug = 'kenai') as kenai_zone_seeded;

-- 20260823000000_add_device_tokens_and_notify_trigger.sql
select
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='device_tokens') as device_tokens_table,
  exists (select 1 from pg_proc where proname='notify_new_sighting') as notify_fn,
  exists (select 1 from pg_trigger where tgname='on_sighting_insert_notify') as notify_trigger,
  exists (select 1 from pg_extension where extname='pg_net') as pg_net_extension,
  exists (select 1 from pg_extension where extname='supabase_vault') as vault_extension;

-- 20260823120000_add_articles.sql
select exists (select 1 from information_schema.tables where table_schema='public' and table_name='articles') as articles_table;

-- 20260826000000_fix_device_tokens_registered_at_refresh.sql
select
  exists (select 1 from pg_proc where proname='touch_device_token_registered_at') as touch_fn,
  exists (select 1 from pg_trigger where tgname='touch_device_token_registered_at') as touch_trigger;

-- 20260827000000_add_export_sightings_rpc.sql -- superseded; see export_sightings ladder, Section B.

-- 20260829000000_add_point_preset_coordinates.sql
select
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='point_presets' and column_name='lat') as lat_column,
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='point_presets' and column_name='lng') as lng_column;

-- 20260829010000_add_subscription_matching.sql -- match_notification_recipients superseded by
-- 20260902010000's 5-arg version below; the subscriber_id column itself is its own check:
select exists (select 1 from information_schema.columns where table_schema='public' and table_name='device_tokens' and column_name='subscriber_id') as device_tokens_subscriber_id_column;

-- 20260829020000_add_beluga_presence_banner.sql -- get_watched_zone_statuses superseded, see
-- Section B; is_banner_watched is its own check:
select
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='zones' and column_name='is_banner_watched') as is_banner_watched_column,
  exists (select 1 from pg_proc where proname='find_nearby_watched_zone') as find_nearby_watched_zone_fn,
  (select is_banner_watched from public.zones where slug='kenai') as kenai_is_watched; -- expect true

-- 20260830000000_add_coastline_traces.sql -- is_point_within_coastline_channel superseded, see
-- Section B; the table itself is its own check:
select exists (select 1 from information_schema.tables where table_schema='public' and table_name='coastline_traces') as coastline_traces_table;

-- 20260831000000_extend_west_shore_and_add_rivers.sql
select
  pg_get_constraintdef(oid) as coastline_traces_side_check_def -- expect it to allow ('east','west','river')
  from pg_constraint where conname = 'coastline_traces_side_check';
select
  exists (select 1 from public.coastline_traces where slug='big_river') as big_river_seeded,
  exists (select 1 from public.coastline_traces where slug='beluga_river') as beluga_river_seeded,
  (select ST_Y(ST_StartPoint(line)) from public.coastline_traces where slug='cook_inlet_west_shore_kenai_latitude') as west_shore_south_end_lat; -- expect ~60.1645, not ~60.4069 (pre-extension)

-- 20260831010000_widen_coastline_channel_search_radius.sql -- superseded, see Section B.

-- 20260831020000_replace_kenai_zone_river_spikes.sql
-- (no DDL keyword matched by the earlier grep -- likely a data-only zones.boundary update.
-- Check zones.boundary's vertex count near the kenai spike changed from whatever it replaced;
-- easiest single check is just confirming the zone's boundary is still valid/non-null and has
-- the expected rough vertex count discussed in that migration's own header -- open the file
-- for the exact number if this needs pinning down further, not included here to avoid a guess.)
select ST_IsValid(boundary), ST_NPoints(ST_ExteriorRing(boundary)) from public.zones where slug='kenai';

-- 20260901000000_add_whale_position_columns.sql
select
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='sightings' and column_name='whale_lat') as whale_lat_column,
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='sightings' and column_name='position_source') as position_source_column;

-- 20260901010000_add_river_mouth_proximity_check.sql -- superseded, see Section B.

-- 20260902000000_use_whale_position_in_zone_and_export_rpcs.sql -- both functions superseded,
-- see Section B (get_watched_zone_statuses ladder, export_sightings ladder).

-- 20260902010000_add_river_proximity_fallback_to_zone_matching.sql
select exists (
  select 1 from pg_proc
  where proname = 'match_notification_recipients'
    and pg_get_function_identity_arguments(oid) = 'p_lat double precision, p_lng double precision, p_observer_type text, p_is_geofence_verified boolean, p_uncertainty_radius_meters double precision'
) as match_notification_recipients_5arg_version; -- is_whale_position_in_zone/get_watched_zone_statuses/export_sightings: see Section B.
select exists (select 1 from pg_proc where proname='is_whale_position_in_zone') as is_whale_position_in_zone_fn;

-- 20260903000000_narrow_kenai_banner_to_river_and_mouth.sql -- superseded, see Section B.
-- 20260903010000_add_observer_tier_system.sql
select
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='tier_roster') as tier_roster_table,
  exists (select 1 from pg_proc where proname='get_observer_tier') as get_observer_tier_fn,
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='sightings' and column_name='observer_tier') as sightings_observer_tier_column,
  exists (select 1 from pg_trigger where tgname='on_sighting_insert_set_observer_tier') as tier_trigger;
  -- redeem_tier_code, export_sightings: see Section B.

-- 20260903020000_wire_observer_tier_into_red_banner.sql -- superseded, see Section B.

-- 20260903030000_add_tide_cycle_predictor.sql -- get_kenai_presence_state superseded, see
-- Section B; the table/cron job are their own check:
select exists (select 1 from information_schema.tables where table_schema='public' and table_name='tide_cycles') as tide_cycles_table;
select jobname from cron.job where jobname = 'trigger-beluga-prediction-refresh'; -- expect 1 row (name per that migration's own cron.schedule call -- open the file to confirm the exact literal if this comes back empty, since it may have been superseded/unscheduled by 20260908's two-job replacement)

-- 20260903040000 / 20260903050000 / 20260903060000 / 20260904000000 -- kenai_mouth_semicircle,
-- kenai_river_spike_line_beluga_limit, is_whale_position_in_kenai_banner_area,
-- get_watched_zone_shading_areas, kenai_closing_margin_meters: see Section B where superseded.
-- Already confirmed live this session (existence + valid output), included in Section B ladders
-- for completeness rather than re-litigated here.

-- 20260905000000_record_gate_datum_regime_on_tide_cycles.sql
-- UNKNOWABLE IN ISOLATION: this added tide_cycles.low_at_or_below_gate_datum, which
-- 20260907010000 (below) drops outright. If 20260907010000 is live, the column's absence is
-- expected either way and can't tell you whether this one ever ran -- and it no longer matters
-- functionally either way, since the column is gone regardless. Not included as a check.

-- 20260905010000_add_tier_code_rate_limiting_and_identity_bind.sql
select
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='tier_code_redeem_attempts') as rate_limit_table,
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='subscriber_identities') as subscriber_identities_table,
  exists (select 1 from pg_proc where proname='generate_crockford_short_code') as short_code_fn,
  exists (select 1 from pg_proc where proname='get_or_create_subscriber_identity') as get_or_create_identity_fn;
  -- redeem_tier_code, admin_bind_tier_code: see Section B.

-- 20260905020000_normalize_tier_code_case_and_separators.sql
select exists (select 1 from pg_proc where proname='normalize_tier_code') as normalize_tier_code_fn;
-- redeem_tier_code, admin_bind_tier_code: see Section B.

-- 20260905030000_group_tier_code_to_expose_truncation.sql
select exists (select 1 from pg_proc where proname='generate_tier_code') as generate_tier_code_fn;
select column_default from information_schema.columns
  where table_schema='public' and table_name='tier_roster' and column_name='code'; -- expect it to call generate_tier_code()
-- redeem_tier_code, admin_bind_tier_code: see Section B.

-- 20260906000000 / 20260906010000 -- get_kenai_presence_state, see Section B.

-- 20260907000000_add_tide_cycle_high_and_gate_time_function.sql
select
  exists (select 1 from information_schema.columns where table_schema='public' and table_name='tide_cycles' and column_name='high_at_epoch_ms') as high_at_epoch_ms_column,
  exists (select 1 from pg_proc where proname='kenai_gate_time') as kenai_gate_time_fn;

-- 20260907010000_drop_low_at_or_below_gate_datum.sql
select exists (
  select 1 from information_schema.columns
  where table_schema='public' and table_name='tide_cycles' and column_name='low_at_or_below_gate_datum'
) as dead_column_still_present; -- expect false

-- 20260908000000_add_noaa_tide_cycle_ingestion.sql
select
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='tide_cycle_ingest_state') as ingest_state_table,
  exists (select 1 from pg_proc where proname='request_tide_cycle_refresh') as request_fn,
  exists (select 1 from pg_proc where proname='process_tide_cycle_response') as process_fn;
select jobname, schedule, active from cron.job
  where jobname in ('request-tide-cycle-refresh', 'process-tide-cycle-response'); -- expect 2 rows

-- 20260909000000 / 20260910000000 / 20260911000000 / 20260912000000 / 20260913000000 /
-- 20260914000000 / 20260915000000: covered by recent-migrations-audit.sql -- all ten confirmed
-- live 2026-09-08. get_kenai_presence_state's gate-time/holdover/ratchet markers are repeated in
-- Section B below too, so this file stays a complete, standalone audit on its own.


-- =========================================================================================
-- SECTION B: cascading version ladders. Read each block top to bottom -- the first TRUE you
-- hit (from the top) is the live version; everything below it should read false.
-- =========================================================================================

-- ---- get_kenai_presence_state (6 versions: 20260903030000 -> 20260906000000 -> 20260906010000
-- -> 20260909000000 -> 20260912000000 -> 20260913000000) ----
select
  fd like '%v_red_lower_bound_ms%' as is_20260913000000_departure_ratchet,
  (fd like '%v_holdover_ms%' and fd not like '%v_red_lower_bound_ms%') as is_20260912000000_holdover,
  (fd like '%v_gate_time_possible%' and fd not like '%v_holdover_ms%') as is_20260909000000_gate_times,
  (fd like '%v_anchorage_month_day%' and fd not like '%v_near_current_arrival%' and fd not like '%v_gate_time_possible%') as is_20260906010000_pure_recency_yellow,
  (fd like '%v_anchorage_month_day%' and fd like '%v_near_current_arrival%') as is_20260906000000_two_season_windows,
  (fd not like '%v_anchorage_month_day%') as is_20260903030000_original_single_window
from (select pg_get_functiondef('public.get_kenai_presence_state(bigint)'::regprocedure) as fd) t;

-- ---- get_watched_zone_statuses (5 versions: 20260829020000 -> 20260902000000 -> 20260902010000
-- -> 20260903000000 -> 20260903020000) ----
select
  fd like '%observer_tier = 2%' as is_20260903020000_tier_gated,
  (fd like '%is_whale_position_in_kenai_banner_area%' and fd not like '%observer_tier = 2%') as is_20260903000000_kenai_special_cased,
  (fd like '%is_whale_position_in_zone%' and fd not like '%is_whale_position_in_kenai_banner_area%') as is_20260902010000_river_proximity_fallback,
  (fd like '%s.whale_lat is not null%' and fd not like '%is_whale_position_in_zone%') as is_20260902000000_whale_position,
  (fd like '%s.lat is not null%' and fd not like '%s.whale_lat%') as is_20260829020000_original_observer_position
from (select pg_get_functiondef('public.get_watched_zone_statuses(bigint)'::regprocedure) as fd) t;

-- ---- export_sightings (4 versions: 20260827000000 -> 20260902000000 -> 20260902010000 ->
-- 20260903010000) -- signature itself changed in the last one (dropped and recreated), so this
-- checks the CURRENT signature's body directly:
select
  fd like '%observer_tier%' as is_20260903010000_current, -- expect true; if false, the call
  -- below will actually fail (old 7-arg signature no longer exists) -- that failure itself
  -- confirms an even older, unexpected state and is worth reporting back as-is.
  fd like '%is_whale_position_in_zone%' as has_river_proximity_fallback,
  fd like '%s.whale_lat%' as has_whale_position
from (
  select pg_get_functiondef('public.export_sightings(bigint, bigint, double precision, double precision, double precision, double precision, text)'::regprocedure) as fd
) t;

-- ---- is_whale_position_in_kenai_banner_area (4 versions: 20260903000000 -> 20260903040000 ->
-- 20260903060000 -> 20260904000000) ----
select
  fd like '%kenai_river_and_mouth_area%' as is_20260904000000_closed_seam,
  (fd like '%kenai_river_spike_line_red_containment_limit%' and fd not like '%kenai_river_and_mouth_area%') as is_20260903060000_13_5mi_containment,
  (fd like '%public.kenai_river_spike_line()%' ) as is_20260903040000_extracted_helpers,
  (fd like '%ST_DumpPoints%') as is_20260903000000_original_inline
from (select pg_get_functiondef('public.is_whale_position_in_kenai_banner_area(double precision, double precision, double precision)'::regprocedure) as fd) t;

-- ---- kenai_river_spike_line_beluga_limit (2 versions: 20260903050000 -> 20260903060000) ----
select
  fd like '%kenai_river_spike_line_truncated_at_miles%' as is_20260903060000_delegates_to_parameterized,
  fd like '%v_limit_meters%' as is_20260903050000_original_plpgsql_loop
from (select pg_get_functiondef('public.kenai_river_spike_line_beluga_limit()'::regprocedure) as fd) t;

-- ---- is_point_within_coastline_channel (3 versions: 20260830000000 -> 20260831010000 ->
-- 20260901010000) ----
select
  fd like '%v_river_max_segment_m%' as is_20260901010000_river_mouth_proximity,
  (fd like '%p_max_search_meters double precision DEFAULT 90000%' and fd not like '%v_river_max_segment_m%') as is_20260831010000_widened_radius,
  (fd like '%p_max_search_meters double precision DEFAULT 60000%') as is_20260830000000_original_radius
from (select pg_get_functiondef('public.is_point_within_coastline_channel(double precision, double precision, double precision)'::regprocedure) as fd) t;
-- ^ if this errors with "function does not exist", the argument list has more/fewer params
-- than assumed here -- open the file for the exact current signature and adjust the cast.

-- ---- redeem_tier_code (4 versions: 20260903010000 -> 20260905010000 -> 20260905020000 ->
-- 20260905030000) ----
select
  fd like '%normalize_tier_code(code) = v_normalized_code%' as is_20260905030000_normalizes_stored_value,
  (fd like '%where code = v_normalized_code%') as is_20260905020000_normalizes_input_only,
  (fd like '%v_recent_attempts%' and fd not like '%v_normalized_code%') as is_20260905010000_rate_limited_only,
  (fd not like '%v_recent_attempts%') as is_20260903010000_original_no_rate_limit
from (select pg_get_functiondef('public.redeem_tier_code(text, uuid)'::regprocedure) as fd) t;

-- ---- admin_bind_tier_code (3 versions: 20260905010000 -> 20260905020000 -> 20260905030000) ----
select
  fd like '%normalize_tier_code(code) = v_normalized_tier_code%' as is_20260905030000_normalizes_stored_value,
  (fd like '%where code = v_normalized_tier_code%') as is_20260905020000_normalizes_input_only,
  (fd not like '%v_normalized_tier_code%') as is_20260905010000_original_no_normalization
from (select pg_get_functiondef('public.admin_bind_tier_code(text, text)'::regprocedure) as fd) t;

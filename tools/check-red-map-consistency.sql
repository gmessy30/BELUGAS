-- Regression check: the status page's RED map (get_kenai_red_qualifying_sightings) must agree
-- with the banner (get_kenai_presence_state) at every moment.
--
-- WHY: get_kenai_red_qualifying_sightings duplicates the banner's RED window logic (cycle start,
-- item 117 grace, departure ratchet, sighting filter). When item 117's grace was added to the
-- banner only (20261001000000), the banner said RED while the map function returned 0 rows during
-- every grace window, and the status page silently hid its map (fixed by 20261015000000; the scan
-- below found 33 failing half-hour samples across 13 tide cycles before the fix, 0 after).
-- Any change to RED logic in get_kenai_presence_state must change get_kenai_red_qualifying_sightings
-- in the same migration; re-run this after both, and in a dry run with the migration prepended
-- before applying it.
--
-- READ-ONLY: one transaction that writes nothing and always rolls back. The result comes back as
-- the text of an exception (that is how it gets out of a DO block); "PASS" or "FAIL" is in the
-- first line. Run from the repo root:
--   & "$env:LOCALAPPDATA\supabase-cli\supabase.exe" db query --linked --file tools\check-red-map-consistency.sql
--
-- What it does: samples every half hour over the last v_days days, calls both functions with the
-- same pinned p_now_epoch_ms, and counts:
--   banner RED, 0 map rows   -- MUST be 0: the page would show RED with no map
--   banner RED, >=1 map row  -- the normal RED case
--   not RED, >=1 map row     -- should be 0 too; harmless to the page (it only draws on RED)
-- and lists every sample in the first and third groups with the tide row in force.
--
-- Caveat: neither function filters on created_at, so a pinned past "now" also counts sightings
-- reported later. That affects both sides equally, so it doesn't weaken the comparison.

begin;
do $$
declare
  v_days constant int := 30;
  out text := '';
  r record;
  n_red_empty int := 0;
  n_red_rows int := 0;
  n_notred_rows int := 0;
  n_total int := 0;
  details_red_empty text := '';
  details_notred_rows text := '';
begin
  for r in
    select x.t,
           (select b.phase from public.get_kenai_presence_state(x.t) b) as phase,
           (select count(*) from public.get_kenai_red_qualifying_sightings(x.t)) as map_rows,
           c.low_at_epoch_ms, c.low_height_m
      from (select (extract(epoch from g) * 1000)::bigint as t
              from generate_series(date_trunc('hour', now()) - make_interval(days => v_days), now(), interval '30 minutes') g) x
      left join lateral (select tc.low_at_epoch_ms, tc.low_height_m from public.tide_cycles tc
                          where tc.low_at_epoch_ms <= x.t order by tc.low_at_epoch_ms desc limit 1) c on true
     order by x.t
  loop
    n_total := n_total + 1;
    if r.phase = 'RED' and r.map_rows = 0 then
      n_red_empty := n_red_empty + 1;
      details_red_empty := details_red_empty || E'\n  ' || to_char(to_timestamp(r.t / 1000.0) at time zone 'America/Anchorage', 'Mon DD HH24:MI')
        || ' AKDT  banner=RED  low ' || coalesce(to_char(to_timestamp(r.low_at_epoch_ms / 1000.0) at time zone 'America/Anchorage', 'Mon DD HH24:MI'), 'none')
        || ' ' || coalesce(r.low_height_m::text, '?') || ' m, ' || coalesce(round((r.t - r.low_at_epoch_ms) / 60000.0)::text, '?') || ' min after low';
    elsif r.phase = 'RED' then
      n_red_rows := n_red_rows + 1;
    elsif r.map_rows > 0 then
      n_notred_rows := n_notred_rows + 1;
      details_notred_rows := details_notred_rows || E'\n  ' || to_char(to_timestamp(r.t / 1000.0) at time zone 'America/Anchorage', 'Mon DD HH24:MI')
        || ' AKDT  banner=' || r.phase || '  map rows=' || r.map_rows;
    end if;
  end loop;

  out := case when n_red_empty = 0 then 'PASS' else 'FAIL' end
    || E'\nsamples: ' || n_total || ' (every 30 min, last ' || v_days || ' days)'
    || E'\n  banner RED, 0 map rows:       ' || n_red_empty || '   (must be 0)'
    || E'\n  banner RED, >=1 map row:      ' || n_red_rows
    || E'\n  banner not RED, >=1 map row:  ' || n_notred_rows
    || E'\nfunction fingerprints: get_kenai_presence_state='
    || (select md5(pg_get_functiondef('public.get_kenai_presence_state'::regproc)))
    || ' get_kenai_red_qualifying_sightings='
    || (select md5(pg_get_functiondef('public.get_kenai_red_qualifying_sightings'::regproc)));
  if n_red_empty > 0 then out := out || E'\n--- banner RED with 0 map rows ---' || details_red_empty; end if;
  if n_notred_rows > 0 then out := out || E'\n--- banner not RED with map rows ---' || details_notred_rows; end if;
  raise exception 'RED MAP CONSISTENCY (read-only, rolled back): %', out;
end $$;
rollback;

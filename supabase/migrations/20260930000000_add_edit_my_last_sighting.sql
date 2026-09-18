-- Item 106: let a device edit its OWN most recent sighting, for a short window after reporting
-- it, without re-triggering the new-sighting alert.
--
-- WHY AN UPDATE IS SILENT BY CONSTRUCTION (confirmed against the LIVE database, not just this
-- repo's migration text -- `select pg_get_triggerdef(...) from pg_trigger where tgrelid =
-- 'public.sightings'::regclass and not tgisinternal` returns exactly two triggers, both
-- INSERT-only):
--   CREATE TRIGGER on_sighting_insert_notify          AFTER  INSERT ... notify_new_sighting()
--   CREATE TRIGGER on_sighting_insert_set_observer_tier BEFORE INSERT ... set_sighting_observer_tier()
-- So an UPDATE fires neither the FCM webhook (20260823000000) nor the observer-tier stamp
-- (20260903010000) -- the alert can't re-fire, and an edit can never change the row's tier. That
-- is a property of the current trigger set, not a guarantee: anything that later adds an
-- `after update` trigger to this table has to revisit this file.
--
-- WHAT STILL VALIDATES AN EDIT. Every integrity check the original INSERT went through is a
-- TABLE-level CHECK constraint, which Postgres applies to UPDATE identically -- nothing here
-- re-implements (or weakens) them:
--   - sightings_whale_position_outer_geofence_check (20260920000000) -- the coarse Cook Inlet
--     box, and the "both position columns or neither" rule
--   - sightings_activities_allowed_check            (20260923000000) -- the allowed activity values
--   - sightings_activity_note_length_check          (20260923000000) -- the 140-char note cap
-- The sightings INSERT policy itself is `with check (true)` (verified live via pg_policies), so
-- there is no policy-level validation to mirror either. The finer client-side geofence judgment
-- (is_geofence_verified) is not computed server-side on insert and is not computed here either --
-- it is SUPPLIED by the caller on both paths, identically; see the "is_geofence_verified" note
-- below for the rule an edit has to follow because of that.
--
-- TRUST MODEL, unchanged from every other subscriber_id-keyed RPC in this schema
-- (confirm_sighting, report_kenai_departure, redeem_tier_code): p_subscriber_id is a
-- client-generated uuid the caller asserts, not an authenticated identity. Anyone who knows
-- another device's subscriber_id could edit that device's last sighting inside the window --
-- exactly as they could already claim its tier-1 privileges. Treating subscriber_id as the
-- device's own secret is a pre-existing, deliberate property of this app's anon-only design, not
-- something this item introduces or is in a position to fix on its own.

-- =========================================================================================
-- edited_at + the edit window
-- =========================================================================================

alter table public.sightings
  add column if not exists edited_at timestamptz;

-- Column-level SELECT grant, same as every other anon-readable sightings column (the table's
-- grants are column-level because subscriber_id/confirmed_by_subscriber_id are deliberately NOT
-- readable -- see 20260903010000's lockdown). Granted to authenticated as well, per item 96's
-- audit: any device that has ever signed into /webapp/admin/ makes ordinary main-app requests as
-- `authenticated`, since Supabase Auth's token is shared across the whole origin.
grant select (edited_at) on public.sightings to anon, authenticated;

-- ONE definition of the window, shared by both functions below so they can never drift apart
-- (the client shows/hides its EDIT button off get_my_editable_sighting; edit_my_last_sighting
-- enforces it -- the two disagreeing would mean a button that always fails). Easy to tune: this
-- is the only place the 4 hours is written down.
create or replace function public.sighting_edit_window()
returns interval
language sql
immutable
as $$
  select interval '4 hours'
$$;

revoke execute on function public.sighting_edit_window from public;
grant execute on function public.sighting_edit_window to anon, authenticated;


-- =========================================================================================
-- get_my_editable_sighting: which row (if any) this subscriber may currently edit.
--
-- The client genuinely cannot work this out for itself: subscriber_id has no anon SELECT grant
-- at all, so a fetched sighting carries nothing that identifies it as this device's own -- the
-- same reason confirm_sighting's own client-side visibility check deliberately does NOT try to
-- exclude the caller's own rows. This is purely a visibility signal (which is the same "visibility
-- is UX, enforcement is server-side" split used by is_tier_one_observer/
-- is_kenai_departure_reporter): edit_my_last_sighting re-derives the target row and re-checks the
-- window itself, regardless of what this ever returned.
--
-- Returns 0 rows when there is nothing editable (no sightings from this subscriber, or the newest
-- one has aged out) -- never an error, so "the window closed" is an ordinary empty result the
-- client renders as "no EDIT button", with no dead end and no message. Both halves of that are
-- enforced in the WHERE clause below; an earlier draft computed editable_until but forgot to
-- filter on it, which showed a button that could only ever fail.
-- =========================================================================================

create or replace function public.get_my_editable_sighting(p_subscriber_id uuid)
returns table (
  sighting_id uuid,
  editable_until timestamptz
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select s.id, s.created_at + public.sighting_edit_window()
  from public.sightings s
  where p_subscriber_id is not null
    and s.subscriber_id = p_subscriber_id
    and s.created_at is not null
    -- The WINDOW, not just the deadline arithmetic. Without this, an aged-out row is still named
    -- here and the client draws an EDIT button on it that edit_my_last_sighting then refuses --
    -- precisely the dead end this feature is supposed to avoid by simply not offering the button.
    and s.created_at >= now() - public.sighting_edit_window()
  -- `nulls last` is belt-and-braces: created_at is nullable on this table (it predates the
  -- default), and a null created_at row is excluded above anyway since it can't be windowed.
  order by s.created_at desc nulls last, s.id desc
  limit 1
$$;

revoke execute on function public.get_my_editable_sighting from public;
grant execute on function public.get_my_editable_sighting to anon, authenticated;


-- =========================================================================================
-- edit_my_last_sighting: the sole write path for an ordinary (non-tier-1, non-admin) correction
-- to an already-submitted sighting.
--
-- p_updates is a jsonb PATCH, not a full row: a key being PRESENT means "set this field to this
-- value" (including an explicit json null, which clears it); a key being ABSENT means "leave this
-- field exactly as it is". A jsonb patch rather than named nullable parameters precisely because
-- of that distinction -- with named params there is no way to tell "clear the travel bearing"
-- apart from "don't touch the travel bearing", and both are real things the client needs to say.
--
-- WHAT IT REFUSES (each collapses to a plain `false`, matching confirm_sighting/
-- report_kenai_departure's single-boolean design -- the client's own EDIT button visibility
-- already covers the ordinary "why is this even offered" case; this is the enforcement):
--   - the caller has no sightings at all
--   - the target row is not that subscriber's own (subscriber_id mismatch)
--   - the target row is not that subscriber's SINGLE most recent one (a newer sighting exists)
--   - the target row was created more than sighting_edit_window() ago
-- A MALFORMED request raises instead of returning false -- an unknown key, a half-specified
-- position, a negative count, a non-https photo url. Those can only come from a client bug or a
-- hand-crafted call, and silently succeeding-but-ignoring (or silently failing) either one would
-- hide a real defect; the ordinary refusals above are expected states, a malformed patch is not.
--
-- WHAT IT NEVER TOUCHES, regardless of what the patch contains (the allowlist below is the
-- enforcement -- any key outside it raises rather than being quietly dropped):
--   - observer_tier                        -- tier at SUBMISSION time; an edit is not a re-submission
--   - confirmed_at / confirmed_by_subscriber_id -- a tier-1 vouch belongs to confirm_sighting alone
--   - observed_at_epoch_ms                 -- WHEN it was seen is the one claim an edit can't revise;
--                                             allowing it would let a row be walked forward through
--                                             tide cycles, which is exactly what drives RED/YELLOW
--   - subscriber_id / created_at / id      -- identity of the row and of its reporter
--
-- is_geofence_verified IS editable, but ONLY as part of a position change, and a position change
-- REQUIRES it -- the two are checked as a unit below, in both directions:
--   - whale_lat/whale_lng patched WITHOUT is_geofence_verified  -> raises. Leaving the pre-edit
--     flag on a post-edit position is exactly the staleness this rule exists to prevent: a row
--     verified where it was first placed would stay flagged verified after being moved somewhere
--     that never would have earned it.
--   - is_geofence_verified patched WITHOUT a position           -> raises. There is nothing to
--     re-derive the flag FROM, so a lone flag patch could only ever be an attempt to flip it.
-- The value itself is computed by the CLIENT and asserted here, which is precisely what the
-- INSERT path already does -- the check is CoastlineGeometry's real polygon/centerline data
-- (GeofenceUtils on native, geofence.js's isWhalePositionVerified plus its coastline-channel
-- fallback and the user's own SAVE ANYWAY override on the web), none of which exists in SQL. So
-- an edit is exactly as client-trusted on this column as the original insert was -- the same
-- documented gap (20260819000000's "KNOWN GAP", restated by 20260920000000), neither widened nor
-- narrowed. What this rule does buy is that the flag can never silently describe a DIFFERENT
-- position than the one it was computed for.
-- =========================================================================================

create or replace function public.edit_my_last_sighting(
  p_subscriber_id uuid,
  p_updates jsonb
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_allowed_keys constant text[] := array[
    'count_whites', 'count_greys', 'count_calves', 'count_unknown',
    'activities', 'activity_note', 'photo_url',
    'whale_lat', 'whale_lng', 'is_geofence_verified', 'travel_bearing_degrees'
  ];
  v_key text;
  v_count_key text;
  v_photo_url text;
begin
  if p_subscriber_id is null or p_updates is null or jsonb_typeof(p_updates) <> 'object' then
    return false;
  end if;

  -- Nothing to do is not a failure, but it must not stamp edited_at either.
  if p_updates = '{}'::jsonb then
    return false;
  end if;

  for v_key in select k from jsonb_object_keys(p_updates) as k
  loop
    if not (v_key = any (v_allowed_keys)) then
      raise exception 'edit_my_last_sighting: unsupported update key %', v_key
        using errcode = '22023';
    end if;
  end loop;

  -- The position columns are a pair, both in meaning and in the table's own CHECK constraint
  -- (whale_lat/whale_lng must be both null or both set) -- patching one alone is always a bug.
  if (p_updates ? 'whale_lat') <> (p_updates ? 'whale_lng') then
    raise exception 'edit_my_last_sighting: whale_lat and whale_lng must be patched together'
      using errcode = '22023';
  end if;

  -- A moved whale gets a freshly-computed verification flag, or the edit is refused -- see the
  -- is_geofence_verified note in this function's header for both directions of this rule.
  if (p_updates ? 'whale_lat') <> (p_updates ? 'is_geofence_verified') then
    raise exception 'edit_my_last_sighting: is_geofence_verified must be patched with, and only with, the position'
      using errcode = '22023';
  end if;

  -- NOT NULL on the table, so a null here would fail the write anyway -- caught early, with a
  -- message that says which field was wrong instead of a bare constraint violation.
  if (p_updates ? 'is_geofence_verified')
     and jsonb_typeof(p_updates -> 'is_geofence_verified') <> 'boolean' then
    raise exception 'edit_my_last_sighting: is_geofence_verified must be a boolean'
      using errcode = '22023';
  end if;

  foreach v_count_key in array array['count_whites', 'count_greys', 'count_calves', 'count_unknown']
  loop
    if (p_updates ? v_count_key)
       and coalesce((p_updates ->> v_count_key)::int, 0) < 0 then
      raise exception 'edit_my_last_sighting: % must not be negative', v_count_key
        using errcode = '22023';
    end if;
  end loop;

  -- Stricter than INSERT, which never validated this column at all: an edit is the one place a
  -- url is being replaced rather than written once by our own upload path, so a patched value
  -- that isn't an https url is rejected outright rather than rendered as an <img src> later.
  if p_updates ? 'photo_url' then
    v_photo_url := p_updates ->> 'photo_url';
    if v_photo_url is not null and v_photo_url !~ '^https://' then
      raise exception 'edit_my_last_sighting: photo_url must be an https url'
        using errcode = '22023';
    end if;
  end if;

  -- ONE statement does the whole job, so every refusal rule is evaluated atomically against the
  -- row as it exists at commit time -- no SELECT-then-UPDATE race (same pattern confirm_sighting
  -- and redeem_tier_code's claim already use). In particular the `id = (select ... limit 1)`
  -- subquery is what enforces "the caller's SINGLE most recent sighting": a sighting inserted
  -- between the client deciding to show EDIT and this call landing moves that target, and this
  -- update then matches zero rows rather than silently editing the older one.
  update public.sightings s
     set count_whites = case when p_updates ? 'count_whites'
                             then (p_updates ->> 'count_whites')::int else s.count_whites end,
         count_greys = case when p_updates ? 'count_greys'
                            then (p_updates ->> 'count_greys')::int else s.count_greys end,
         count_calves = case when p_updates ? 'count_calves'
                             then (p_updates ->> 'count_calves')::int else s.count_calves end,
         count_unknown = case when p_updates ? 'count_unknown'
                              then (p_updates ->> 'count_unknown')::int else s.count_unknown end,
         -- An empty array patches to NULL (array_agg over zero rows), matching the client's own
         -- "null, not []" convention for "no activities selected" on insert.
         activities = case
                        when not (p_updates ? 'activities') then s.activities
                        when jsonb_typeof(p_updates -> 'activities') = 'null' then null
                        else (select array_agg(a) from jsonb_array_elements_text(p_updates -> 'activities') as a)
                      end,
         activity_note = case when p_updates ? 'activity_note'
                              then p_updates ->> 'activity_note' else s.activity_note end,
         photo_url = case when p_updates ? 'photo_url'
                          then p_updates ->> 'photo_url' else s.photo_url end,
         whale_lat = case when p_updates ? 'whale_lat'
                          then (p_updates ->> 'whale_lat')::double precision else s.whale_lat end,
         whale_lng = case when p_updates ? 'whale_lng'
                          then (p_updates ->> 'whale_lng')::double precision else s.whale_lng end,
         -- Always moves WITH the position (enforced above), never on its own.
         is_geofence_verified = case when p_updates ? 'is_geofence_verified'
                                     then (p_updates ->> 'is_geofence_verified')::boolean
                                     else s.is_geofence_verified end,
         travel_bearing_degrees = case when p_updates ? 'travel_bearing_degrees'
                                       then (p_updates ->> 'travel_bearing_degrees')::double precision
                                       else s.travel_bearing_degrees end,
         -- Derived, never patched directly: travel_bearing_source describes WHERE the bearing came
         -- from, and every bearing this app can set is the BearingDial's own MANUAL one. Clearing
         -- the bearing clears the source with it, so the pair can't end up saying "MANUAL" about a
         -- bearing that no longer exists.
         travel_bearing_source = case
                                   when not (p_updates ? 'travel_bearing_degrees') then s.travel_bearing_source
                                   when (p_updates ->> 'travel_bearing_degrees') is null then null
                                   else 'MANUAL'
                                 end,
         edited_at = now()
   where s.subscriber_id = p_subscriber_id
     and s.created_at is not null
     and s.created_at >= now() - public.sighting_edit_window()
     and s.id = (
       select s2.id
       from public.sightings s2
       where s2.subscriber_id = p_subscriber_id
       order by s2.created_at desc nulls last, s2.id desc
       limit 1
     );

  return found;
end;
$$;

revoke execute on function public.edit_my_last_sighting from public;
grant execute on function public.edit_my_last_sighting to anon, authenticated;

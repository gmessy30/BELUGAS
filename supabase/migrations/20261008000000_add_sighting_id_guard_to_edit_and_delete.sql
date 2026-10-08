-- Item 120: close the edit race, and give edit/delete a reason when they refuse.
--
-- Why: edit_my_last_sighting(p_subscriber_id, p_updates) takes no row id. Its UPDATE targets
-- "the caller's newest row AT SAVE TIME". If a newer report of theirs lands while the edit screen
-- is open (another device, or the offline queue syncing), Save writes the older report's
-- pre-filled values onto the NEWER row. Its own comment claims it "matches zero rows" then; it
-- doesn't -- the newer row passes every check. delete_my_last_sighting (item 119) already takes
-- the row id, so it has no race, but like edit it returns a bare false for every refusal.
--
-- What this adds:
--   edit_my_sighting(p_subscriber_id, p_sighting_id, p_updates)  -> jsonb {"status": ...}
--   delete_my_sighting(p_subscriber_id, p_sighting_id)           -> jsonb {"status": ...}
-- status is one of:
--   "ok"          -- changed (edit) / deleted (delete)
--   "superseded"  -- the row is the caller's, inside the window, but a newer one of theirs exists
--   "expired"     -- the row is the caller's but outside sighting_edit_window()
--   "not_found"   -- no such row, someone else's row, or a null argument (indistinguishable on
--                    purpose: a caller learns nothing about rows that aren't theirs)
--   "no_changes"  -- edit only: an empty patch; nothing written, edited_at not stamped
-- Malformed patches still RAISE exactly as before (a client bug, not a user state).
--
-- New NAMES rather than overloads: delete_my_last_sighting already has (p_subscriber_id,
-- p_sighting_id), so a jsonb-returning version with the same argument names can't sit beside it,
-- and PostgREST picks overloads by argument names, which makes same-name pairs fragile. The old
-- names stay as thin wrappers (below) so older cached copies of the PWA keep working. The native
-- app calls none of these functions.
--
-- The write is still ONE statement (no SELECT-then-UPDATE race): ownership, window and
-- "still the newest" are all evaluated atomically in its WHERE. Only when it matches nothing does
-- a follow-up read classify why -- that read can't change what was written.
--
-- Unchanged: get_my_editable_sighting, sighting_edit_window(), the sightings table, its columns,
-- RLS policies and triggers. Grants: EXECUTE on the two new functions to anon and authenticated,
-- matching the existing pair. No other grant changes.

-- Shared classification, used only after a write matched nothing.
create or replace function public.classify_my_sighting_refusal(p_subscriber_id uuid, p_sighting_id uuid)
returns text
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select case
    when v.id is null or v.subscriber_id is distinct from p_subscriber_id then 'not_found'
    when v.created_at is null or v.created_at < now() - public.sighting_edit_window() then 'expired'
    when exists (
      select 1 from public.sightings s2
      where s2.subscriber_id = p_subscriber_id
        and (s2.created_at > v.created_at or (s2.created_at = v.created_at and s2.id > v.id))
    ) then 'superseded'
    else 'not_found' -- e.g. deleted between the write and this read
  end
  from (select 1) one
  left join public.sightings v on v.id = p_sighting_id
$$;

revoke all on function public.classify_my_sighting_refusal(uuid, uuid) from public, anon, authenticated;

create or replace function public.edit_my_sighting(p_subscriber_id uuid, p_sighting_id uuid, p_updates jsonb)
returns jsonb
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
  if p_subscriber_id is null or p_sighting_id is null then
    return jsonb_build_object('status', 'not_found');
  end if;
  if p_updates is null or jsonb_typeof(p_updates) <> 'object' then
    raise exception 'edit_my_sighting: p_updates must be a json object' using errcode = '22023';
  end if;
  if p_updates = '{}'::jsonb then
    return jsonb_build_object('status', 'no_changes');
  end if;

  -- Patch validation: identical rules and messages to edit_my_last_sighting (20260930000000).
  for v_key in select k from jsonb_object_keys(p_updates) as k loop
    if not (v_key = any (v_allowed_keys)) then
      raise exception 'edit_my_sighting: unsupported update key %', v_key using errcode = '22023';
    end if;
  end loop;
  if (p_updates ? 'whale_lat') <> (p_updates ? 'whale_lng') then
    raise exception 'edit_my_sighting: whale_lat and whale_lng must be patched together' using errcode = '22023';
  end if;
  if (p_updates ? 'whale_lat') <> (p_updates ? 'is_geofence_verified') then
    raise exception 'edit_my_sighting: is_geofence_verified must be patched with, and only with, the position'
      using errcode = '22023';
  end if;
  if (p_updates ? 'is_geofence_verified') and jsonb_typeof(p_updates -> 'is_geofence_verified') <> 'boolean' then
    raise exception 'edit_my_sighting: is_geofence_verified must be a boolean' using errcode = '22023';
  end if;
  foreach v_count_key in array array['count_whites', 'count_greys', 'count_calves', 'count_unknown'] loop
    if (p_updates ? v_count_key) and coalesce((p_updates ->> v_count_key)::int, 0) < 0 then
      raise exception 'edit_my_sighting: % must not be negative', v_count_key using errcode = '22023';
    end if;
  end loop;
  if p_updates ? 'photo_url' then
    v_photo_url := p_updates ->> 'photo_url';
    if v_photo_url is not null and v_photo_url !~ '^https://' then
      raise exception 'edit_my_sighting: photo_url must be an https url' using errcode = '22023';
    end if;
  end if;

  -- The SAME column logic as edit_my_last_sighting; the only change is `s.id = p_sighting_id`,
  -- which pins the write to the row the user was actually editing.
  update public.sightings s
     set count_whites = case when p_updates ? 'count_whites' then (p_updates ->> 'count_whites')::int else s.count_whites end,
         count_greys = case when p_updates ? 'count_greys' then (p_updates ->> 'count_greys')::int else s.count_greys end,
         count_calves = case when p_updates ? 'count_calves' then (p_updates ->> 'count_calves')::int else s.count_calves end,
         count_unknown = case when p_updates ? 'count_unknown' then (p_updates ->> 'count_unknown')::int else s.count_unknown end,
         activities = case
                        when not (p_updates ? 'activities') then s.activities
                        when jsonb_typeof(p_updates -> 'activities') = 'null' then null
                        else (select array_agg(a) from jsonb_array_elements_text(p_updates -> 'activities') as a)
                      end,
         activity_note = case when p_updates ? 'activity_note' then p_updates ->> 'activity_note' else s.activity_note end,
         photo_url = case when p_updates ? 'photo_url' then p_updates ->> 'photo_url' else s.photo_url end,
         whale_lat = case when p_updates ? 'whale_lat' then (p_updates ->> 'whale_lat')::double precision else s.whale_lat end,
         whale_lng = case when p_updates ? 'whale_lng' then (p_updates ->> 'whale_lng')::double precision else s.whale_lng end,
         is_geofence_verified = case when p_updates ? 'is_geofence_verified'
                                     then (p_updates ->> 'is_geofence_verified')::boolean else s.is_geofence_verified end,
         travel_bearing_degrees = case when p_updates ? 'travel_bearing_degrees'
                                       then (p_updates ->> 'travel_bearing_degrees')::double precision
                                       else s.travel_bearing_degrees end,
         travel_bearing_source = case
                                   when not (p_updates ? 'travel_bearing_degrees') then s.travel_bearing_source
                                   when (p_updates ->> 'travel_bearing_degrees') is null then null
                                   else 'MANUAL'
                                 end,
         edited_at = now()
   where s.id = p_sighting_id
     and s.subscriber_id = p_subscriber_id
     and s.created_at is not null
     and s.created_at >= now() - public.sighting_edit_window()
     and s.id = (
       select s2.id from public.sightings s2
       where s2.subscriber_id = p_subscriber_id
       order by s2.created_at desc nulls last, s2.id desc
       limit 1
     );

  if found then
    return jsonb_build_object('status', 'ok');
  end if;
  return jsonb_build_object('status', public.classify_my_sighting_refusal(p_subscriber_id, p_sighting_id));
end;
$$;

create or replace function public.delete_my_sighting(p_subscriber_id uuid, p_sighting_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if p_subscriber_id is null or p_sighting_id is null then
    return jsonb_build_object('status', 'not_found');
  end if;

  -- The same single statement as delete_my_last_sighting (20261002000000).
  delete from public.sightings s
   where s.id = p_sighting_id
     and s.subscriber_id = p_subscriber_id
     and s.created_at is not null
     and s.created_at >= now() - public.sighting_edit_window()
     and s.id = (
       select s2.id from public.sightings s2
       where s2.subscriber_id = p_subscriber_id
       order by s2.created_at desc nulls last, s2.id desc
       limit 1
     );

  if found then
    return jsonb_build_object('status', 'ok');
  end if;
  return jsonb_build_object('status', public.classify_my_sighting_refusal(p_subscriber_id, p_sighting_id));
end;
$$;

-- Postgres grants EXECUTE to PUBLIC on every new function by default (seen in the Oct 8 dry run);
-- the existing edit/delete pair have no PUBLIC grant, so match them: revoke it, then grant the
-- two client roles explicitly.
revoke execute on function public.edit_my_sighting(uuid, uuid, jsonb) from public;
revoke execute on function public.delete_my_sighting(uuid, uuid) from public;
grant execute on function public.edit_my_sighting(uuid, uuid, jsonb) to anon, authenticated;
grant execute on function public.delete_my_sighting(uuid, uuid) to anon, authenticated;

-- Old signatures, kept for older cached PWA copies. Same names, same arguments, same boolean
-- return, so nothing calling them breaks.
--
-- edit_my_last_sighting: resolves "the caller's newest row" itself (the same expression it always
-- used) and hands that id to edit_my_sighting. That is EXACTLY today's behavior, race included --
-- an old client can't send an id, so the wrapper can't protect it; only the new client is
-- protected. Returns true only for "ok"; every refusal and "no_changes" is false, as before.
-- Malformed patches still raise, as before.
create or replace function public.edit_my_last_sighting(p_subscriber_id uuid, p_updates jsonb)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_newest uuid;
begin
  if p_subscriber_id is null or p_updates is null or jsonb_typeof(p_updates) <> 'object' then
    return false;
  end if;
  select s2.id into v_newest
  from public.sightings s2
  where s2.subscriber_id = p_subscriber_id
  order by s2.created_at desc nulls last, s2.id desc
  limit 1;
  if v_newest is null then
    return false;
  end if;
  return (public.edit_my_sighting(p_subscriber_id, v_newest, p_updates) ->> 'status') = 'ok';
end;
$$;

-- delete_my_last_sighting: already takes the id; now a one-line wrapper so the delete rules live
-- in one place. true only for "ok".
create or replace function public.delete_my_last_sighting(p_subscriber_id uuid, p_sighting_id uuid)
returns boolean
language sql
security definer
set search_path = public, pg_temp
as $$
  select (public.delete_my_sighting(p_subscriber_id, p_sighting_id) ->> 'status') = 'ok'
$$;

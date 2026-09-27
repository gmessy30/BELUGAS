-- Item 119: let a device DELETE its own most recent sighting, under exactly the same rules as
-- item 106's edit_my_last_sighting (20260930000000) -- own device, single most recent row, inside
-- sighting_edit_window(). Hard delete: the row is gone, nothing is kept.
--
-- SAME GUARDS AS EDIT, SHARED RATHER THAN RE-WRITTEN. The window is sighting_edit_window() itself
-- (not a second copy of "4 hours"), and visibility is get_my_editable_sighting itself -- the client
-- shows DELETE on exactly the row it already shows EDIT on, so the two buttons can never disagree
-- about which row is yours or whether the window is still open. Nothing in this file changes either
-- of those functions or edit_my_last_sighting.
--
-- ONE DELIBERATE DIFFERENCE FROM EDIT'S SIGNATURE: p_sighting_id. edit_my_last_sighting takes no
-- row id and acts on whichever row is newest at call time. For a delete that is not acceptable: a
-- report the offline queue syncs between the user tapping DELETE and confirming it would become
-- "newest", and an id-less delete would then remove THAT one -- a report the user never looked at
-- -- irreversibly. So the caller names the row it means, and the delete only happens if that row is
-- ALSO still the caller's single most recent one. A mismatch is the "not the newest" refusal.
-- (The same race exists in edit_my_last_sighting -- its own comment claims the update "matches zero
-- rows rather than silently editing the older one", but with no row id it actually edits the NEWER
-- one with the older row's pre-filled values. Recorded here, not fixed: that function touches
-- sightings, so it needs its own reviewed change.)
--
-- WHAT IT REFUSES -- each a plain `false`, exactly as edit_my_last_sighting does, so the client
-- handles both the same way:
--   - null subscriber id / null sighting id
--   - the named row doesn't exist, or isn't this subscriber's own (subscriber_id mismatch)
--   - the named row is not this subscriber's SINGLE most recent one (a newer sighting exists)
--   - the named row was created more than sighting_edit_window() ago (or has no created_at)
-- A consequence worth stating, not a bug: once the newest row is deleted, the caller's PREVIOUS row
-- becomes the newest, and is itself deletable if it too is still inside the window -- the same way
-- it would become editable. The window still bounds how far back that can reach.
--
-- WHAT A DELETE CANNOT UNDO:
--   - Push alerts already sent for the row (on_sighting_insert_notify fired at INSERT time).
--   - The photo object, if any, in the public sighting-photos bucket. anon/authenticated have no
--     storage DELETE policy, and removing storage.objects rows from SQL is what Supabase blocks
--     (it would orphan the underlying file). The photo_url stops being referenced anywhere in the
--     app the moment the row is gone, but the file itself stays until removed by hand.
-- Presence state (get_kenai_presence_state) is computed live from `sightings`, so deleting a
-- RED/YELLOW-qualifying row changes the banner on the next read -- which is the point of deleting a
-- mistaken report.
--
-- WHY THIS IS SAFE TO ADD ALONGSIDE THE EXISTING GRANTS (verified live, not from migration text):
--   - No foreign key anywhere references public.sightings (pg_constraint.confrelid), so a hard
--     delete can't cascade into, or be blocked by, another table.
--   - No DELETE trigger exists on public.sightings -- only on_sighting_insert_notify (AFTER INSERT)
--     and on_sighting_insert_set_observer_tier (BEFORE INSERT).
--   - anon/authenticated hold a table-level DELETE grant, but RLS is on and there is NO delete
--     policy, so a direct DELETE through PostgREST matches zero rows. This SECURITY DEFINER function
--     is the only way a sighting can be deleted by a client; this file adds no policy.
--
-- TRUST MODEL: unchanged from edit_my_last_sighting / confirm_sighting -- p_subscriber_id is the
-- device's own client-generated uuid, asserted, not authenticated. See 20260930000000's header.

create or replace function public.delete_my_last_sighting(
  p_subscriber_id uuid,
  p_sighting_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if p_subscriber_id is null or p_sighting_id is null then
    return false;
  end if;

  -- ONE statement, the same shape as edit_my_last_sighting's UPDATE, so every refusal rule is
  -- evaluated atomically against the rows as they exist when it runs -- no SELECT-then-DELETE race.
  -- The subquery is the SAME "single most recent" expression edit uses (same ordering, same
  -- tie-break), so the two functions always agree on which row is the newest.
  delete from public.sightings s
   where s.id = p_sighting_id
     and s.subscriber_id = p_subscriber_id
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

revoke execute on function public.delete_my_last_sighting from public;
grant execute on function public.delete_my_last_sighting to anon, authenticated;

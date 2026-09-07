-- Widens tier_roster.tier's check constraint from (1, 2) to (1, 2, 3), so a row can be
-- explicitly demoted rather than only ever revoked by unbinding.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.
--
-- REVOCATION POLICY, settled: one code per device, a new code issued on request. If a phone is
-- lost, stolen, or dead, the admin finds the OWNER'S EXISTING ROW and reassigns it to tier 3,
-- rather than clearing claimed_subscriber_id the way an ordinary revoke would (see this table's
-- own header comment in 20260903010000_add_observer_tier_system.sql on why a cleared code stays
-- permanently burned -- that's still true here; tier 3 is a different operation from a revoke,
-- not a replacement for one).
--
-- WHAT TIER 3 MEANS: a demoted device -- functionally identical to an unclaimed one everywhere
-- the app actually makes a decision off tier, but still traceable to its owner in the roster.
-- Every current consumer of observer_tier is an ALLOW-LIST, not a null-check, so this requires
-- no other change:
--   - get_kenai_presence_state's RED-eligibility (and every earlier copy of the same tidal-cycle
--     predicate): (observer_tier = 2 and observer_type = 'SELF') or observer_tier = 1 -- 3 fails
--     both branches, exactly like null does today.
--   - SightingRecord.isHighConfidence (client): observerTier == 1 || observerTier == 2 -- same
--     shape, same result for 3 as for null.
-- get_observer_tier itself has no filter on the tier value -- it already returns whatever's in
-- the row -- and set_sighting_observer_tier (the BEFORE INSERT trigger) already calls it fresh
-- on every insert, never cached. So a demotion takes effect on that device's very next submitted
-- sighting with no app change and no redeploy, the same way any other roster edit already does.
--
-- WHY 3 IS NOT JUST A NEW NUMBER: "tier-3" already exists informally in this codebase as prose
-- for "no elevation" -- see SightingRecord.kt's own isHighConfidence comment ("hiding plain
-- manual tier-3 reports"), written when the only way to reach that state was an absent roster
-- row (get_observer_tier returning null). This migration makes that vocabulary a real stored
-- value for one additional path into the same state -- a row that still exists and is still
-- claimed, just no longer privileged -- rather than introducing a new concept.
--
-- WHY REASSIGN INSTEAD OF UNBIND: unbinding (clearing claimed_subscriber_id) frees the row for
-- a DIFFERENT person to claim via a fresh code, and burns the ability to trace it back to this
-- owner at all. Setting tier=3 in place keeps claimed_subscriber_id and is_used exactly as they
-- are -- the admin can still find this device by its owner's name/contact in tier_roster, which
-- is the entire point: distinguishable in the roster, indistinguishable in the app.
alter table public.tier_roster
  drop constraint if exists tier_roster_tier_check;

alter table public.tier_roster
  add constraint tier_roster_tier_check check (tier in (1, 2, 3));

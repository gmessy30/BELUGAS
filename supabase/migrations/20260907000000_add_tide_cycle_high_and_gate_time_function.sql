-- Foundation for the entrance-gate floor that replaces the fitted delay model (see the wider
-- design discussion this migration comes out of). Adds the missing half of the tide geometry
-- each row needs -- the high following its own low -- and the pure formula that turns a
-- low/high pair into "the tide has risen to depth g" for an arbitrary gate depth g.
--
-- Deliberately scoped to ONLY the schema and the formula here: nothing yet reads these columns
-- or calls this function. get_kenai_presence_state, the NOAA ingestion path, and the client
-- display all come later, once the formula itself is verified against real logbook gate times.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

-- =========================================================================================
-- tide_cycles.high_at_epoch_ms / high_height_m: the high tide that follows THIS row's own low
-- -- i.e. the rising limb of this cycle, paired with the row's existing low_at_epoch_ms/
-- low_height_m. Named plainly (not "next_high_*") because the row already scopes it to "this
-- cycle starting at this low" -- "next" would risk reading as the NEXT ROW's low, a different,
-- already-existing concept (get_kenai_presence_state's own next_cycle_low_epoch_ms output).
-- Both nullable: populated once NOAA data covers this cycle's high, same as low_height_m being
-- nullable today for a cycle-boundary row that predates any height data.
-- =========================================================================================
alter table public.tide_cycles
  add column if not exists high_at_epoch_ms bigint,
  add column if not exists high_height_m double precision;

-- =========================================================================================
-- kenai_gate_time: the time a rising tide between (t_low, h_low) and (t_high, h_high) first
-- reaches height g, modeling the rise as the cosine curve
--   h(t) = h_low + R * (1 - cos(pi*tau)) / 2,  tau = (t - t_low)/D,  D = t_high - t_low,
--   R = h_high - h_low
-- solved for t:
--   t_gate = t_low + D * acos(1 - 2*(g - h_low)/R) / pi
--
-- Verified against 18 hand-tabulated gate times (0.0/0.3/0.5/0.8m, August-December, one
-- observer's own logbook) -- see the verification report alongside this migration. Deliberately
-- returns the RAW formula result with NO safety margin applied -- the "err early" 3-minute
-- offset belongs to the CALLER (get_kenai_presence_state), as a named constant applied on top of
-- this function's result, not folded in here. Keeping this function pure and margin-free is what
-- makes it directly comparable against the logbook's own gate times during verification, and
-- keeps the offset a single, visible, deliberate subtraction rather than something buried inside
-- a formula nobody will think to look for it in.
--
-- IMMUTABLE, not STABLE: unlike get_kenai_presence_state, this takes every input as a parameter
-- and touches no table or now() -- a pure function of its arguments.
--
-- Two guards, both purely DEFENSIVE (see each one's own comment) -- neither is expected to ever
-- fire against real data:
--   - d <= 0 or r <= 0: a rising limb always has t_high > t_low and h_high > h_low; only a
--     malformed row (transposed high/low, a duplicate timestamp/height from a bad fetch) would
--     violate that.
--   - h_high < g: real highs at the Kenai River mouth run roughly 4.3-7.6m across the whole
--     season (and only rising with sea level), nowhere near either gate depth (0.3m/0.8m) --
--     this is NOT a real tidal case, just protection against a bad row (null height, malformed
--     fetch, transposed high/low) reaching acos() with an out-of-domain argument.
--
-- h_low >= g is the one non-defensive early-return: the tide never drops below the gate at all
-- this cycle, so the gate is open from the tide turn itself -- the floor IS t_low.
-- =========================================================================================
create or replace function public.kenai_gate_time(
  t_low bigint,
  h_low double precision,
  t_high bigint,
  h_high double precision,
  g double precision
)
returns bigint
language plpgsql
immutable
as $$
declare
  d double precision;
  r double precision;
  arg double precision;
begin
  if t_low is null or h_low is null or t_high is null or h_high is null or g is null then
    return null;
  end if;

  d := t_high - t_low;
  r := h_high - h_low;

  -- Defensive only -- see header comment.
  if d <= 0 or r <= 0 then
    return null;
  end if;

  -- Already at/above gate depth at low tide -- the gate never closes this cycle.
  if h_low >= g then
    return t_low;
  end if;

  -- Defensive only -- see header comment. Real data never reaches this branch.
  if h_high < g then
    return null;
  end if;

  arg := 1 - 2 * (g - h_low) / r;
  arg := greatest(-1.0, least(1.0, arg)); -- guard floating-point rounding right at a boundary

  return t_low + round(d * acos(arg) / pi())::bigint;
end;
$$;

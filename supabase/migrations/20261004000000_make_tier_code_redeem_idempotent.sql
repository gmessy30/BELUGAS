-- Why: on 2026-10-04 a tier-2 redemption committed on its first call, but the phone never
-- showed success; its 8 retries all hit is_used = true and were told the code was invalid.
--
-- redeem_tier_code_detailed: same rate limit, same normalization, same claim, but returns a
-- status the client can tell apart, and treats a retry by the subscriber_id that already holds
-- the code as success (the first call can commit while its response never reaches the phone).
--
-- Every outcome is a RETURN, never a RAISE (except rate_limited, which happens before the
-- attempt is logged, as today). A RAISE after the attempts insert would roll that insert back
-- and take the new "already_claimed" answer out from under the hourly rate limit.
create or replace function public.redeem_tier_code_detailed(p_code text, p_subscriber_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_normalized_code text := public.normalize_tier_code(p_code);
  v_recent_attempts int;
  v_row public.tier_roster%rowtype;
begin
  select count(*) into v_recent_attempts
  from public.tier_code_redeem_attempts
  where subscriber_id = p_subscriber_id
    and attempted_at > now() - interval '1 hour';

  if v_recent_attempts >= 12 then
    raise exception 'rate_limited';
  end if;

  insert into public.tier_code_redeem_attempts (subscriber_id) values (p_subscriber_id);

  -- Lock the row so two in-flight calls for the same code serialize here.
  select * into v_row
  from public.tier_roster
  where public.normalize_tier_code(code) = v_normalized_code
  for update;

  if not found then
    return jsonb_build_object('status', 'invalid');
  end if;

  -- Idempotent retry: this device already holds this exact code.
  if v_row.claimed_subscriber_id = p_subscriber_id then
    return jsonb_build_object('status', 'success', 'tier', v_row.tier, 'already_held', true);
  end if;

  if v_row.is_used then
    -- Claimed by a different device. A revoked code (claimed_subscriber_id cleared, is_used
    -- still true) stays 'invalid', not "used by another device".
    if v_row.claimed_subscriber_id is not null then
      return jsonb_build_object('status', 'already_claimed');
    end if;
    return jsonb_build_object('status', 'invalid');
  end if;

  -- tier_roster_claimed_subscriber_id_uidx allows one code per subscriber. Checked up front so
  -- it gets its own answer (and keeps its attempt row); today it's swallowed as plain null.
  if exists (select 1 from public.tier_roster where claimed_subscriber_id = p_subscriber_id) then
    return jsonb_build_object('status', 'device_has_other_code');
  end if;

  update public.tier_roster
     set claimed_subscriber_id = p_subscriber_id,
         is_used = true,
         claimed_at = now()
   where id = v_row.id;

  return jsonb_build_object('status', 'success', 'tier', v_row.tier, 'already_held', false);
exception
  when unique_violation then
    -- Race fallback only (the exists() above should catch it first).
    return jsonb_build_object('status', 'device_has_other_code');
end;
$$;

grant execute on function public.redeem_tier_code_detailed(text, uuid) to anon, authenticated;

-- The existing smallint RPC becomes a thin wrapper, with the same signature and return type, so
-- installed native builds (SupabaseClient.kt: any int = Success, null = Invalid) keep working
-- unchanged and get the idempotent retry for free. Every non-success status maps to null,
-- exactly as before.
create or replace function public.redeem_tier_code(p_code text, p_subscriber_id uuid)
returns smallint
language sql
security definer
set search_path = public, pg_temp
as $$
  select case when r->>'status' = 'success' then (r->>'tier')::smallint end
  from public.redeem_tier_code_detailed(p_code, p_subscriber_id) as r
$$;

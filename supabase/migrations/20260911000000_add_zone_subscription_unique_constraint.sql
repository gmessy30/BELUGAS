-- Prevents duplicate zone subscriptions for the same subscriber -- ALERTS' zone picker let
-- someone tap subscribe on a zone they already watch, inserting a second (third, fourth...) row
-- for the exact same (subscriber_id, zone_id) pair, with nothing stopping it client or server.
-- Confirmed harmless in practice (match_notification_recipients, 20260829010000, already dedupes
-- by subscriber_id before the token lookup, so duplicates never caused extra pushes) but still
-- real clutter. Paired with the client no longer letting the picker attempt it in the first
-- place (SubscriptionsScreen.kt's zone chips now disable/mark an already-watched zone), with
-- this constraint as the backstop for the race that check can't fully close (e.g. the same
-- subscriber_id subscribing from a second device at the same moment).
--
-- PARTIAL, kind='zone' only: 'zone' has a clean natural key (zone_id) to dedupe on; the other
-- two kinds don't. A point_radius subscription stores only a raw geography point + radius, no
-- FK back to whichever preset (or custom pin) produced it (see that column's own comment in
-- 20260819000000_add_notification_zones_and_subscriptions.sql) -- there's no reliable equality
-- to enforce a constraint on: a stored point isn't guaranteed bit-identical to its source
-- lat/lng after a geography round-trip, and two different custom pins a few centimeters apart
-- are legitimately different subscriptions, not duplicates of the same button. custom_polygon
-- has no fixed identity at all. Both kinds can still technically get a duplicate row (same
-- missing-check root cause as zone had), but it's the same harmless clutter zone duplicates
-- were, with no clean column (or set of columns) to build a constraint on -- accepted as-is
-- rather than forcing one.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.
create unique index if not exists subscriptions_subscriber_zone_unique_idx
  on public.subscriptions (subscriber_id, zone_id)
  where kind = 'zone';

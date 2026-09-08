# Migrations here are not applied automatically

This repo has no DB credentials configured, so nothing under `migrations/` runs on its own.
Every file is hand-applied in the Supabase SQL editor (or via `supabase db push`) by a person,
whenever they get around to it and in whatever order they actually run them.

**The repo being up to date is not evidence that the database is up to date.** A migration can
sit committed for days before anyone runs it, or get skipped entirely. This already caused a
real bug: the gate-time holdover fix was layered on top of an *assumed* deployed version of
`get_kenai_presence_state` that turned out not to match what was actually live.

## Before layering another change onto a function that's been replaced before

Check what's actually deployed first, not what the latest migration file *should* have produced:

```sql
select pg_get_functiondef('public.get_kenai_presence_state(bigint)'::regprocedure);
```

(Swap the name/arg types for whichever function you're about to touch.) Read the body — don't
just check that the migration ran without erroring at some point in the past. Compare it against
the migration file you think is live before writing the next one on top of it.

This matters most for a function that's been `create or replace`'d more than once
(`get_kenai_presence_state` already has been three times) — each new migration's comments
describe what it assumes is already there, and that assumption is only as good as whoever last
confirmed it.

## The other recurring gotcha: changing the return signature

`create or replace function` fails with a "cannot change return type" error if the new version's
`returns table (...)` columns differ from what's currently live — Postgres won't let you swap the
signature out from under a plain replace. If a migration needs different output columns, `drop
function` first, then create the new one. If the columns are unchanged (just the function body),
plain `create or replace` is safe and preferred. Each migration touching a shared function should
say explicitly which case it is, and diff its own `returns table` against the previous migration's
before assuming.

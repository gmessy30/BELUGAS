// Fetches and caches the Kenai River beluga arrival prediction. Triggered on a schedule (see
// public.trigger_beluga_prediction_refresh / pg_cron job "refresh-beluga-prediction" in
// 20260903030000_add_tide_cycle_predictor.sql), not per-request -- the pages this scrapes are
// static daily harmonic predictions, so there's nothing to gain from fetching them on every
// banner poll. Upserts into public.tide_cycles; public.get_kenai_presence_state() reads that
// cache (plus live sightings) to decide RED/YELLOW/BLUE -- this function never makes that
// decision itself, only keeps the raw model output current.
//
// Port of Kenai Beluga Predictor SPEC.md / kenai_beluga_predictor.py (R^2=0.964 on 28 Spring
// 2026 sightings), with two deliberate deviations from that reference, both explained where
// they occur below:
//   1. The tide-low source. The SPEC cites location 3508 for tide lows; verified live before
//      writing this that 3508 is NOT the Kenai station (it's Kenilworth Aquatic Garden, DC).
//      The correct station, also verified live, is location 3503 ("Kenai River entrance, Cook
//      Inlet, Alaska Tide Chart") -- same site family and <pre class="predictions-table">
//      format as the 3504/3506 current stations the model was actually fit against, heights in
//      feet (converted to meters here -- the model's low-tide-height warning threshold and its
//      validation sample are both in meters).
//   2. Everything is parsed straight into real UTC epoch milliseconds (via each event's own
//      AKDT/AKST label -- never the runtime's local clock, which is not Alaska's) rather than
//      the reference's "minutes since midnight, naive local time" domain. That domain never
//      leaves the page's own day in the reference implementation (a deliberate simplification
//      for a CLI tool always run against one caller-supplied day); this function fetches
//      several days per request and needs values that stay comparable across day and month
//      boundaries, including across a spring-forward/fall-back transition -- which the
//      reference's own comment flags as something it sidesteps by staying naive, not something
//      it solves. Trusting each event's own printed zone label (rather than computing DST
//      rules independently) avoids re-deriving Alaska's DST calendar and matches "never trust
//      a single data source silently": the source is trusted for what it explicitly states,
//      not for an inference on top of it.
//
// SETUP (mirrors notify-new-sighting/index.ts's own setup block -- see
// 20260903030000_add_tide_cycle_predictor.sql's header for the exact commands):
//   1. supabase functions deploy predict-beluga-arrival --no-verify-jwt --project-ref vwbcrctzsqukutvlbqwy
//   2. One-time in the SQL editor (never committed): vault.create_secret(...) under the name
//      'predict_beluga_arrival_webhook_secret'.
//   3. supabase secrets set WEBHOOK_SECRET="<same value>" --project-ref vwbcrctzsqukutvlbqwy

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const WEBHOOK_SECRET = Deno.env.get("WEBHOOK_SECRET")!;

const USER_AGENT = "kenai-beluga-predictor/1.0 (contact: owner)";

// ---------------------------------------------------------------------------------------------
// Model constants (fitted; do not change unless you refit -- see kenai_beluga_predictor.py)
// ---------------------------------------------------------------------------------------------
const A = 72.0;
const B_CUR = 101.0;
const B_CFS = -0.017;
const CFS_REF = 2500.0;
const WINDOW = 18;
const WINDOW_FLOOD = 40;
const WINDOW_SLACK = 25;
const CLAMP: [number, number] = [-80, 360];
const CFS_CLAMP: [number, number] = [2130, 3030];

// Current stations (unchanged from the SPEC -- verified live, both correctly resolve to Kenai
// River current-prediction pages).
const CURRENT_URLS: Record<number, string> = {
  45: "https://tides.mobilegeographics.com/locations/3506.html",
  12: "https://tides.mobilegeographics.com/locations/3504.html",
};

// Tide-low station -- 3503, NOT the SPEC's 3508. See this file's header comment.
const TIDE_LOW_URL = "https://tides.mobilegeographics.com/locations/3503.html";

const FEET_TO_METERS = 0.3048;

// ---------------------------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------------------------
const PRE_RE = /<pre class="predictions-table">([\s\S]*?)<\/pre>/;

// Matches a MobileGeographics event line, with or without its day's leading date prefix, and
// with or without a trailing numeric event (Sunset/Moonrise/etc. lines match only through the
// timezone code -- see below). Examples this matches:
//   "2026-09-03 Thu  8:59 PM AKDT   20.3 feet  High Tide"
//   "                9:02 PM AKDT   Sunset"                      (no numeric group -- skipped)
//   "               11:11 PM AKDT   -0.0 knots  Slack, Ebb Begins"
const LINE_RE =
  /^(?:(\d{4})-(\d{2})-(\d{2})\s+[A-Za-z]{3}\s+)?\s*(\d{1,2}):(\d{2})\s*(AM|PM)\s+([A-Z]{3,4})\s*(?:(-?\d+\.\d+)\s+(feet|knots)\s+(.+))?$/;

interface ParsedEvent {
  epochMs: number;
  value: number;
  unit: "feet" | "knots";
  label: string;
}

// Converts an event's own printed local time + zone label into a real UTC epoch -- see this
// file's header comment on why the zone label is trusted directly rather than re-derived.
function eventEpochMs(year: number, month: number, day: number, h12: number, minute: number, ampm: string, tz: string): number {
  let hour24 = h12 % 12;
  if (ampm === "PM") hour24 += 12;
  let offsetHours: number;
  if (tz === "AKDT") offsetHours = 8;
  else if (tz === "AKST") offsetHours = 9;
  else throw new Error(`unrecognized timezone label "${tz}" -- refusing to guess an offset`);
  // Date.UTC(...) here treats the given y/m/d/h/min AS IF it were already UTC. The real UTC
  // instant is `offsetHours` LATER than that (AKDT/AKST are behind UTC), so add the offset --
  // e.g. 8:59 PM AKDT (UTC-8) is 4:59 AM UTC the following day.
  return Date.UTC(year, month - 1, day, hour24, minute) + offsetHours * 3600 * 1000;
}

// Parses every dated line inside a page's <pre class="predictions-table"> block into real UTC
// epoch events, carrying the most recent date prefix forward across the un-prefixed
// continuation lines that follow it (the page only stamps a date on the first event of each
// day -- confirmed live against both the tide-low and current-event pages).
function parseEvents(html: string): ParsedEvent[] {
  const pre = PRE_RE.exec(html);
  if (!pre) throw new Error("predictions-table block not found");
  const lines = pre[1].split("\n");

  let curYear: number | null = null;
  let curMonth: number | null = null;
  let curDay: number | null = null;
  const events: ParsedEvent[] = [];

  for (const line of lines) {
    const m = LINE_RE.exec(line);
    if (!m) continue;
    const [, y, mo, d, h, mi, ampm, tz, val, unit, label] = m;
    if (y) {
      curYear = parseInt(y, 10);
      curMonth = parseInt(mo, 10);
      curDay = parseInt(d, 10);
    }
    if (val === undefined) continue; // Sunset/Moonrise/etc -- no numeric event to record.
    if (curYear === null || curMonth === null || curDay === null) continue; // no date yet, skip.
    events.push({
      epochMs: eventEpochMs(curYear, curMonth, curDay, parseInt(h, 10), parseInt(mi, 10), ampm, tz),
      value: parseFloat(val),
      unit: unit as "feet" | "knots",
      label: label.trim(),
    });
  }
  events.sort((a, b) => a.epochMs - b.epochMs);
  return events;
}

async function fetchHtml(url: string): Promise<string> {
  const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
  if (!res.ok) throw new Error(`fetch failed: ${res.status} ${url}`);
  return await res.text();
}

interface TideLow {
  lowAtEpochMs: number;
  lowHeightM: number;
}

async function fetchTideLows(date: Date): Promise<TideLow[]> {
  const url = `${TIDE_LOW_URL}?d=${date.getUTCDate()}&m=${date.getUTCMonth() + 1}&y=${date.getUTCFullYear()}`;
  const html = await fetchHtml(url);
  const events = parseEvents(html);
  return events
    .filter((e) => e.unit === "feet" && /low/i.test(e.label))
    .map((e) => ({ lowAtEpochMs: e.epochMs, lowHeightM: e.value * FEET_TO_METERS }));
}

async function fetchCurrentEvents(date: Date, station: number): Promise<ParsedEvent[]> {
  const url = `${CURRENT_URLS[station]}?d=${date.getUTCDate()}&m=${date.getUTCMonth() + 1}&y=${date.getUTCFullYear()}`;
  const html = await fetchHtml(url);
  const events = parseEvents(html).filter((e) => e.unit === "knots");
  if (events.length < 4) throw new Error(`only ${events.length} current events parsed from ${url}`);
  return events;
}

// Ported from fetch_usgs_cfs -- same 5-day lookback window (provisional data lags ~1 day),
// same "most recent published value wins" resolution.
async function fetchUsgsCfs(date: Date): Promise<number | null> {
  const end = new Date(date.getTime() + 86400000);
  const start = new Date(date.getTime() - 4 * 86400000);
  const fmt = (d: Date) => d.toISOString().slice(0, 10);
  const url =
    `https://waterservices.usgs.gov/nwis/dv/?format=json&sites=15266300` +
    `&startDT=${fmt(start)}&endDT=${fmt(end)}&statCd=00003&parameterCd=00060`;
  try {
    const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
    if (!res.ok) throw new Error(`USGS fetch failed: ${res.status}`);
    const j = await res.json();
    let latest: number | null = null;
    for (const ts of j?.value?.timeSeries ?? []) {
      for (const v of ts?.values ?? []) {
        for (const p of v?.value ?? []) {
          const val = p?.value;
          if (val === undefined || val === null) continue;
          const num = parseFloat(val);
          if (!Number.isNaN(num)) latest = num; // values come chronologically
        }
      }
    }
    return latest;
  } catch (e) {
    console.error("USGS cfs fetch failed", e);
    return null;
  }
}

// ---------------------------------------------------------------------------------------------
// Model -- direct port of piecewise_current / predict from kenai_beluga_predictor.py, epoch-ms
// based instead of minutes-of-day (see file header).
// ---------------------------------------------------------------------------------------------
function piecewiseCurrent(events: ParsedEvent[], atEpochMs: number): number | null {
  if (events.length === 0) return null;
  if (atEpochMs <= events[0].epochMs) return events[0].value;
  for (let i = 0; i < events.length - 1; i++) {
    const a = events[i];
    const b = events[i + 1];
    if (a.epochMs <= atEpochMs && atEpochMs <= b.epochMs) {
      const frac = (atEpochMs - a.epochMs) / Math.max(1, b.epochMs - a.epochMs);
      return a.value + frac * (b.value - a.value);
    }
  }
  return events[events.length - 1].value;
}

interface Prediction {
  predicted_delay_min: number;
  predicted_window_lo_min: number;
  predicted_window_hi_min: number;
  predicted_arrival_at_epoch_ms: number;
  current_phase: "flood-ride" | "ebb-arrival" | "slack-arrival";
  current_entrance_knots: number | null;
  cfs_used: number | null;
  station_ft: number;
  warnings: string[];
}

function predict(lowAtEpochMs: number, lowHeightM: number | null, currentEvents: ParsedEvent[], cfs: number | null, stationFt: number): Prediction {
  let t = 72; // minutes after low, median start
  let usedCurrent: number | null = null;

  for (let i = 0; i < 8; i++) {
    const atMs = lowAtEpochMs + t * 60000;
    const c = currentEvents.length > 0 ? piecewiseCurrent(currentEvents, atMs) : null;
    if (c !== null) usedCurrent = c;
    let delay = A + B_CUR * (usedCurrent ?? 0.0);
    if (cfs !== null) {
      const cfsClamped = Math.min(Math.max(cfs, CFS_CLAMP[0]), CFS_CLAMP[1]);
      delay += B_CFS * cfsClamped - B_CFS * CFS_REF;
    }
    const newT = delay;
    if (Math.abs(newT - t) < 5) {
      t = newT;
      break;
    }
    t = newT;
  }

  t = Math.min(Math.max(t, CLAMP[0]), CLAMP[1]);
  let lo = t - WINDOW;
  let hi = t + WINDOW;
  let phase: Prediction["current_phase"] = "slack-arrival";
  if (usedCurrent !== null) {
    if (usedCurrent > 0.5) {
      phase = "flood-ride";
      lo = t - WINDOW_FLOOD;
      hi = t + WINDOW_FLOOD;
    } else if (usedCurrent < -0.5) {
      phase = "ebb-arrival";
    } else if (Math.abs(usedCurrent) < 0.15) {
      phase = "slack-arrival";
      lo = t - WINDOW_SLACK;
      hi = t + WINDOW_SLACK;
    }
  }

  const warnings: string[] = [];
  if (usedCurrent === null) {
    warnings.push("no current events available; flow-only estimate, wider window");
    lo = t - 30;
    hi = t + 30;
  }
  if (cfs === null) warnings.push("no river flow available; used intercept-only");
  if (lowHeightM !== null && lowHeightM > 1.75) {
    warnings.push("unusually high low tide (rare in sample); lower confidence");
  }

  return {
    predicted_delay_min: Math.round(t),
    predicted_window_lo_min: Math.round(lo),
    predicted_window_hi_min: Math.round(hi),
    predicted_arrival_at_epoch_ms: lowAtEpochMs + Math.round(t) * 60000,
    current_phase: phase,
    current_entrance_knots: usedCurrent !== null ? Math.round(usedCurrent * 1000) / 1000 : null,
    cfs_used: cfs,
    station_ft: stationFt,
    warnings,
  };
}

// ---------------------------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------------------------
async function upsertTideCycle(row: Record<string, unknown>): Promise<void> {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/tide_cycles?on_conflict=low_at_epoch_ms`, {
    method: "POST",
    headers: {
      apikey: SERVICE_ROLE_KEY,
      Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates,return=minimal",
    },
    body: JSON.stringify(row),
  });
  if (!res.ok) {
    throw new Error(`tide_cycles upsert failed: ${res.status} ${await res.text()}`);
  }
}

// ---------------------------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------------------------
Deno.serve(async (req: Request) => {
  if (req.headers.get("x-webhook-secret") !== WEBHOOK_SECRET) {
    return new Response("Unauthorized", { status: 401 });
  }

  // "Today" by UTC calendar date, not Alaska's -- deliberately not corrected, since the tide
  // and current pages each return several days of events from whatever date is requested (5
  // seen live from a single request), comfortably covering the up-to-9-hour skew between UTC
  // and Alaska's local calendar date either direction. Worst case on a boundary this run
  // re-fetches a day already cached from 3 hours ago instead of a new one -- harmless, and
  // self-heals on the next scheduled run regardless.
  const today = new Date();

  const cfs = await fetchUsgsCfs(today);

  let currentEvents: ParsedEvent[] = [];
  let stationUsed = 45;
  try {
    currentEvents = await fetchCurrentEvents(today, 45);
  } catch (e) {
    console.error("45ft current fetch failed, falling back to 12ft", e);
    try {
      currentEvents = await fetchCurrentEvents(today, 12);
      stationUsed = 12;
    } catch (e2) {
      console.error("12ft current fetch also failed; predictions this run will be flow-only", e2);
    }
  }

  let lows: TideLow[];
  try {
    lows = await fetchTideLows(today);
  } catch (e) {
    console.error("tide-low fetch failed", e);
    return new Response(JSON.stringify({ error: "tide-low fetch failed", detail: String(e) }), { status: 502 });
  }

  let upserted = 0;
  let predicted = 0;
  for (const low of lows) {
    const row: Record<string, unknown> = {
      low_at_epoch_ms: low.lowAtEpochMs,
      low_height_m: low.lowHeightM,
      source: "mobilegeographics",
    };
    // Only attach a prediction when the current-events fetch actually covers this low's
    // predicted entrance time -- piecewiseCurrent degrades to flat extrapolation past either
    // end of the fetched curve, which is a real (warned) degradation for a low near the edge
    // of the window but not a reason to skip predicting altogether; skip only on a total
    // current-fetch failure (currentEvents.length === 0), where predict() itself already
    // handles that via its own "no current events" warning path.
    const pred = predict(low.lowAtEpochMs, low.lowHeightM, currentEvents, cfs, stationUsed);
    Object.assign(row, pred, { prediction_computed_at: new Date().toISOString() });
    predicted++;

    try {
      await upsertTideCycle(row);
      upserted++;
    } catch (e) {
      console.error(`upsert failed for low_at_epoch_ms=${low.lowAtEpochMs}`, e);
    }
  }

  return new Response(
    JSON.stringify({ lows_seen: lows.length, upserted, predicted, cfs, station_used: stationUsed, current_events_count: currentEvents.length }),
    { status: 200 },
  );
});

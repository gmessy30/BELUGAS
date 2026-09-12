// Notification dispatch. Triggered by a Postgres AFTER INSERT trigger on public.sightings (see
// supabase/migrations/20260823000000_add_device_tokens_and_notify_trigger.sql): resolves which
// device tokens should hear about this sighting via the match_notification_recipients RPC
// (supabase/migrations/20260829010000_add_subscription_matching.sql -- zone/custom-polygon
// containment, point+radius great-circle distance, confidence_filter, expires_at, and a
// broadcast fallback for any device with zero active subscriptions, all computed in that one
// SQL function so it can use PostGIS's indexes), then sends each a single FCM push via the
// HTTP v1 send API. Dedup is inherent -- the RPC returns each fcm_token at most once even if
// several of that device's subscriptions matched.
//
// SETUP (none of this can be done from the repo/CLI-less environment that wrote this file --
// these are the manual steps to actually stand this up):
//
// 1. Generate a Firebase service account key (NOT the client API key already in
//    google-services.json -- a separate, far more privileged credential):
//    Firebase Console -> Project Settings (gear icon) -> Service Accounts tab ->
//    "Generate new private key" -> downloads a JSON file. Keep it out of the git repo.
//
// 2. Deploy this function with JWT verification disabled -- a DB trigger has no user JWT to
//    present, so the shared-secret header below is the auth gate instead:
//      supabase functions deploy notify-new-sighting --no-verify-jwt --project-ref vwbcrctzsqukutvlbqwy
//
// 3. Set this function's secrets (SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are already
//    auto-provisioned by the platform for every edge function -- only these two need setting):
//      supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON="$(cat /path/to/service-account.json)" --project-ref vwbcrctzsqukutvlbqwy
//      supabase secrets set WEBHOOK_SECRET="<same value you pass to vault.create_secret>" --project-ref vwbcrctzsqukutvlbqwy
//    WEBHOOK_SECRET must exactly match whatever value you store in Supabase Vault under the
//    name notify_new_sighting_webhook_secret (see the trigger migration's header comment for
//    the one-time `vault.create_secret` command) -- pick that value yourself and use it in
//    both places, don't reuse anything that has ever appeared in a committed file. Two prior
//    values already leaked into git history this way (the original literal-in-migration
//    mistake, and then a regenerated value that got hardcoded right back into *this* file's
//    setup comment) -- both are burned. Treat this as a lesson in itself: no secret value,
//    including a freshly generated "fixed" one, belongs in a committed file, even as an
//    example.
//
// 4. Run the migrations (creates device_tokens + the trigger that calls this function, and --
//    as of Stage 3 -- 20260829010000_add_subscription_matching.sql, which this function's
//    fetchMatchingDeviceTokens() depends on: without it, match_notification_recipients won't
//    exist and every call to this function will fail).
//
// 5. Redeploy (step 2's command again) any time this file changes -- Stage 3 replaced the
//    flat device_tokens broadcast with real subscription matching; a stale deployed version
//    would keep broadcasting to everyone regardless of what the migration above adds.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const WEBHOOK_SECRET = Deno.env.get("WEBHOOK_SECRET")!;
const FIREBASE_SERVICE_ACCOUNT_JSON = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON")!;

interface SightingRow {
  id: string;
  count_whites?: number;
  count_greys?: number;
  count_calves?: number;
  count_unknown?: number;
  // The estimated whale position (see supabase/migrations/20260901000000_add_whale_position_
  // columns.sql) -- lat/lng on this row are the retired observer-position columns and are no
  // longer read here. May be null -- a stray row with missing coordinates, or a legacy
  // (position_source null) row from before the whale-position redesign, which is deliberately
  // treated as having no known location rather than falling back to its old observer lat/lng.
  // match_notification_recipients handles a null location gracefully: nobody's zone/point/
  // polygon can match it, so only broadcast-fallback devices get it.
  whale_lat?: number | null;
  whale_lng?: number | null;
  observer_type?: string | null;
  is_geofence_verified?: boolean;
  // Forwarded to match_notification_recipients as p_uncertainty_radius_meters -- the
  // river-proximity fallback (supabase/migrations/20260902010000_add_river_proximity_
  // fallback_to_zone_matching.sql) accepts a whale position that fails ST_Contains against a
  // zone's polygon when it's within this many meters (capped at 1000) of that zone's
  // river-spike trace, same cap GeofenceUtils.isWhalePositionVerified uses client-side.
  uncertainty_radius_meters?: number | null;
}

interface WebhookPayload {
  type: string;
  table: string;
  record: SightingRow;
}

Deno.serve(async (req: Request) => {
  if (req.headers.get("x-webhook-secret") !== WEBHOOK_SECRET) {
    return new Response("Unauthorized", { status: 401 });
  }

  let payload: WebhookPayload;
  try {
    payload = await req.json();
  } catch {
    return new Response("Bad request: invalid JSON body", { status: 400 });
  }

  if (payload.type !== "INSERT" || payload.table !== "sightings") {
    return new Response(JSON.stringify({ ignored: true }), { status: 200 });
  }

  const { title, body } = buildNotificationText(payload.record);

  const tokens = await fetchMatchingDeviceTokens(payload.record);
  if (tokens.length === 0) {
    return new Response(JSON.stringify({ sent: 0, failed: 0, reason: "no matching recipients" }), {
      status: 200,
    });
  }

  const accessToken = await getFcmAccessToken();
  const projectId = JSON.parse(FIREBASE_SERVICE_ACCOUNT_JSON).project_id as string;

  let sent = 0;
  let failed = 0;
  await Promise.all(
    tokens.map(async (token) => {
      const ok = await sendFcmMessage(projectId, accessToken, token, title, body);
      if (ok) sent++;
      else failed++;
    }),
  );

  return new Response(JSON.stringify({ sent, failed }), { status: 200 });
});

// Species-count summary, e.g. "3 belugas spotted" -- falls back to a generic line if every
// count field is missing/zero. Zone/area-name enrichment was left out of this pass: doing it
// reliably means an RPC that depends on the separate notification-zones migration being
// applied first, which isn't confirmed yet -- worth adding once that's live, but the count
// summary here doesn't need it.
function buildNotificationText(sighting: SightingRow): { title: string; body: string } {
  const total =
    (sighting.count_whites ?? 0) +
    (sighting.count_greys ?? 0) +
    (sighting.count_calves ?? 0) +
    (sighting.count_unknown ?? 0);

  const body =
    total > 0 ? `${total} beluga${total === 1 ? "" : "s"} spotted` : "New beluga sighting reported";

  return { title: "BELUGAS", body };
}

async function fetchMatchingDeviceTokens(sighting: SightingRow): Promise<string[]> {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/match_notification_recipients`, {
    method: "POST",
    headers: {
      apikey: SERVICE_ROLE_KEY,
      Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      p_lat: sighting.whale_lat ?? null,
      p_lng: sighting.whale_lng ?? null,
      p_observer_type: sighting.observer_type ?? null,
      p_is_geofence_verified: sighting.is_geofence_verified ?? false,
      p_uncertainty_radius_meters: sighting.uncertainty_radius_meters ?? null,
    }),
  });
  if (!res.ok) {
    throw new Error(`Failed to call match_notification_recipients: ${res.status} ${await res.text()}`);
  }
  const rows = (await res.json()) as { fcm_token: string }[];
  return rows.map((r) => r.fcm_token);
}

// --- FCM HTTP v1 send, authenticated via a Google service-account JWT Bearer flow (the v1
// API takes an OAuth2 access token, not a static server key like the legacy FCM API). ---

async function getFcmAccessToken(): Promise<string> {
  const serviceAccount = JSON.parse(FIREBASE_SERVICE_ACCOUNT_JSON);
  const now = Math.floor(Date.now() / 1000);

  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };

  const unsignedToken = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;

  const privateKey = await importPrivateKey(serviceAccount.private_key as string);
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    privateKey,
    new TextEncoder().encode(unsignedToken),
  );

  const jwt = `${unsignedToken}.${base64url(new Uint8Array(signature))}`;

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });

  if (!tokenRes.ok) {
    throw new Error(`Failed to obtain FCM access token: ${tokenRes.status} ${await tokenRes.text()}`);
  }

  const tokenJson = await tokenRes.json();
  return tokenJson.access_token as string;
}

function base64url(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let binary = "";
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const pemContents = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");
  const binaryDer = Uint8Array.from(atob(pemContents), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

// The webapp's own deployed URL -- used only by the webpush section below (icon to show, and
// where a tap on the notification opens). Update this if the GitHub Pages deployment URL ever
// changes; nothing else in this function depends on it.
const WEBAPP_URL = "https://gmessy30.github.io/BELUGAS/webapp/";

async function sendFcmMessage(
  projectId: string,
  accessToken: string,
  token: string,
  title: string,
  body: string,
): Promise<boolean> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      message: {
        token,
        notification: { title, body },
        // Platform-specific overrides are additive and independent -- this "webpush" section is
        // only ever applied when FCM delivers to a browser/web-push registration (this webapp's
        // own tokens); it changes nothing about how the SAME message reaches an Android native
        // client's token, which has no webpush section to read. Icon/link are needed here since
        // a bare notification payload shows with a generic browser icon and no click-through
        // destination otherwise -- Android instead uses the app's own launcher icon and opens the
        // app automatically, so it never needed this.
        webpush: {
          notification: {
            icon: `${WEBAPP_URL}icons/icon-192.png`,
          },
          fcm_options: {
            link: WEBAPP_URL,
          },
        },
      },
    }),
  });
  if (!res.ok) {
    console.error(`FCM send failed for token ${token.slice(0, 12)}...: ${res.status} ${await res.text()}`);
    return false;
  }
  return true;
}

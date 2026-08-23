// Minimal flat-broadcast notification pipeline. Triggered by a Postgres AFTER INSERT trigger
// on public.sightings (see supabase/migrations/20260823000000_add_device_tokens_and_notify_trigger.sql):
// fetches every row in device_tokens and sends each one an FCM push via the HTTP v1 send API.
// No zone matching, confidence filtering, or dedup -- every registered device gets every
// sighting, intentionally, for fast closed-testing turnaround. The real targeted system this
// is meant to be superseded by is the notification zones/subscriptions schema in
// 20260819000000_add_notification_zones_and_subscriptions.sql.
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
//      supabase secrets set WEBHOOK_SECRET="REDACTED-SECRET" --project-ref vwbcrctzsqukutvlbqwy
//    (that WEBHOOK_SECRET value must exactly match the one baked into the trigger's header in
//    the migration above -- it's a purpose-built shared secret for this one webhook, not a
//    real Supabase/Firebase credential, but still worth treating as sensitive.)
//
// 4. Run the migration (creates device_tokens + the trigger that calls this function).

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

  const tokens = await fetchDeviceTokens();
  if (tokens.length === 0) {
    return new Response(JSON.stringify({ sent: 0, failed: 0, reason: "no registered devices" }), {
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

async function fetchDeviceTokens(): Promise<string[]> {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/device_tokens?select=fcm_token`, {
    headers: {
      apikey: SERVICE_ROLE_KEY,
      Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
    },
  });
  if (!res.ok) {
    throw new Error(`Failed to fetch device_tokens: ${res.status} ${await res.text()}`);
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
      },
    }),
  });
  if (!res.ok) {
    console.error(`FCM send failed for token ${token.slice(0, 12)}...: ${res.status} ${await res.text()}`);
    return false;
  }
  return true;
}

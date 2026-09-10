package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Groups a raw 8-character Crockford short code for display/reading aloud, e.g. "K7X92FQR" ->
// "K7X9-2FQR". Stored ungrouped server-side (subscriber_identities.short_code) -- the dash is
// purely a display convenience, never part of the value itself, so admin_bind_tier_code strips
// non-alphanumeric characters before matching regardless of whether it's typed with or without it.
private fun groupedShortCode(code: String): String =
    code.chunked(4).joinToString("-")

// Same polygon as kenai_departure_viewing_areas' 'kenai_mouth' row
// (20260914000000_swap_kenai_departure_polygon_and_expose_tier_check.sql) -- (lat, lng) pairs,
// reordered from the WKT's (lng, lat) convention to match pointInPolygon's own parameter order
// (CoastlineGeometry.kt). This has to be kept in sync by hand if that polygon ever changes again;
// there's no client-side WKB/GeoJSON parsing of a geometry column anywhere in this codebase
// (ZoneRecord.boundary is write-only for exactly this reason -- see that field's own comment), so
// duplicating the literal coordinates here is the pragmatic choice over building that
// infrastructure for one button's visibility check. The closing vertex (identical to the first)
// is dropped -- pointInPolygon's ray-cast already wraps the ring on its own.
//
// DRIFT GUARD: KenaiDepartureViewingAreaTest (commonTest) pins several coordinates against this
// exact polygon -- if this list is ever edited (a real KML update, a fat-fingered paste), that
// test breaks loudly instead of the client's visibility check silently disagreeing with the
// server's own ST_Contains enforcement. internal (not private) so that test can reference this
// constant directly rather than duplicating it a third time.
internal val KENAI_DEPARTURE_VIEWING_AREA: List<Pair<Double, Double>> = listOf(
    60.55840946945002 to -151.2929030905107,
    60.548809724266 to -151.2711621015774,
    60.54447894339786 to -151.2663236677443,
    60.523702563429 to -151.2807913408272,
    60.52157475630008 to -151.2728886260558,
    60.53299593807134 to -151.2684593705852,
    60.54586176396081 to -151.2595707653854,
    60.5458859329573 to -151.2559459380841,
    60.5497924659469 to -151.2612319296148,
    60.55272009801407 to -151.2476677121801,
    60.55204282721643 to -151.237950970811,
    60.55349234777209 to -151.2403839329403,
    60.55399897149227 to -151.2486397201363,
    60.55147439417682 to -151.2647257396582,
    60.55301499382099 to -151.2694197660138,
    60.55336655022332 to -151.274443365124,
    60.55892294657778 to -151.289777984689
)

// Loading is deliberately not distinguished from "nothing yet" in the UI below (no spinner/
// placeholder while it's in flight -- the fetch is normally near-instant) -- but Failed IS
// rendered, with a real fallback, unlike the plain-nullable version this replaced. That one
// collapsed "still loading," "fetch failed," and "never attempted" into the same invisible
// null, which meant the one person this whole section exists for -- someone who can't manage
// the code-entry UI -- had zero indication anything was even supposed to be here if the
// network call failed.
private sealed class ShortCodeState {
    object Loading : ShortCodeState()
    data class Loaded(val code: String) : ShortCodeState()
    object Failed : ShortCodeState()
}

// Reached only via AboutScreen's hidden 7-tap gesture -- see that screen's own comment. A code
// field, plus (below it) this device's own short id for the manual fallback: someone who can't
// manage the code entry can read that id aloud over the phone instead, and an admin binds a
// code to it by hand via admin_bind_tier_code
// (20260905010000_add_tier_code_rate_limiting_and_identity_bind.sql). No name/tier picker here
// either way -- those stay entirely admin-managed directly in Supabase (public.tier_roster).
@Composable
fun TierClaimScreen(appPreferences: AppPreferences, locationService: LocationService, onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    // null = no attempt yet.
    var lastResult by remember { mutableStateOf<TierRedeemResult?>(null) }
    val scope = rememberCoroutineScope()

    // Departure-report button state. isDepartureReporter gates whether ANY of this section
    // renders at all ("visible only to a claimed tier-1 device") -- fetched fresh each time this
    // screen loads rather than cached locally, so a revoked claim stops showing the button on the
    // very next visit instead of persisting stale until someone thinks to check
    // (isKenaiDepartureReporter's own comment). isInsideViewingArea is the second, independent
    // gate ("you can't watch a departure from town") -- both are pure visibility signals; the
    // actual reportKenaiDeparture call re-checks both server-side regardless.
    var isDepartureReporter by remember { mutableStateOf(false) }
    var isInsideViewingArea by remember { mutableStateOf(false) }
    var isCheckingLocation by remember { mutableStateOf(false) }
    var isReportingDeparture by remember { mutableStateOf(false) }
    // null = no attempt yet this screen visit.
    var departureReportSucceeded by remember { mutableStateOf<Boolean?>(null) }

    suspend fun checkDepartureEligibility(id: String) {
        isDepartureReporter = SupabaseApi.isKenaiDepartureReporter(id)
        if (!isDepartureReporter) return
        isCheckingLocation = true
        val coords = locationService.getCurrentLocation()
        isInsideViewingArea = coords != null &&
            pointInPolygon(coords.latitude, coords.longitude, KENAI_DEPARTURE_VIEWING_AREA)
        isCheckingLocation = false
    }

    // Fetched once when this screen loads -- which, since it's only reachable through the
    // hidden gesture, already IS "the fallback panel being revealed." getOrCreateSubscriberId
    // is cheap/local; getOrCreateSubscriberIdentity is the one network round trip, and mints
    // this device's short code server-side on its first-ever call (see that RPC's own comment).
    var subscriberId by remember { mutableStateOf<String?>(null) }
    var shortCodeState by remember { mutableStateOf<ShortCodeState>(ShortCodeState.Loading) }

    suspend fun fetchShortCode(id: String) {
        shortCodeState = ShortCodeState.Loading
        val fetched = SupabaseApi.getOrCreateSubscriberIdentity(id)
        shortCodeState = if (fetched != null) ShortCodeState.Loaded(fetched) else ShortCodeState.Failed
    }

    LaunchedEffect(Unit) {
        val id = appPreferences.getOrCreateSubscriberId()
        subscriberId = id
        fetchShortCode(id)
        checkDepartureEligibility(id)
    }

    val clipboardManager = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OBSERVER CODE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                TextButton(onClick = onBack) {
                    Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    lastResult = null
                },
                label = { Text("Code") },
                singleLine = true,
                enabled = !isSubmitting,
                // Ascii (not the default Text) skips keyboards that assume prose -- no
                // predictive text bar guessing at a word, no auto-period-on-double-space.
                // Characters forces caps as typed, matching what the server now normalizes to
                // anyway (see normalize_tier_code, 20260905020000) -- so what's on screen is
                // what actually gets compared, not a lowercase-looking string that's silently
                // uppercased server-side. autoCorrectEnabled off for the same reason autocorrect
                // is wrong for any code: it "fixes" a token that was never a word to begin with.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false
                ),
                // Explicit, not the Material3 default -- App.kt's MaterialTheme is never given
                // a colorScheme, so it defaults to lightColorScheme(), whose text/border colors
                // assume a light surface. This screen paints its own black background instead
                // of using the theme's surface color, so the default (near-black) text and
                // border were rendering essentially invisibly against it. Set to the same
                // white/cyan the rest of this screen already uses (see the header row's Text
                // and the short-code section's COPY buttons), not inherited from the theme.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = Color.White.copy(alpha = 0.5f),
                    cursorColor = Color(0xFF00E5FF),
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                    focusedLabelColor = Color(0xFF00E5FF),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            Button(
                enabled = code.isNotBlank() && !isSubmitting && subscriberId != null,
                onClick = {
                    val id = subscriberId ?: return@Button
                    isSubmitting = true
                    lastResult = null
                    scope.launch {
                        val result = SupabaseApi.redeemTierCode(code.trim(), id)
                        lastResult = result
                        isSubmitting = false
                        if (result is TierRedeemResult.Success) code = ""
                    }
                },
                // All four explicit -- same MaterialTheme-has-no-colorScheme issue as the code
                // field above. containerColor alone left contentColor/disabledContainerColor/
                // disabledContentColor on the theme's lightColorScheme() defaults; the visible
                // Text/CircularProgressIndicator below have their own explicit colors so the
                // enabled state looked fine, but the disabled container (this button is disabled
                // while isSubmitting) fell back to a near-black-on-alpha default that blended
                // into this screen's black background.
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800),
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFFFF9800).copy(alpha = 0.4f),
                    disabledContentColor = Color.Black.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Text("SUBMIT", color = Color.Black, fontWeight = FontWeight.Black)
                }
            }

            // Deliberately generic wording for Invalid -- a bad code and an already-used code
            // look identical here, same as redeem_tier_code's own null-for-every-failure
            // design, so this screen can't be used to probe which codes exist. RateLimited gets
            // its own distinct, honest message -- it says nothing about whether any code tried
            // was valid, only that this device should slow down, so surfacing it separately
            // doesn't reopen that concern (see TierRedeemResult's own comment).
            lastResult?.let { result ->
                Spacer(Modifier.height(16.dp))
                val (text, color) = when (result) {
                    is TierRedeemResult.Success -> "Code accepted." to Color(0xFF00E5FF)
                    is TierRedeemResult.Invalid -> "That code isn't valid." to Color(0xFFFF5252)
                    is TierRedeemResult.RateLimited -> "Too many attempts -- try again in a bit." to Color(0xFFFF9800)
                }
                Text(text = text, color = color, fontWeight = FontWeight.Bold)
            }

            // Manual fallback: this device's own short id, for someone who can't manage the
            // code entry above to read aloud over the phone instead. Nothing shown during
            // Loading (normally near-instant, not worth a placeholder) -- but Failed IS shown,
            // deliberately, with a real fallback rather than silence. See ShortCodeState's own
            // comment for why that distinction matters here specifically.
            when (val state = shortCodeState) {
                is ShortCodeState.Loading -> Unit
                is ShortCodeState.Loaded -> {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        "CAN'T ENTER A CODE?",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Read this to an admin over the phone -- they can bind your code to it directly.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            groupedShortCode(state.code),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = {
                            clipboardManager.setText(AnnotatedString(state.code))
                            justCopied = true
                        }) {
                            Text(
                                if (justCopied) "COPIED" else "COPY",
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                is ShortCodeState.Failed -> {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        "CAN'T ENTER A CODE?",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Couldn't load your short device ID -- check your connection.",
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { subscriberId?.let { id -> scope.launch { fetchShortCode(id) } } },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Text("RETRY", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    // Falls back to the raw subscriber_id -- awkward to read aloud (36 characters,
                    // hyphenated), but an admin can look it up directly, and it beats the person
                    // calling in having nothing at all to give them, which is exactly the
                    // situation this whole section exists to prevent.
                    subscriberId?.let { id ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "LONG FORM (read carefully, or send this device closer to a signal):",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                id,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(id))
                                justCopied = true
                            }) {
                                Text(
                                    if (justCopied) "COPIED" else "COPY",
                                    color = Color(0xFF00E5FF),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Departure-report section -- only ever renders for a claimed tier-1 device
            // currently standing inside the viewing polygon (both isDepartureReporter and
            // isInsideViewingArea gate visibility only; report_kenai_departure re-checks both,
            // plus that the phase is actually RED, server-side regardless of what got this far).
            if (isDepartureReporter && isInsideViewingArea) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "WHALES LEFT?",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Only use this if you watched them go -- it steps the alert down, not all the way to clear.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !isReportingDeparture,
                    onClick = {
                        val id = subscriberId ?: return@Button
                        isReportingDeparture = true
                        departureReportSucceeded = null
                        scope.launch {
                            val coords = locationService.getCurrentLocation()
                            departureReportSucceeded = if (coords != null) {
                                SupabaseApi.reportKenaiDeparture(id, coords.latitude, coords.longitude)
                            } else {
                                false
                            }
                            isReportingDeparture = false
                        }
                    },
                    // Same fix as SUBMIT above -- all four explicit so the disabled state
                    // (while isReportingDeparture) doesn't fall back to a near-invisible
                    // theme default.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF5252),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFFF5252).copy(alpha = 0.4f),
                        disabledContentColor = Color.White.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isReportingDeparture) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("REPORT DEPARTURE", color = Color.White, fontWeight = FontWeight.Black)
                    }
                }
                departureReportSucceeded?.let { succeeded ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (succeeded) "Reported. The alert will step down." else "Couldn't report right now -- check that you're still in position and try again.",
                        color = if (succeeded) Color(0xFF00E5FF) else Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isDepartureReporter && isCheckingLocation) {
                Spacer(Modifier.height(40.dp))
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

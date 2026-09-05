package com.cookinlet.belugas

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.cookinlet.belugas.db.BelugaDatabase
import com.cookinlet.belugas.db.DatabaseDriverFactory
import com.cookinlet.belugas.db.createDatabase
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import belugas.shared.generated.resources.Res
import belugas.shared.generated.resources.beluga_background
import belugas.shared.generated.resources.beluga_sketch
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.painterResource

// Navigation States
enum class Screen {
    SPLASH,
    // Shown once on install and again after any reinstall -- see AppPreferences.
    // getHasAcknowledgedFirstRunGate's own comment. Never reachable from normal navigation, same
    // as TIER_CLAIM below -- only App()'s own startup routing puts the user here.
    ACKNOWLEDGEMENT_GATE,
    CAPTURE,
    PHOTO_LOGGING,
    MANUAL_LOGGING,
    MAP,
    MENU,
    SIGHTINGS_LIST,
    NEWS_FEED,
    RESOURCES,
    ABOUT,
    EXPORT,
    SUBSCRIPTIONS,
    // Reached only via AboutScreen's hidden 7-tap gesture -- never from MainMenuDrawer or any
    // other visible navigation entry point. See TierClaimScreen.kt.
    TIER_CLAIM
}

// How long the splash screen stays up before navigating to the launch-preference screen.
private const val SPLASH_DURATION_MS = 3000L

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.SPLASH) }
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    val storage = rememberLocalFileStorage()
    val appPreferences = rememberAppPreferences()
    val database = remember { createDatabase(DatabaseDriverFactory()) }
    val locationService = rememberLocationService()
    var activeRegion by remember { mutableStateOf(Regions.COOK_INLET) }
    var currentAltitude by remember { mutableStateOf(0.0) }

    // Hoisted remote sightings state
    var remoteSightings by remember { mutableStateOf<List<SightingRecord>>(emptyList()) }
    var isLoadingRemote by remember { mutableStateOf(false) }

    fun refreshRemoteSightings() {
        scope.launch {
            println("APP_ROOT: Refreshing remote sightings...")
            isLoadingRemote = true
            val fetched = SupabaseApi.getSightings()
            remoteSightings = fetched.sortedByDescending { it.observedAtEpochMs ?: 0L }
            isLoadingRemote = false
            println("APP_ROOT: Successfully loaded ${remoteSightings.size} sightings.")
        }
    }

    // Initialize local count and fetch remote sightings on startup
    LaunchedEffect(Unit) {
        OfflineSightingRepository.refreshPendingCount(storage)
        refreshRemoteSightings()
    }

    // Splash: only shown when landing on Map or Menu. Camera is the pre-existing default
    // behavior, so a Camera preference skips the splash (and its fixed delay) entirely rather
    // than adding a startup delay users never had before. For Map/Menu, the launch-screen
    // preference loads in parallel with the fixed display duration (loading a couple bytes
    // from SharedPreferences/NSUserDefaults is far faster than SPLASH_DURATION_MS, so this
    // just makes sure a slow read can't extend the splash rather than trying to shave time
    // off it).
    // Shared by the initial routing effect below and the acknowledgement gate's own
    // onAcknowledged callback -- one place that resolves "which real screen does the launch
    // preference mean," so those two call sites can't drift. withSplashDelay is false from the
    // gate (the gate itself already was the first thing the user saw and interacted with --
    // stacking the fixed splash wait on top of that would just feel like a second cold-open).
    suspend fun resolveAndSetLaunchScreen(withSplashDelay: Boolean) {
        val launchScreen = appPreferences.getLaunchScreen()
        if (launchScreen == LaunchScreen.CAMERA) {
            currentScreen = Screen.CAPTURE
        } else {
            if (withSplashDelay) delay(SPLASH_DURATION_MS)
            currentScreen = when (launchScreen) {
                LaunchScreen.MAP -> Screen.MAP
                LaunchScreen.MENU -> Screen.MENU
                LaunchScreen.CAMERA -> Screen.CAPTURE
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!appPreferences.getHasAcknowledgedFirstRunGate()) {
            currentScreen = Screen.ACKNOWLEDGEMENT_GATE
            return@LaunchedEffect
        }
        resolveAndSetLaunchScreen(withSplashDelay = true)
    }

    // Top-level region and altitude detection
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.CAPTURE || currentScreen == Screen.MAP) {
            val coords = locationService.getCurrentLocation()
            if (coords != null) {
                activeRegion = Regions.ALL.find { it.containsLocation(coords.latitude, coords.longitude) } ?: Regions.COOK_INLET
                currentAltitude = coords.altitudeMeters
            }
        }
    }

    // "Belugas present" safety banner + map river-shading state -- hoisted here (rather than
    // per-screen) since both need to keep working regardless of which screen is showing, and
    // the map shading specifically needs to be visible unconditionally (not just wherever the
    // banner's own gates happen to be satisfied).
    //
    // Both the Kenai predictor path and the generic (any other watched zone) flat-decay path
    // now follow the SAME three-state model, deliberately -- UNKNOWN is its own state, never
    // folded into BLUE (see BelugaPresenceStatus's own comment for why: BLUE is a confirmed
    // claim, UNKNOWN means no successful fetch has ever landed, and conflating them used to mean
    // a device that had never reached the server rendered a confident false all-clear):
    //   - Fresh: a real, recent value from the last successful poll.
    //   - Stale: a real value, but the last successful poll is older than
    //     DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS -- last-known status/color kept exactly as is,
    //     with a "(UPDATING…)" suffix on the banner (see BelugaPresenceBanner's isDataStale).
    //   - Unknown: no successful poll has EVER landed this app run. Renders as its own gray
    //     "STATUS UNKNOWN" on the banner, and SightingsMapScreen skips drawing that zone's
    //     shading entirely rather than drawing a placeholder color.
    // A failed poll NEVER wipes previously-known data back to empty/null in either path -- that
    // was the generic path's actual bug (getWatchedZoneStatuses() returning emptyList() on
    // failure, assigned directly, indistinguishable from a real "nothing here" answer) alongside
    // Kenai's narrower one (null collapsing to BLUE).
    //
    // Six independent pieces:
    //   - watchedZoneShadingAreas: each watched zone's shading geometry for the map's
    //     FillLayer (the real banner watch area for Kenai, that zone's full boundary as a
    //     fallback for any other watched zone -- decided server-side, see
    //     WatchedZoneShadingRecord's comment). Fetched once -- doesn't change at runtime.
    //   - watchedZoneStatuses: raw sighting-recency facts for every watched zone, no location
    //     involved -- sticky, only ever updated on a SUCCESSFUL fetch (see hasEverFetched
    //     WatchedZoneStatuses below for how "no successful fetch yet" is tracked separately).
    //     Feeds the map (everyone sees it) and doubles as the banner's status lookup once a
    //     relevant zone id is known.
    //   - nearbyWatchedZone: the closer of the banner's two visibility gates -- real distance,
    //     not containment, per the spec (someone doesn't need to be in the river to see it).
    //   - presenceStatus: the banner's own color -- for Kenai, kenaiBelugaStatus below, the
    //     server's phase possibly ESCALATED toward UNKNOWN by effectiveKenaiPresenceStatus once
    //     it's been too long (or too many tide cycles) since the last successful poll for that
    //     phase to still be trustworthy (see PresenceBanner.kt's own comment on the escalation
    //     constants) -- ticked, not purely reactive, since elapsed time alone can trigger it; for
    //     any other watched zone, the flat-decay color once hasEverFetchedWatchedZoneStatuses is
    //     true, recomputed on a short local tick (see PresenceBanner.kt) so it keeps visibly
    //     aging between the infrequent network polls above instead of only updating on fetch.
    //   - kenaiPresenceSnapshot: get_kenai_presence_state()'s real tide-cycle-aware RED/YELLOW/
    //     BLUE for Kenai specifically, replacing the flat-decay path for that one zone -- both the
    //     banner AND the map's Kenai river-shading color (passed into SightingsMapScreen below)
    //     read kenaiBelugaStatus (the escalated value, not this raw fetch), so the two surfaces
    //     can't disagree (see colorForBelugaPresenceStatus's own comment on why that invariant
    //     matters). Polled unconditionally, same reasoning as watchedZoneShadingAreas/
    //     watchedZoneStatuses above -- the map shading needs it regardless of whether the banner
    //     itself is currently shown.
    //   - isDataStale (Kenai's and the generic path's own): the "(UPDATING…)" suffix -- narrower
    //     than escalation above, and independent of it (a phase can be both escalated AND
    //     stale-suffixed at once).
    var watchedZoneShadingAreas by remember { mutableStateOf<List<WatchedZoneShadingRecord>>(emptyList()) }
    var watchedZoneStatuses by remember { mutableStateOf<List<WatchedZoneSightingStatus>>(emptyList()) }
    var hasEverFetchedWatchedZoneStatuses by remember { mutableStateOf(false) }
    var lastSuccessfulWatchedZoneStatusesFetchAtMs by remember { mutableStateOf<Long?>(null) }
    var isWatchedZoneStatusesStale by remember { mutableStateOf(false) }
    var subscribedWatchedZoneId by remember { mutableStateOf<String?>(null) }
    var nearbyWatchedZone by remember { mutableStateOf<NearbyWatchedZone?>(null) }
    var nonKenaiPresenceStatus by remember { mutableStateOf(BelugaPresenceStatus.UNKNOWN) }
    // KenaiPresenceState and its fetch time are always set together -- KenaiPresenceSnapshot
    // (see PresenceBanner.kt) keeps them that way instead of two separate nullable vars that
    // could drift apart.
    var kenaiPresenceSnapshot by remember { mutableStateOf<KenaiPresenceSnapshot?>(null) }
    var isKenaiDataStale by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        watchedZoneShadingAreas = SupabaseApi.getWatchedZoneShadingAreas()
    }

    LaunchedEffect(Unit) {
        while (true) {
            val statuses = SupabaseApi.getWatchedZoneStatuses(DEFAULT_YELLOW_WINDOW_MS)
            if (statuses != null) {
                watchedZoneStatuses = statuses
                hasEverFetchedWatchedZoneStatuses = true
                lastSuccessfulWatchedZoneStatusesFetchAtMs = currentTimeMillis()
                val watchedZoneIds = statuses.map { it.zoneId }.toSet()
                val subscriberId = appPreferences.getOrCreateSubscriberId()
                subscribedWatchedZoneId = SupabaseApi.getSubscriptions(subscriberId)
                    .firstOrNull { it.isActive && it.kind == "zone" && it.zoneId in watchedZoneIds }
                    ?.zoneId
            }
            // null (failure) intentionally leaves watchedZoneStatuses/subscribedWatchedZoneId
            // exactly as they were -- see this block's own comment above.
            delay(LOCATION_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(lastSuccessfulWatchedZoneStatusesFetchAtMs) {
        while (true) {
            val lastFetch = lastSuccessfulWatchedZoneStatusesFetchAtMs
            isWatchedZoneStatusesStale = lastFetch != null &&
                currentTimeMillis() - lastFetch > DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS
            delay(PRESENCE_DECAY_TICK_INTERVAL_MS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val fetched = SupabaseApi.getKenaiPresenceState()
            if (fetched != null) {
                kenaiPresenceSnapshot = KenaiPresenceSnapshot(fetched, currentTimeMillis())
            }
            delay(LOCATION_POLL_INTERVAL_MS)
        }
    }

    // Staleness ("(UPDATING…)" suffix) AND escalation (phase drifting toward UNKNOWN once
    // it's no longer trustworthy, see effectiveKenaiPresenceStatus) are both genuinely
    // time-based -- unlike a plain reactive derivation, they need to keep recomputing between
    // polls, not just when a new one lands, so this is the one place Kenai's presence state
    // still needs a local tick. UNKNOWN (not BLUE) while kenaiPresenceSnapshot is null -- see
    // this block's own header comment.
    var kenaiBelugaStatus by remember { mutableStateOf(BelugaPresenceStatus.UNKNOWN) }
    LaunchedEffect(kenaiPresenceSnapshot) {
        while (true) {
            val snapshot = kenaiPresenceSnapshot
            val nowMs = currentTimeMillis()
            isKenaiDataStale = snapshot != null &&
                nowMs - snapshot.fetchedAtMs > DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS
            kenaiBelugaStatus = snapshot?.let { effectiveKenaiPresenceStatus(it, nowMs) }
                ?: BelugaPresenceStatus.UNKNOWN
            delay(PRESENCE_DECAY_TICK_INTERVAL_MS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val coords = locationService.getCurrentLocation()
            if (coords != null) {
                nearbyWatchedZone = SupabaseApi.findNearbyWatchedZone(coords.latitude, coords.longitude, DEFAULT_BANNER_PROXIMITY_METERS)
                delay(LOCATION_POLL_INTERVAL_MS)
            } else {
                // A single-shot fused-location request can easily return null on a cold GPS fix
                // (e.g. right after launch) with no fault of the device actually being near a
                // watched zone -- retry soon rather than leaving the banner's proximity gate
                // starved of any data for the full 5-minute poll interval. Also deliberately
                // doesn't clobber a previously-successful nearbyWatchedZone with null here, so a
                // later transient failure can't make an already-shown banner disappear.
                delay(LOCATION_RETRY_INTERVAL_MS)
            }
        }
    }

    val relevantWatchedZoneId = nearbyWatchedZone?.zoneId ?: subscribedWatchedZoneId
    val relevantWatchedZoneName = nearbyWatchedZone?.zoneName
        ?: watchedZoneStatuses.find { it.zoneId == subscribedWatchedZoneId }?.zoneName
    val relevantWatchedZoneSlug = nearbyWatchedZone?.zoneSlug
        ?: watchedZoneStatuses.find { it.zoneId == subscribedWatchedZoneId }?.zoneSlug
    val isRelevantZoneKenai = relevantWatchedZoneSlug == "kenai"

    // Flat-decay path -- kept ticking even while Kenai is the relevant zone (harmless -- its
    // result just isn't read in that case) rather than conditionally starting/stopping, so this
    // doesn't need to know about Kenai at all. UNKNOWN, not a computed BLUE, until the very
    // first successful watchedZoneStatuses fetch has landed -- see this state block's own header
    // comment for why that distinction matters.
    LaunchedEffect(relevantWatchedZoneId, watchedZoneStatuses, hasEverFetchedWatchedZoneStatuses) {
        while (true) {
            nonKenaiPresenceStatus = if (!hasEverFetchedWatchedZoneStatuses) {
                BelugaPresenceStatus.UNKNOWN
            } else {
                val relevantStatus = watchedZoneStatuses.find { it.zoneId == relevantWatchedZoneId }
                computeBelugaPresenceStatus(relevantStatus, currentTimeMillis())
            }
            delay(PRESENCE_DECAY_TICK_INTERVAL_MS)
        }
    }

    // The banner's actual color: Kenai's real, reactive predictor status when Kenai is the
    // relevant zone, the original ticked flat-decay status for anything else. See flag A in the
    // scoping discussion this implements -- this is also, deliberately, the same value fed to
    // SightingsMapScreen's kenaiBelugaStatus parameter below, so the bottom banner and the map's
    // Kenai river shading can never disagree.
    val presenceStatus = if (isRelevantZoneKenai) kenaiBelugaStatus else nonKenaiPresenceStatus

    // Reactive list of all sightings for List and Map views
    val sightings by database.sightingEntityQueries
        .selectAllSightings()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .collectAsState(initial = emptyList())

    rememberSightingPhotoImageLoaderSetup()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        SyncEngine.events.collect { event ->
            val message = when (event) {
                is SyncEngine.SyncEvent.ItemSynced -> "Sighting synced"
                is SyncEngine.SyncEvent.ItemFailed -> event.reason
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Bottom banner: hidden on the two active-data-entry screens (Placement rule), AND
    // only shown elsewhere when this device is subscribed to a watched zone or physically
    // near one -- unlike the map's shading above, which every screen renders unconditionally
    // once it's on the map. Within that gate, BLUE is still a real, shown state -- it only
    // disappears because neither gate applies, never because of its own color.
    val showPresenceBanner = currentScreen != Screen.CAPTURE &&
        currentScreen != Screen.MANUAL_LOGGING &&
        currentScreen != Screen.ACKNOWLEDGEMENT_GATE &&
        relevantWatchedZoneId != null

    // The banner's actual rendered height (including its own navigationBarsPadding, which
    // varies by device/nav style) -- measured, not guessed, so AppBackground's reserved
    // bottom inset for each screen's own content is always exactly right. Stays at whatever
    // it was last measured at across screen changes (harmless: it's only consumed while
    // showPresenceBanner is true, and the banner's own height doesn't change screen to screen).
    var presenceBannerHeightDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    // The one inset value everything bottom-anchored (AppBackground's content padding below,
    // the snackbar, the Map screen's own FAB/playback panel) reads to clear the banner --
    // computed once here so all of them agree, instead of each re-deriving showPresenceBanner
    // vs. presenceBannerHeightDp on its own.
    val bottomContentInset = if (showPresenceBanner) presenceBannerHeightDp else 0.dp

    MaterialTheme {
      Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalBottomContentInset provides bottomContentInset
        ) {
        when (currentScreen) {
            Screen.SPLASH -> {
                SplashScreen()
            }
            Screen.ACKNOWLEDGEMENT_GATE -> {
                AcknowledgementGateScreen(
                    appPreferences = appPreferences,
                    onAcknowledged = {
                        scope.launch { resolveAndSetLaunchScreen(withSplashDelay = false) }
                    }
                )
            }
            Screen.CAPTURE -> {
                CaptureScreen(
                    onPhotoCaptured = { path ->
                        capturedPhotoPath = path
                        currentScreen = Screen.PHOTO_LOGGING
                    },
                    onOpenMenu = { currentScreen = Screen.MENU },
                    storage = storage,
                    currentAltitude = currentAltitude
                )
            }
            Screen.PHOTO_LOGGING -> {
                LoggingScreen(
                    capturedPhotoPath = capturedPhotoPath,
                    storage = storage,
                    locationService = locationService,
                    appPreferences = appPreferences,
                    region = activeRegion,
                    onDoneClick = {
                        capturedPhotoPath = null
                        currentScreen = Screen.CAPTURE
                        refreshRemoteSightings() // Refresh after new data
                    },
                    onRetakeClick = {
                        capturedPhotoPath?.let { path ->
                            scope.launch {
                                storage.deleteFile(path)
                            }
                        }
                        capturedPhotoPath = null
                        currentScreen = Screen.CAPTURE
                    },
                    // The camera flow couldn't place this sighting at all (no heading given,
                    // and no water confirmed near the observer to guess from) -- same cleanup
                    // as RETAKE, since there's no way to carry the captured photo into the
                    // manual flow (ManualLoggingScreen takes no photo path), then hand off to
                    // manual pin-drop logging instead of losing the report entirely.
                    onNavigateToManualLogging = {
                        capturedPhotoPath?.let { path ->
                            scope.launch {
                                storage.deleteFile(path)
                            }
                        }
                        capturedPhotoPath = null
                        currentScreen = Screen.MANUAL_LOGGING
                    }
                )
            }
            Screen.MANUAL_LOGGING -> {
                ManualLoggingScreen(
                    storage = storage,
                    locationService = locationService,
                    appPreferences = appPreferences,
                    region = activeRegion,
                    onDoneClick = {
                        currentScreen = Screen.CAPTURE
                        refreshRemoteSightings() // Refresh after new data
                    },
                    onOpenMenuClick = { currentScreen = Screen.MENU }
                )
            }
            Screen.MENU -> {
                MainMenuDrawer(
                    onNavigateToCamera = { currentScreen = Screen.CAPTURE },
                    onNavigateToMap = { currentScreen = Screen.MAP },
                    onNavigateToManualLog = { currentScreen = Screen.MANUAL_LOGGING },
                    onNavigateToList = { currentScreen = Screen.SIGHTINGS_LIST },
                    onNavigateToNewsFeed = { currentScreen = Screen.NEWS_FEED },
                    onNavigateToResources = { currentScreen = Screen.RESOURCES },
                    onNavigateToAbout = { currentScreen = Screen.ABOUT },
                    onNavigateToExport = { currentScreen = Screen.EXPORT },
                    onNavigateToSubscriptions = { currentScreen = Screen.SUBSCRIPTIONS },
                    storage = storage,
                    currentAltitude = currentAltitude,
                    appPreferences = appPreferences
                )
            }
            Screen.SIGHTINGS_LIST -> {
                OfflineSightingsList(
                    database = database,
                    remoteSightings = remoteSightings,
                    isLoadingRemote = isLoadingRemote,
                    onBack = { currentScreen = Screen.MENU }
                )
            }
            Screen.NEWS_FEED -> {
                NewsFeedScreen(onBack = { currentScreen = Screen.MENU })
            }
            Screen.RESOURCES -> {
                ResourcesScreen(onBack = { currentScreen = Screen.MENU })
            }
            Screen.ABOUT -> {
                AboutScreen(
                    onBack = { currentScreen = Screen.MENU },
                    onNavigateToTierClaim = { currentScreen = Screen.TIER_CLAIM }
                )
            }
            Screen.TIER_CLAIM -> {
                TierClaimScreen(
                    appPreferences = appPreferences,
                    onBack = { currentScreen = Screen.ABOUT }
                )
            }
            Screen.EXPORT -> {
                ExportScreen(onBack = { currentScreen = Screen.MENU })
            }
            Screen.SUBSCRIPTIONS -> {
                SubscriptionsScreen(
                    onBack = { currentScreen = Screen.MENU },
                    appPreferences = appPreferences
                )
            }
            Screen.MAP -> {
                SightingsMapScreen(
                    localSightings = sightings,
                    remoteSightings = remoteSightings,
                    isLoading = isLoadingRemote,
                    region = activeRegion,
                    currentAltitude = currentAltitude,
                    watchedZoneShadingAreas = watchedZoneShadingAreas,
                    watchedZoneStatuses = watchedZoneStatuses,
                    hasEverFetchedWatchedZoneStatuses = hasEverFetchedWatchedZoneStatuses,
                    kenaiBelugaStatus = kenaiBelugaStatus,
                    onCloseMap = { currentScreen = Screen.MENU },
                    onRefreshRemote = { refreshRemoteSightings() }
                )
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomContentInset)
        )

        if (showPresenceBanner) {
            BelugaPresenceBanner(
                status = presenceStatus,
                zoneName = relevantWatchedZoneName,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { presenceBannerHeightDp = with(density) { it.size.height.toDp() } },
                kenaiDetail = if (isRelevantZoneKenai) kenaiPresenceSnapshot?.detail else null,
                isDataStale = if (isRelevantZoneKenai) isKenaiDataStale else isWatchedZoneStatusesStale
            )
        }
      }
    }
}

// ==============================================================
// SPLASH SCREEN
// ==============================================================
// How long the icon sits alone on the plain backdrop before the background artwork fades in.
private const val SPLASH_ICON_ONLY_DURATION_MS = 1500L

@Composable
fun SplashScreen() {
    var showBackgroundArt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPLASH_ICON_ONLY_DURATION_MS)
        showBackgroundArt = true
    }
    val backgroundArtAlpha by animateFloatAsState(
        targetValue = if (showBackgroundArt) 1f else 0f,
        animationSpec = tween(durationMillis = 500)
    )

    AppBackground(backgroundImageAlpha = backgroundArtAlpha) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Temporary placeholder artwork (a hand-drawn sketch) until real logo art exists.
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(Color.White, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(Res.drawable.beluga_sketch),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "BELUGAS",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp
            )
            Text(
                text = "Cook Inlet Beluga Monitoring",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
        }
    }
    }
}

// ==============================================================
// OBSERVER ELEVATION TIP (Contextual aerial mode indicator)
// ==============================================================
@Composable
fun ObserverElevationTip(currentAltitudeMeters: Double, modifier: Modifier = Modifier) {
    if (currentAltitudeMeters > 100.0) {
        Surface(
            color = Color(0xFF0284C7).copy(alpha = 0.9f),
            shape = CircleShape,
            modifier = modifier.padding(8.dp)
        ) {
            Text(
                text = "✈ AERIAL MODE ACTIVE (${currentAltitudeMeters.toInt()}m alt) — Funnel geofence expanded",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

// ==============================================================
// PENDING SYNC BADGE (Live status indicator)
// ==============================================================
@Composable
fun PendingSyncBadge(
    count: Int,
    isSyncing: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    if (count <= 0) return // Hide completely when everything is synced

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color(0xFFFF9800), shape = CircleShape) // High-visibility amber orange
            .clickable(enabled = !isSyncing) { onClick() } // A run is already in flight -- don't stack a redundant one
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(8.dp),
                color = Color.Black,
                strokeWidth = 1.5.dp
            )
        } else {
            // Glowing status indicator icon
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, shape = CircleShape)
            )
        }
        Text(
            text = if (isSyncing) "SYNCING…" else "$count QUEUED",
            color = Color.Black,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ==============================================================
// OFFLINE SIGHTINGS LIST (Reactive local database view)
// ==============================================================
@Composable
fun OfflineSightingsList(
    database: BelugaDatabase,
    remoteSightings: List<SightingRecord>,
    isLoadingRemote: Boolean,
    onBack: () -> Unit
) {
    // Collect reactive Flow directly from local SQLite database
    val localSightings by database.sightingEntityQueries
        .selectAllSightings()
        .asFlow()
        .mapToList(Dispatchers.Default) // commonMain standard dispatcher
        .collectAsState(initial = emptyList())

    // "High confidence only" -- same predicate/reasoning as SightingsMapScreen's own toggle (see
    // isHighConfidence's comment). Applies only to REMOTE DATABASE below -- QUEUED FOR SYNC
    // (this device's own not-yet-synced local queue) is never filtered.
    var showHighConfidenceOnly by remember { mutableStateOf(false) }
    val filteredRemoteSightings = remember(remoteSightings, showHighConfidenceOnly) {
        if (showHighConfidenceOnly) remoteSightings.filter { it.isHighConfidence } else remoteSightings
    }

    AppBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Historical Sightings", color = Color.White) },
                navigationIcon = {
                    Button(onClick = onBack) { Text("BACK") }
                },
                actions = {
                    TextButton(onClick = { showHighConfidenceOnly = !showHighConfidenceOnly }) {
                        Text(
                            if (showHighConfidenceOnly) "✓ VERIFIED ONLY" else "VERIFIED ONLY",
                            color = if (showHighConfidenceOnly) Color(0xFF00E5FF) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (localSightings.isEmpty() && remoteSightings.isEmpty() && !isLoadingRemote) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No sightings logged yet.", color = Color.White.copy(alpha = 0.8f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Show Unsynced Local Sightings first
                    val unsynced = localSightings.filter { it.isSynced == 0L }
                    if (unsynced.isNotEmpty()) {
                        item {
                            Text("QUEUED FOR SYNC", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        items(unsynced) { sighting ->
                            SightingListItem(
                                heading = sighting.heading,
                                countWhites = sighting.countWhites.toInt(),
                                countGreys = sighting.countGreys.toInt(),
                                countCalves = sighting.countCalves.toInt(),
                                countUnknown = sighting.countUnknown.toInt(),
                                lat = sighting.lat,
                                lng = sighting.lng,
                                observedAtMs = sighting.timestamp,
                                photoUrl = sighting.photoUrl,
                                isLocal = true
                            )
                        }
                    }

                    // Show Remote Sightings
                    if (filteredRemoteSightings.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("REMOTE DATABASE", color = Color(0xFFA5D6A7), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        items(filteredRemoteSightings) { sighting ->
                            SightingListItem(
                                heading = sighting.heading,
                                countWhites = sighting.countWhites,
                                countGreys = sighting.countGreys,
                                countCalves = sighting.countCalves,
                                countUnknown = sighting.countUnknown,
                                lat = sighting.whaleLat,
                                lng = sighting.whaleLng,
                                observedAtMs = sighting.observedAtEpochMs ?: 0L,
                                photoUrl = sighting.photoUrl,
                                isLocal = false
                            )
                        }
                    }
                }
            }

            if (isLoadingRemote) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.Yellow)
                }
            }
        }
    }
    }
}

@Composable
fun SightingListItem(
    heading: String?,
    countWhites: Int,
    countGreys: Int,
    countCalves: Int,
    countUnknown: Int,
    lat: Double?,
    lng: Double?,
    observedAtMs: Long,
    photoUrl: String?,
    isLocal: Boolean
) {
    val total = countWhites + countGreys + countCalves + countUnknown

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp), // Slightly more compact padding within the LazyColumn
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = "Sighting photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2A2A))
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
            // Header: Day of Week, Date & Time
            Text(
                text = if (observedAtMs > 0) formatDateTime(observedAtMs) else "Date Not Recorded",
                color = Color.Yellow,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Main Row: Total Belugas
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$total Beluga${if (total != 1) "s" else ""}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isLocal) {
                    Text(
                        " (QUEUED)",
                        color = Color.Yellow.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Breakdown Details
            Text(
                text = "Whites: $countWhites  |  Greys: $countGreys  |  Calves: $countCalves  |  Unknown: $countUnknown",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Footer: Coordinates & Direction
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (lat != null && lng != null) {
                        "Lat: ${formatCoord(lat)}, Lng: ${formatCoord(lng)}"
                    } else {
                        "Location not recorded"
                    },
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (!heading.isNullOrBlank()) {
                    Text(
                        text = "Heading: $heading",
                        color = Color.DarkGray,
                        fontSize = 11.sp
                    )
                }
            }
            }
        }
    }
}

// ==============================================================
// 3. MAIN MENU DRAWER (Blue-Green Inlet Aesthetic)
// ==============================================================
@Composable
fun MainMenuDrawer(
    onNavigateToCamera: () -> Unit,
    onNavigateToMap: () -> Unit,
    onNavigateToManualLog: () -> Unit,
    onNavigateToList: () -> Unit,
    onNavigateToNewsFeed: () -> Unit,
    onNavigateToResources: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToExport: () -> Unit,
    onNavigateToSubscriptions: () -> Unit,
    storage: LocalFileStorage,
    currentAltitude: Double,
    appPreferences: AppPreferences
) {
    // Observe pending count and in-flight sync status in real time
    val pendingCount by OfflineSightingRepository.pendingCount.collectAsState()
    val isSyncing by SyncEngine.isSyncing.collectAsState()
    val scope = rememberCoroutineScope()

    var launchScreen by remember { mutableStateOf<LaunchScreen?>(null) }
    LaunchedEffect(Unit) {
        launchScreen = appPreferences.getLaunchScreen()
    }

    // A Column here (header row, then the rest) instead of a Box with two full-bleed
    // overlapping children: the previous Box layout had the scrollable menu-items Column
    // (fillMaxHeight, right-aligned) spatially overlapping the top-right header row, and a
    // scrollable container claims pointer/drag events across its whole bounds, not just
    // where its visible content sits -- so it was silently stealing taps from the button in
    // the row underneath it (this is what broke the old CLOSE button). Splitting into
    // sibling regions (header takes its natural height, content takes the rest via weight)
    // makes that overlap structurally impossible instead of working around it again.
    AppBackground {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PendingSyncBadge(
                count = pendingCount,
                isSyncing = isSyncing,
                onClick = {
                    SyncEngine.processQueueInBackground(scope, storage)
                }
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.End
        ) {
            val menuItems = listOf(
                "CAMERA",
                "REPORT MANUALLY",
                "SIGHTINGS LIST",
                "SIGHTINGS MAP",
                "NEWS FEED",
                "RESOURCES",
                "ABOUT",
                "EXPORT DATA",
                "ALERTS",
                "SCREEN NAME",
                "(DE)REGISTER"
            )

            menuItems.forEach { title ->
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clickable {
                            when (title) {
                                "CAMERA" -> onNavigateToCamera()
                                "REPORT MANUALLY" -> onNavigateToManualLog()
                                "SIGHTINGS LIST" -> onNavigateToList()
                                "SIGHTINGS MAP" -> onNavigateToMap()
                                "NEWS FEED" -> onNavigateToNewsFeed()
                                "RESOURCES" -> onNavigateToResources()
                                "ABOUT" -> onNavigateToAbout()
                                "EXPORT DATA" -> onNavigateToExport()
                                "ALERTS" -> onNavigateToSubscriptions()
                            }
                        }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Launch-screen preference. No dedicated Settings screen yet, so this lives here.
            Text(
                text = "OPEN APP TO",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LaunchScreen.entries.forEach { option ->
                    FilterChip(
                        selected = launchScreen == option,
                        onClick = {
                            launchScreen = option
                            scope.launch { appPreferences.setLaunchScreen(option) }
                        },
                        label = { Text(option.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.Yellow,
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.1f),
                            labelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            ObserverElevationTip(currentAltitudeMeters = currentAltitude)
        }
    }
    }
}

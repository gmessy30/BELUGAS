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
    SUBSCRIPTIONS
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
    LaunchedEffect(Unit) {
        val launchScreen = appPreferences.getLaunchScreen()
        if (launchScreen == LaunchScreen.CAMERA) {
            currentScreen = Screen.CAPTURE
        } else {
            delay(SPLASH_DURATION_MS)
            currentScreen = when (launchScreen) {
                LaunchScreen.MAP -> Screen.MAP
                LaunchScreen.MENU -> Screen.MENU
                LaunchScreen.CAMERA -> Screen.CAPTURE
            }
        }
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
    // banner's own gates happen to be satisfied). Four independent pieces:
    //   - watchedZoneBoundaries: real polygon geometry for every watched zone, for the map's
    //     FillLayer. Fetched once -- zone polygons don't change at runtime.
    //   - watchedZoneStatuses: raw sighting-recency facts for every watched zone, no location
    //     involved. Feeds the map (everyone sees it) and doubles as the banner's status lookup
    //     once a relevant zone id is known. Refreshed periodically alongside this device's own
    //     subscriptions, since the subscription check needs to know which zone ids are watched.
    //   - nearbyWatchedZone: the closer of the banner's two visibility gates -- real distance,
    //     not containment, per the spec (someone doesn't need to be in the river to see it).
    //   - presenceStatus: the banner's own decayed color, recomputed on a short local tick
    //     (see PresenceBanner.kt) so it keeps visibly aging between the infrequent network polls
    //     above instead of only updating on fetch.
    var watchedZoneBoundaries by remember { mutableStateOf<List<ZoneBoundaryRecord>>(emptyList()) }
    var watchedZoneStatuses by remember { mutableStateOf<List<WatchedZoneSightingStatus>>(emptyList()) }
    var subscribedWatchedZoneId by remember { mutableStateOf<String?>(null) }
    var nearbyWatchedZone by remember { mutableStateOf<NearbyWatchedZone?>(null) }
    var presenceStatus by remember { mutableStateOf(BelugaPresenceStatus.BLUE) }

    LaunchedEffect(Unit) {
        watchedZoneBoundaries = SupabaseApi.getWatchedZoneBoundaries()
    }

    LaunchedEffect(Unit) {
        while (true) {
            val statuses = SupabaseApi.getWatchedZoneStatuses(DEFAULT_YELLOW_WINDOW_MS)
            watchedZoneStatuses = statuses
            val watchedZoneIds = statuses.map { it.zoneId }.toSet()
            val subscriberId = appPreferences.getOrCreateSubscriberId()
            subscribedWatchedZoneId = SupabaseApi.getSubscriptions(subscriberId)
                .firstOrNull { it.isActive && it.kind == "zone" && it.zoneId in watchedZoneIds }
                ?.zoneId
            delay(LOCATION_POLL_INTERVAL_MS)
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

    LaunchedEffect(relevantWatchedZoneId, watchedZoneStatuses) {
        while (true) {
            val relevantStatus = watchedZoneStatuses.find { it.zoneId == relevantWatchedZoneId }
            presenceStatus = computeBelugaPresenceStatus(relevantStatus, currentTimeMillis())
            delay(PRESENCE_DECAY_TICK_INTERVAL_MS)
        }
    }

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
        relevantWatchedZoneId != null

    // The banner's actual rendered height (including its own navigationBarsPadding, which
    // varies by device/nav style) -- measured, not guessed, so AppBackground's reserved
    // bottom inset for each screen's own content is always exactly right. Stays at whatever
    // it was last measured at across screen changes (harmless: it's only consumed while
    // showPresenceBanner is true, and the banner's own height doesn't change screen to screen).
    var presenceBannerHeightDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    MaterialTheme {
      Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalBottomContentInset provides if (showPresenceBanner) presenceBannerHeightDp else 0.dp
        ) {
        when (currentScreen) {
            Screen.SPLASH -> {
                SplashScreen()
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
                    }
                )
            }
            Screen.MANUAL_LOGGING -> {
                ManualLoggingScreen(
                    storage = storage,
                    locationService = locationService,
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
                AboutScreen(onBack = { currentScreen = Screen.MENU })
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
                    watchedZoneBoundaries = watchedZoneBoundaries,
                    watchedZoneStatuses = watchedZoneStatuses,
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
                .padding(bottom = if (showPresenceBanner) 40.dp else 0.dp)
        )

        if (showPresenceBanner) {
            BelugaPresenceBanner(
                status = presenceStatus,
                zoneName = relevantWatchedZoneName,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { presenceBannerHeightDp = with(density) { it.size.height.toDp() } }
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

    AppBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Historical Sightings", color = Color.White) },
                navigationIcon = {
                    Button(onClick = onBack) { Text("BACK") }
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
                    if (remoteSightings.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("REMOTE DATABASE", color = Color(0xFFA5D6A7), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        items(remoteSightings) { sighting ->
                            SightingListItem(
                                heading = sighting.heading,
                                countWhites = sighting.countWhites,
                                countGreys = sighting.countGreys,
                                countCalves = sighting.countCalves,
                                countUnknown = sighting.countUnknown,
                                lat = sighting.lat,
                                lng = sighting.lng,
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

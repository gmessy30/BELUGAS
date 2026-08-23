package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    RESOURCES
}

// How long the splash screen stays up before navigating to the launch-preference screen.
private const val SPLASH_DURATION_MS = 2000L

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

    // Reactive list of all sightings for List and Map views
    val sightings by database.sightingEntityQueries
        .selectAllSightings()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .collectAsState(initial = emptyList())

    MaterialTheme {
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
                    onOpenCameraClick = { currentScreen = Screen.CAPTURE }
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
            Screen.MAP -> {
                SightingsMapScreen(
                    localSightings = sightings,
                    remoteSightings = remoteSightings,
                    isLoading = isLoadingRemote,
                    region = activeRegion,
                    currentAltitude = currentAltitude,
                    onCloseMap = { currentScreen = Screen.MENU }
                )
            }
        }
    }
}

// ==============================================================
// SPLASH SCREEN
// ==============================================================
@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF007F7F), Color(0xFF004D4D)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Placeholder logo mark. Swap for real artwork once it exists: add the image to
            // shared/src/commonMain/composeResources/drawable/ (already wired up via the
            // compose.components.resources dependency) and replace this Box with
            // Image(painterResource(Res.drawable.<name>), contentDescription = null,
            // modifier = Modifier.size(120.dp)).
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(Color(0xFF00E5FF), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🐋", fontSize = 56.sp)
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    if (count <= 0) return // Hide completely when everything is synced

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color(0xFFFF9800), shape = CircleShape) // High-visibility amber orange
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Glowing status indicator icon
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Color.White, shape = CircleShape)
        )
        Text(
            text = "$count QUEUED",
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historical Sightings") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("BACK") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (localSightings.isEmpty() && remoteSightings.isEmpty() && !isLoadingRemote) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No sightings logged yet.", color = Color.Gray)
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

@Composable
fun SightingListItem(
    heading: String?,
    countWhites: Int,
    countGreys: Int,
    countCalves: Int,
    countUnknown: Int,
    lat: Double,
    lng: Double,
    observedAtMs: Long,
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
        Column(modifier = Modifier.padding(16.dp)) {
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
                    text = "Lat: ${formatCoord(lat)}, Lng: ${formatCoord(lng)}",
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
    storage: LocalFileStorage,
    currentAltitude: Double,
    appPreferences: AppPreferences
) {
    // Observe pending count in real time
    val pendingCount by OfflineSightingRepository.pendingCount.collectAsState()
    val scope = rememberCoroutineScope()

    var launchScreen by remember { mutableStateOf<LaunchScreen?>(null) }
    LaunchedEffect(Unit) {
        launchScreen = appPreferences.getLaunchScreen()
    }

    val glacialBlueGreen = Brush.verticalGradient(
        colors = listOf(Color(0xFF007F7F), Color(0xFF004D4D))
    )

    // A Column here (header row, then the rest) instead of a Box with two full-bleed
    // overlapping children: the previous Box layout had the scrollable menu-items Column
    // (fillMaxHeight, right-aligned) spatially overlapping the top-right header row, and a
    // scrollable container claims pointer/drag events across its whole bounds, not just
    // where its visible content sits -- so it was silently stealing taps from the button in
    // the row underneath it (this is what broke the old CLOSE button). Splitting into
    // sibling regions (header takes its natural height, content takes the rest via weight)
    // makes that overlap structurally impossible instead of working around it again.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(glacialBlueGreen)
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

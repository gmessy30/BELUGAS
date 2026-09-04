package com.cookinlet.belugas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SightingRecord(
    @SerialName("id")
    val id: String = "",

    // Nullable: a stray row with missing coordinates (e.g. bad test/manual data) shouldn't be
    // able to fail decoding for the entire fetched list -- callers that need a location (map
    // pins, sector rendering) skip a sighting with a null lat/lng; the sightings list still
    // shows it, just without coordinates.
    @SerialName("lat")
    val lat: Double? = null,

    @SerialName("lng")
    val lng: Double? = null,

    // Pod movement direction relative to the observer (AWAY/LEFT/RIGHT/NONE) — distinct from
    // headingDegrees below, which is the compass bearing FROM the observer TO the sighting.
    @SerialName("heading")
    val heading: String? = null,

    @SerialName("heading_degrees")
    val headingDegrees: Double? = null,

    // "SENSOR" or "MANUAL" — see HeadingSource
    @SerialName("heading_source")
    val headingSource: String? = null,

    // null for MANUAL; degrees of sensor uncertainty for SENSOR
    @SerialName("heading_accuracy_degrees")
    val headingAccuracyDegrees: Double? = null,

    // "CLOSE" / "MEDIUM" / "FAR" — see DistanceBucket
    @SerialName("distance_bucket")
    val distanceBucket: String? = null,

    // Resolved radius (meters) the bucket meant at capture time (shore vs. aerial/boat use
    // different scales), so the sector renders at the right size later without needing to
    // re-derive the observer's altitude.
    @SerialName("distance_radius_meters")
    val distanceRadiusMeters: Double? = null,

    @SerialName("count_whites")
    val countWhites: Int = 0,

    @SerialName("count_greys")
    val countGreys: Int = 0,

    @SerialName("count_calves")
    val countCalves: Int = 0,

    @SerialName("count_unknown")
    val countUnknown: Int = 0,

    @SerialName("observed_at_epoch_ms")
    val observedAtEpochMs: Long? = null,

    @SerialName("observer_type")
    val observerType: String? = null,

    // Whether GeofenceUtils' own check (isWithin3DFunnel or its isWithinCoastlineChannelFallback)
    // actually passed for this sighting, as opposed to the user overriding a rejected location
    // via the "SAVE ANYWAY" geofence warning dialog (LoggingScreen.kt/ManualLoggingScreen.kt).
    // Defaults to false, matching the sightings table's own column default -- callers must
    // explicitly opt a record into "verified" rather than that being assumed. This is the same
    // "verified" the RED presence-banner tier and confidence_filter = 'verified_only'
    // subscriptions key off (see get_watched_zone_statuses' comment).
    @SerialName("is_geofence_verified")
    val isGeofenceVerified: Boolean = false,

    @SerialName("photo_url")
    val photoUrl: String? = null,

    // --- Whale-position fields (observer-position -> whale-position redesign). lat/lng/
    // heading_degrees above are frozen at their old "observer position" / "observer->animal
    // bearing" meaning and are never written by new code -- these are the replacements. See
    // supabase/migrations/20260901000000_add_whale_position_columns.sql for the full rationale.

    // The estimated WHALE position -- never the observer's. Set directly from the dropped pin
    // (ManualLoggingScreen, positionSource = PIN) or projected from the observer's GPS fix via
    // a real heading+distance reading (LoggingScreen, PROJECTED) or CoastlineGeometry's
    // offshore-perpendicular guess when no heading was given (LoggingScreen, FALLBACK).
    @SerialName("whale_lat")
    val whaleLat: Double? = null,

    @SerialName("whale_lng")
    val whaleLng: Double? = null,

    // Resolved meters behind whaleLat/whaleLng's precision -- always present whenever
    // whaleLat/whaleLng are, since every positionSource derives one. This is what the map
    // draws as a plain circle (replacing the old heading/distance sector wedge) and what the
    // geofence check treats as the point's buffer, capped -- see isGeofenceVerified computation
    // at each capture site for why a user-claimed radius can't just be trusted uncapped.
    @SerialName("uncertainty_radius_meters")
    val uncertaintyRadiusMeters: Double? = null,

    // The label behind uncertaintyRadiusMeters -- DistanceBucket's names for PROJECTED/
    // FALLBACK rows, or the flow-agnostic manual picker's own label set for PIN rows.
    @SerialName("uncertainty_bucket")
    val uncertaintyBucket: String? = null,

    // The animal's own absolute direction of travel (0..359.99 degrees, true/magnetic north) --
    // NOT a locate vector like the old heading_degrees. Optional: null renders as a plain dot.
    // Derived on-device from a base compass bearing at capture time (AWAY = that bearing
    // unchanged, LEFT = -90, RIGHT = +90) and stored as the resolved absolute number -- the
    // relative AWAY/LEFT/RIGHT choice itself is not persisted.
    @SerialName("travel_bearing_degrees")
    val travelBearingDegrees: Double? = null,

    // Provenance of the base compass bearing travelBearingDegrees was derived from -- SENSOR
    // (live device compass) or MANUAL (typed/dialed in). Null whenever travelBearingDegrees is
    // null. Kept specifically so a researcher can weight the rendered arrow by whether the
    // underlying reading was measured or guessed.
    @SerialName("travel_bearing_source")
    val travelBearingSource: String? = null,

    // Discriminator for how whaleLat/whaleLng were derived, and the permanent, unambiguous
    // marker for "is this row old-meaning or new-meaning" -- see the migration's own comment.
    // Always set on a new-format row; always null on a legacy (pre-redesign) row. 'PIN' /
    // 'PROJECTED' / 'FALLBACK' -- see PositionSource.
    @SerialName("position_source")
    val positionSource: String? = null,

    // This device's own persistent identifier (AppPreferences.getOrCreateSubscriberId()),
    // set at capture time so the server can compute observerTier below at insert. WRITE-ONLY
    // from the app's own perspective: anon has no SELECT grant on this column at all (see
    // 20260903010000_add_observer_tier_system.sql's column-level lockdown), so a fetched
    // SightingRecord (getSightings/export_sightings) always decodes this as null regardless of
    // what was originally stored -- it is never read back, only written once at insert.
    @SerialName("subscriber_id")
    val subscriberId: String? = null,

    // Server-computed at insert time via a BEFORE INSERT trigger joining subscriberId above
    // against public.tier_roster -- never client-set, always null/1/2. See that migration's
    // header for why this is a stored column (a sighting keeps whatever tier applied when it
    // was made, regardless of later roster changes) rather than always re-derived live.
    @SerialName("observer_tier")
    val observerTier: Int? = null
)

// The map/list "high confidence only" filter's predicate (SightingsMapScreen.kt, App.kt's
// OfflineSightingsList) -- a photo is direct evidence regardless of who logged it; absent that,
// tier 1/2 (credentialed observer) is the next-best evidence. Plain tier-3/no-photo manual
// reports are hidden when the filter's on, not excluded from the data itself. Remote sightings
// only, deliberately -- this device's own not-yet-synced local queue has no observerTier at all
// (server-computed, never round-tripped back down) and should never be hidden from its owner
// just because it hasn't been classified yet.
val SightingRecord.isHighConfidence: Boolean
    get() = photoUrl != null || observerTier == 1 || observerTier == 2

// See SightingRecord.positionSource's doc comment.
enum class PositionSource { PIN, PROJECTED, FALLBACK }

enum class TravelBearingSource { SENSOR, MANUAL }

// Intermediate result while a logging screen resolves a whale position, before it's folded
// into a SightingRecord alongside the counts/timestamp/travel-bearing fields the position
// computation doesn't need to know about.
data class WhalePositionEstimate(
    val lat: Double,
    val lng: Double,
    val uncertaintyRadiusMeters: Double,
    val uncertaintyBucket: String?,
    val positionSource: PositionSource
)

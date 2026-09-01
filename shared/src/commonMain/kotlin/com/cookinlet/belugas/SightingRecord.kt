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
    val photoUrl: String? = null
)

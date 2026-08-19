package com.cookinlet.belugas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SightingRecord(
    @SerialName("id")
    val id: String = "",

    @SerialName("lat")
    val lat: Double = 0.0,

    @SerialName("lng")
    val lng: Double = 0.0,

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

    @SerialName("photo_url")
    val photoUrl: String? = null
)

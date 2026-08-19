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

    @SerialName("heading")
    val heading: String? = null,

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
    val observerType: String? = null
)

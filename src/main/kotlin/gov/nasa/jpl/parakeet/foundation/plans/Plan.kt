package gov.nasa.jpl.parakeet.foundation.plans

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Plan<M>(
    @SerialName("start")
    val startTime: Instant,
    @SerialName("end")
    val endTime: Instant,
    val activities: List<GroundedActivity<M>> = emptyList(),
) {
    init {
        require(startTime <= endTime) {
            "Malformed plan starts at $startTime, after it ends at $endTime"
        }
    }
}


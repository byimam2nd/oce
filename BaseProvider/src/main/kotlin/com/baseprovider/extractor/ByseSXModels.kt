package com.baseprovider.extractor

import com.fasterxml.jackson.annotation.JsonProperty

data class ByseDetailsRoot(
    val id: Long,
    val code: String,
    val title: String,
    @JsonProperty("poster_url") val posterUrl: String,
    val description: String,
    @JsonProperty("embed_frame_url") val embedFrameUrl: String
)

data class BysePlaybackRoot(val playback: BysePlayback)
data class BysePlayback(
    val algorithm: String,
    val iv: String,
    val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>
)

data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>)
data class BysePlaybackSource(val quality: String, val label: String,
    val url: String)

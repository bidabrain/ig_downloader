package com.mediasave

data class MediaItem(
    val type: String,
    val url: String,
    val filename: String,
    val source: String = "instagram"
)

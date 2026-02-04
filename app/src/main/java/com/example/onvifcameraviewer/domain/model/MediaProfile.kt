package com.example.onvifcameraviewer.domain.model

/**
 * Represents a media profile from an ONVIF device.
 * Each profile typically corresponds to a different stream quality/resolution.
 * 
 * @property token The profile token used in GetStreamUri requests
 * @property name Human-readable profile name
 * @property videoEncoderConfig Video encoder configuration details
 */
data class MediaProfile(
    val token: String,
    val name: String,
    val videoEncoderConfig: VideoEncoderConfig? = null
)

/**
 * Video encoder configuration details.
 */
data class VideoEncoderConfig(
    val encoding: String = "H264",  // H264, H265, MPEG4, etc.
    val width: Int = 0,
    val height: Int = 0,
    val frameRateLimit: Int = 0,
    val bitrateLimit: Int = 0
)
